library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Map Docker OS names to pre-baked image names
def DOCKER_IMAGE_MAP = [
    'oraclelinux:8':    'ghcr.io/evgeniypatlan/psmdb-build-oraclelinux8:latest',
    'oraclelinux:9':    'ghcr.io/evgeniypatlan/psmdb-build-oraclelinux9:latest',
    'ubuntu:focal':     'ghcr.io/evgeniypatlan/psmdb-build-focal:latest',
    'ubuntu:jammy':     'ghcr.io/evgeniypatlan/psmdb-build-jammy:latest',
    'ubuntu:noble':     'ghcr.io/evgeniypatlan/psmdb-build-noble:latest',
    'debian:bullseye':  'ghcr.io/evgeniypatlan/psmdb-build-bullseye:latest',
    'debian:bookworm':  'ghcr.io/evgeniypatlan/psmdb-build-bookworm:latest',
    'amazonlinux:2023': 'ghcr.io/evgeniypatlan/psmdb-build-amzn2023:latest',
]

// Derive S3 cache prefix from branch name
String getCachePrefix(String branch) {
    def m = (branch =~ /release-(\d+\.\d+\.\d+)/)
    if (m.find()) {
        return "release-${m.group(1)}"
    }
    return "trunk"
}

// Get OS short name for cache path
String getOSPrefix(String dockerOS) {
    def map = [
        'oraclelinux:8': 'el8', 'oraclelinux:9': 'el9',
        'ubuntu:focal': 'focal', 'ubuntu:jammy': 'jammy', 'ubuntu:noble': 'noble',
        'debian:bullseye': 'bullseye', 'debian:bookworm': 'bookworm',
        'amazonlinux:2023': 'amzn2023',
    ]
    return map[dockerOS] ?: dockerOS.replaceAll('[:/]', '-')
}

void startBazelCache(String containerName, String branch, String dockerOS, String arch = 'x86_64') {
    def cachePrefix = getCachePrefix(branch)
    def osPrefix = getOSPrefix(dockerOS)
    sh """
        docker run -d --name ${containerName} \
            --network=host \
            buchgr/bazel-remote-cache:v2.4.4 \
            --max_size=50 --dir=/tmp/cache \
            --s3.endpoint=s3.us-east-1.amazonaws.com \
            --s3.bucket=psmdb-bazel-cache \
            --s3.prefix=${cachePrefix}/${osPrefix}/${arch}/ \
            --s3.auth_method=iam_role \
            --grpc_address=0.0.0.0:9092 \
            --http_address=0.0.0.0:8080
        sleep 3
    """
}

void stopBazelCache(String containerName) {
    sh "docker stop ${containerName} 2>/dev/null; docker rm ${containerName} 2>/dev/null || true"
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    def prebakedImage = DOCKER_IMAGE_MAP[DOCKER_OS]
    def useCache = (prebakedImage != null)
    def image = useCache ? prebakedImage : DOCKER_OS
    def osPrefix = getOSPrefix(DOCKER_OS)
    def cacheName = "bazel-cache-${env.BUILD_NUMBER}-${osPrefix}"

    // Detect architecture from the agent we're running on
    def arch = sh(script: 'uname -m', returnStdout: true).trim()

    if (useCache) {
        startBazelCache(cacheName, GIT_BRANCH, DOCKER_OS, arch)
    }

    def cacheEnv = useCache ? '-e BAZEL_REMOTE_CACHE=grpc://localhost:9092' : ''
    def installDeps = useCache ? '' : "bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1 &&"

    try {
        sh """
            set -o xtrace
            ls -laR ./
            # Backup properties file if it exists
            if [ -f test/percona-server-mongodb-80.properties ]; then
                cp test/percona-server-mongodb-80.properties percona-server-mongodb-80.properties.backup
            fi
            rm -rf test/*
            mkdir -p test
            # Restore properties file if it was backed up
            if [ -f percona-server-mongodb-80.properties.backup ]; then
                mv percona-server-mongodb-80.properties.backup test/percona-server-mongodb-80.properties
            fi
            wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
            pwd -P
            ls -laR
            export build_dir=\$(pwd -P)
            docker run -u root --network=host \
                ${cacheEnv} \
                -v \${build_dir}:\${build_dir} ${image} sh -c "
                set -o xtrace
                cd \${build_dir}
                ${installDeps}
                bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} --mongo_tools_tag=${MONGO_TOOLS_TAG} ${STAGE_PARAM}"
        """
    } finally {
        if (useCache) {
            stopBazelCache(cacheName)
        }
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['AWS','Hetzner'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for  percona-server-mongodb repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'v8.0',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '8.0.0',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.9.5',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-80',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
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

            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    buildStage("oraclelinux:8", "--get_sources=1")
                }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-80.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-80.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-mongodb-80.properties', name: 'psmdb-properties'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PSMDB generic source packages') {

            parallel {
                stage('Build PSMDB generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
        } // stage
        stage('Build PSMDB RPMs/DEBs/Binary tarballs') {

            parallel {
                stage('Oracle Linux 8(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                }
                stage('Ubuntu Noble(24.04) binary tarball(glibc2.39)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                stage('Debian Bookworm(12) binary tarball(glibc2.36)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                }
            }
        }

        stage('Upload packages and tarballs from S3') {

            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
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
                // sync packages
                script {
                    sync2ProdAutoBuild(params.CLOUD, PSMDB_REPO, COMPONENT)
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {

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
        stage('Run testing job') {
            when {
                expression { return params.TESTS == 'yes' }
            }
            steps {
                script {
                    build job: 'hetzner-psmdb-multijob-testing', propagate: false, wait: false, quietPeriod: 1800, parameters: [string(name: 'PSMDB_VERSION', value: PSMDB_VERSION), string(name: 'PSMDB_RELEASE', value: PSMDB_RELEASE)]
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
