library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        ls -laR ./
        # Backup properties file if it exists
        if [ -f test/percona-server-mongodb-83.properties ]; then
            cp test/percona-server-mongodb-83.properties percona-server-mongodb-83.properties.backup
        fi
        rm -rf test/*
        mkdir -p test
        # Restore properties file if it was backed up
        if [ -f percona-server-mongodb-83.properties.backup ]; then
            mv percona-server-mongodb-83.properties.backup test/percona-server-mongodb-83.properties
        fi
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            ls -laR ./
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1
            ls -la ./test/
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} --mongo_tools_tag=${MONGO_TOOLS_TAG} ${STAGE_PARAM}"
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

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
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for  percona-server-mongodb repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'preview-8.3.0-0',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '8.3.0',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.14.1',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-83',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            name: 'BUILD_PACKAGES',
            choices: ['true', 'false'],
            description: 'Build packages and tarballs (default: true)')
        choice(
            name: 'TESTS',
            choices: ['yes', 'no'],
            description: 'Run functional tests on packages and tarballs after building')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PSMDB source tarball') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
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
                   # Use 83 properties file; if build script created 80 (or other), copy to 83 for pipeline
                   if [ ! -f test/percona-server-mongodb-83.properties ]; then
                       OTHER=$(ls test/percona-server-mongodb-*.properties 2>/dev/null | head -1)
                       if [ -n "$OTHER" ]; then
                           cp "$OTHER" test/percona-server-mongodb-83.properties
                       else
                           echo "No percona-server-mongodb-*.properties found in test/"
                           ls -la test/ || true
                           exit 1
                       fi
                   fi
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-83.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-83.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-mongodb-83.properties', name: 'psmdb-properties'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
/*        stage('Build PSMDB generic source packages') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            parallel {
                stage('Build PSMDB generic source rpm') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:8", "--build_src_rpm=1")
                        }

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PSMDB generic source deb') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:jammy", "--build_src_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage */
        stage('Build PSMDB RPMs/DEBs/Binary tarballs') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            parallel {
/*                stage('Oracle Linux 8(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
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
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            buildStage("debian:bookworm", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 binary tarball(glibc2.28)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:8", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Oracle Linux 9 binary tarball(glibc2.34)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:9", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Amazon Linux 2023 binary tarball(glibc2.34)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("amazonlinux:2023", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) binary tarball(glibc2.35)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:jammy", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }*/
                stage('Ubuntu Noble(24.04) binary tarball(glibc2.39)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:noble", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
/*                stage('Debian Bookworm(12) binary tarball(glibc2.36)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("debian:bookworm", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }*/
            }
        }

        stage('Upload packages and tarballs from S3') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            agent {
                label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
            }
            steps {
                cleanUpWS()
                // Only tarball for 24.04 build
                // uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                // uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
            }
        }

/*        stage('Sign packages') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            steps {
                // sync packages
                script {
                    sync2ProdAutoBuild(params.CLOUD, PSMDB_REPO, COMPONENT)
                }
            }
        }*/
        stage('Push Tarballs to TESTING download area') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "psmdb", "${PSMDB_VERSION}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
/*        stage('Run testing job') {
            when {
                allOf {
                    expression { return params.BUILD_PACKAGES == 'true' }
                    expression { return params.TESTS == 'yes' }
                }
            }
            steps {
                script {
                    def version = "${PSMDB_VERSION}-${PSMDB_RELEASE}"
                    build job: 'psmdb-tarball-functional', propagate: false, wait: true, parameters: [string(name: 'PSMDB_VERSION', value: version), string(name: 'TESTING_BRANCH', value: 'main')]
                    build job: 'psmdb-parallel', propagate: false, wait: false, parameters: [string(name: 'REPO', value: 'testing'), string(name: 'PSMDB_VERSION', value: PSMDB_VERSION), string(name: 'ENABLE_TOOLKIT', value: 'false'), string(name: 'TESTING_BRANCH', value: 'main')]
                }
            }
        }*/
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
