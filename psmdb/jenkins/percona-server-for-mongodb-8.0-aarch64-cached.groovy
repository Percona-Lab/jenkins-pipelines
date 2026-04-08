library changelog: false, identifier: 'lib@master', retriever: modernSCM([
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

String getCachePrefix(String branch) {
    def m = (branch =~ /release-(\d+\.\d+\.\d+)/)
    if (m.find()) {
        return "release-${m.group(1)}"
    }
    return "trunk"
}

String getOSPrefix(String dockerOS) {
    def map = [
        'oraclelinux:8': 'el8', 'oraclelinux:9': 'el9',
        'ubuntu:focal': 'focal', 'ubuntu:jammy': 'jammy', 'ubuntu:noble': 'noble',
        'debian:bullseye': 'bullseye', 'debian:bookworm': 'bookworm',
        'amazonlinux:2023': 'amzn2023',
    ]
    return map[dockerOS] ?: dockerOS.replaceAll('[:/]', '-')
}

void startBazelCache(String containerName, String branch, String dockerOS, String arch = 'aarch64') {
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

    if (useCache) {
        startBazelCache(cacheName, GIT_BRANCH, DOCKER_OS, 'aarch64')
    }

    def cacheEnv = useCache ? '-e BAZEL_REMOTE_CACHE=grpc://localhost:9092' : ''
    def installDeps = useCache ? '' : "bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1 &&"

    try {
        sh """
            set -o xtrace
            mkdir test
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
        label 'master'
    }
    parameters {
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
            choices: 'no\nyes',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
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
        stage('Create PSMDB source tarball') {
            agent {
                label 'docker-64gb-aarch64'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_sources=1")
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
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PSMDB generic source packages') {
            parallel {
                stage('Build PSMDB generic source rpm') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'yes') {
                                buildStage("oraclelinux:8", "--build_src_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:8", "--build_src_rpm=1")
                            }
                        }

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PSMDB generic source deb') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'yes') {
                                buildStage("ubuntu:focal", "--build_src_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:focal", "--build_src_deb=1")
                            }
                        }

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadRPMfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PSMDB aarch64 Packages') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'yes') {
                                buildStage("oraclelinux:8", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:8", "--build_rpm=1")
                            }
                        }

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'yes') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
 /*               stage('Amazon Linux 2023') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'yes') {
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("amazonlinux:2023", "--build_rpm=1")
                            }
                        }
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }*/
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'yes') {
                                buildStage("ubuntu:focal", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:focal", "--build_deb=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'yes') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'yes') {
                                buildStage("ubuntu:noble", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
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
                    if (env.FIPSMODE == 'yes') {
                        sync2PrivateProdAutoBuild(PSMDB_REPO+"-pro", COMPONENT)
                    } else {
                        sync2ProdAutoBuild(PSMDB_REPO, COMPONENT)
                    }
                }
            }
        }
    }
    post {
         success {
             script {
                if (env.FIPSMODE == 'YES') {
                    slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: PRO build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
                }
                if (env.FIPSMODE == 'YES') {
                    currentBuild.description = "!!! PRO Built on ${GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"
                } else {
                    currentBuild.description = "Built on ${GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"
                }
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
