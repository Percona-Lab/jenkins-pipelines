library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Builds percona-server-mongodb-tools (the MongoDB Database Tools) independently of the
// PSMDB server build -- see PSMDB-1944. Structure mirrors percona-server-for-mongodb-8.3
// minus everything bazel/RBE related: these are 8 Go binaries, so plain distro images and
// ordinary agents are enough.
//
// Unlike the PSMDB job, the builder does not live in the product repo: it comes from
// percona-mongodb-tools-packaging, and it needs the whole repo (spec template, debian/,
// manpages/, docs/, go-deps.env), not just the script. Hence the tarball fetch below
// rather than the single-file wget the PSMDB and mongosh jobs use.
void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        # keep the props file across the workspace wipe, same as the PSMDB job
        if [ -f test/percona-server-mongodb-tools.properties ]; then
            cp test/percona-server-mongodb-tools.properties tools.properties.backup
        fi
        rm -rf test/* tools-packaging
        mkdir -p test
        if [ -f tools.properties.backup ]; then
            mv tools.properties.backup test/percona-server-mongodb-tools.properties
        fi

        wget \$(echo ${BUILD_GIT_REPO} | sed -re 's|\\.git\$||')/archive/${BUILD_GIT_BRANCH}.tar.gz -O packaging.tar.gz
        tar zxf packaging.tar.gz
        mv percona-mongodb-tools-packaging-* tools-packaging
        rm -f packaging.tar.gz

        pwd -P
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./tools-packaging/scripts/mongo_tools_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./tools-packaging/scripts/mongo_tools_builder.sh --builddir=\${build_dir}/test --repo=${TOOLS_REPO} --branch=${TOOLS_TAG} --release=${TOOLS_RELEASE} ${STAGE_PARAM}"
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
            choices: [ 'Hetzner', 'AWS' ],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/mongodb/mongo-tools.git',
            description: 'URL for the mongo-tools repository to build from',
            name: 'TOOLS_REPO')
        string(
            defaultValue: '100.18.0',
            description: 'Tag/Branch of mongo-tools to build. https://github.com/mongodb/mongo-tools/tags',
            name: 'TOOLS_TAG')
        string(
            defaultValue: '1',
            description: 'Package release number. Bump this for a rebuild of the same tools tag, e.g. a CVE fix via go-deps.env',
            name: 'TOOLS_RELEASE')
        string(
            defaultValue: 'https://github.com/percona/percona-mongodb-tools-packaging.git',
            description: 'URL for the tools packaging repository',
            name: 'BUILD_GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for the packaging repository',
            name: 'BUILD_GIT_BRANCH')
        string(
            defaultValue: 'psmdb-83',
            description: 'PSMDB repo name to push into. The same build can be pushed to psmdb-70/80/83 in turn',
            name: 'PSMDB_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            name: 'TESTS',
            choices: ['yes', 'no'],
            description: 'Run package tests after building')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create tools source tarball') {
            agent {
                label params.CLOUD == 'AWS' ? 'docker' : 'docker-x64'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for mongo-tools ${TOOLS_TAG}-${TOOLS_RELEASE} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-tools.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-tools.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-mongodb-tools.properties', name: 'tools-properties'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build tools generic source packages') {
            parallel {
                stage('Build tools generic source rpm') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_src_rpm=1")
                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build tools generic source deb') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_src_deb=1")
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Build tools RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Oracle Linux 8(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8(aarch64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb-aarch64' : 'docker-aarch64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(aarch64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb-aarch64' : 'docker-aarch64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10(aarch64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb-aarch64' : 'docker-aarch64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023(aarch64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb-aarch64' : 'docker-aarch64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)(aarch64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb-aarch64' : 'docker-aarch64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)(aarch64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb-aarch64' : 'docker-aarch64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13)(x86_64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_deb=1")
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13)(aarch64)') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb-aarch64' : 'docker-aarch64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_deb=1")
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                // Binary tarballs are x86_64 only, as in the PSMDB job (PSMDB-1944, OQ-1)
                stage('Oracle Linux 8 binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10 binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023 binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04) binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11) binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12) binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13) binary tarball') {
                    agent { label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64' }
                    steps {
                        cleanUpWS()
                        unstash 'tools-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
            }
        }

        stage('Upload packages and tarballs from S3') {
            agent {
                label params.CLOUD == 'AWS' ? 'docker-32gb' : 'docker-x64'
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
                    // Gated push: only OS/arch combinations that already carry the
                    // percona-server-mongodb metapackage in this repo+component are published,
                    // so the server has to be pushed first. Nothing is ever removed.
                    sync2ProdAutoBuildPSMDB(params.CLOUD, PSMDB_REPO, COMPONENT)
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "psmdb-tools", "${TOOLS_TAG}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Run testing job') {
            when {
                expression { return params.TESTS == 'yes' }
            }
            steps {
                script {
                    build job: 'hetzner-psmdb-tools-package-testing', propagate: false, wait: false, quietPeriod: 600, parameters: [
                        string(name: 'TOOLS_VERSION', value: TOOLS_TAG),
                        string(name: 'TOOLS_RELEASE', value: TOOLS_RELEASE),
                        string(name: 'PSMDB_REPO', value: PSMDB_REPO),
                        string(name: 'COMPONENT', value: COMPONENT)
                    ]
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build finished successfully for mongo-tools ${TOOLS_TAG}-${TOOLS_RELEASE} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built mongo-tools ${TOOLS_TAG}-${TOOLS_RELEASE}. Path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for mongo-tools ${TOOLS_TAG}-${TOOLS_RELEASE} - [${BUILD_URL}]")
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
