library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        ls -laR ./
        # Backup properties file if it exists
        if [ -f test/percona-server-mongodb-mongot.properties ]; then
            cp test/percona-server-mongodb-mongot.properties percona-server-mongodb-mongot.properties.backup
        fi
        rm -rf test/*
        mkdir -p test
        # Restore properties file if it was backed up
        if [ -f percona-server-mongodb-mongot.properties.backup ]; then
            mv percona-server-mongodb-mongot.properties.backup test/percona-server-mongodb-mongot.properties
        fi
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/percona-packaging/scripts/mongot_builder.sh -O mongot_builder.sh
        chmod +x mongot_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./mongot_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./mongot_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --version=${VERSION} --ps4m_release=${PS4M_RELEASE} ${STAGE_PARAM}"
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

// Pull only the matching-arch mongot bundle from tarball/ in per-arch package stages.
def S3_FILTER_X64 = "--exclude '*' --include '*linux_x86_64*'"
def S3_FILTER_ARM = "--exclude '*' --include '*linux_aarch64*'"

pipeline {
    agent {
        label params.CLOUD == 'AWS' ? 'micro-amazon' : 'launcher-x64'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/percona/percona-mongot.git',
            description: 'URL for Percona Search for MongoDB (mongot) repository (contains percona-packaging/)',
            name: 'GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for Percona mongot fork',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '0.50.0',
            description: 'mongot release version',
            name: 'VERSION')
        string(
            defaultValue: '1',
            description: 'Package release/revision number (same for rpm and deb)',
            name: 'PS4M_RELEASE')
        string(
            defaultValue: 'psmdb-83',
            description: 'Target repo name for sync2ProdAutoBuild (mongot is shipped under PSMDB repo)',
            name: 'MONGOT_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create mongot source tarball') {
            agent {
                label params.CLOUD == 'AWS' ? 'docker' : 'docker-x64'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    buildStage("oraclelinux:8", "--get_sources=1")
                }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-mongot.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-mongot.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-mongodb-mongot.properties', name: 'mongot-properties'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }

        // The mongot bundle is the bazel deploy tarball — JDK + jars + native
        // .so libs. It is architecture-specific (per-arch JDK and natives) but
        // OS-agnostic (uses bundled JDK, no glibc-coupled binaries we ship),
        // so we produce exactly two bundles. These bundles are reused as both
        // the public binary tarball release AND as input to RPM/DEB stages.
        stage('Build mongot bundles') {
            parallel {
                stage('Bundle x86_64') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:8", "--build_mongot=1 --build_variant=linux-x64")
                        }
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Bundle aarch64') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:8", "--build_mongot=1 --build_variant=linux-aarch64")
                        }
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
            }
        }

        stage('Build mongot RPMs/DEBs') {
            parallel {
                stage('Oracle Linux 8(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_X64)
                        script {
                            buildStage("oraclelinux:8", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_ARM)
                        script {
                            buildStage("oraclelinux:8", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_X64)
                        script {
                            buildStage("oraclelinux:9", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_ARM)
                        script {
                            buildStage("oraclelinux:9", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_X64)
                        script {
                            buildStage("amazonlinux:2023", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_ARM)
                        script {
                            buildStage("amazonlinux:2023", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_X64)
                        script {
                            buildStage("ubuntu:jammy", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_ARM)
                        script {
                            buildStage("ubuntu:jammy", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_X64)
                        script {
                            buildStage("ubuntu:noble", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_ARM)
                        script {
                            buildStage("ubuntu:noble", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'mongot-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH, S3_FILTER_X64)
                        script {
                            buildStage("debian:bookworm", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
            }
        }

        stage('Upload packages and tarballs from S3') {
            agent {
                label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
            }
            steps {
                cleanUpWS()

                uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
            }
        }

        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            steps {
                script {
                    sync2ProdAutoBuild(params.CLOUD, MONGOT_REPO, COMPONENT)
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "mongot", "${VERSION}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
