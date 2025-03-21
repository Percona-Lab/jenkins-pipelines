library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        sh """
            set -o xtrace
            ls -laR ./
            rm -rf test/*
            mkdir -p test
            if [ \${FULL_FEATURED} = "yes" ]; then
                PRO_BRANCH="v6.0"
                curl -L -H "Authorization: Bearer \${TOKEN}" \
                      -H "Accept: application/vnd.github.v3.raw" \
                      -o psmdb_builder.sh \
                      "https://api.github.com/repos/percona/mongo-build-scripts/contents/scripts/psmdb_builder.sh?ref=\${PRO_BRANCH}"
            else
                wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
            fi
            pwd -P
            ls -laR
            export build_dir=\$(pwd -P)
            docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                set -o xtrace
                cd \${build_dir}
                bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1
                if [ \${FULL_FEATURED} = "yes" ]; then
                    git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/mongo-build-scripts.git percona-packaging
                    mv -f \${build_dir}/percona-packaging \${build_dir}/test/.
                    ls -la \${build_dir}/percona-packaging
                fi
                bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} --mongo_tools_tag=${MONGO_TOOLS_TAG} ${STAGE_PARAM}"
    """
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
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for  percona-server-mongodb repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'v6.0',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '6.0.0',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.5.4',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-60',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'no\nyes',
            description: 'Enable all pro features',
            name: 'FULL_FEATURED')
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
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    if (env.FULL_FEATURED == 'yes') {
                        buildStage("oraclelinux:8", "--get_sources=1 --full_featured=1")
                    } else {
                        buildStage("oraclelinux:8", "--get_sources=1")
                    }
                }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-60.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-60.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
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
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:8", "--build_src_rpm=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:8", "--build_src_rpm=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:focal", "--build_src_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:focal", "--build_src_deb=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:8", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:8", "--build_rpm=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:8", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:8", "--build_rpm=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("amazonlinux:2023", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("amazonlinux:2023", "--build_rpm=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("amazonlinux:2023", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("amazonlinux:2023", "--build_rpm=1")
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:focal", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:focal", "--build_deb=1")
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)(aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:focal", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:focal", "--build_deb=1")
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:noble", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:noble", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("debian:bullseye", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("debian:bullseye", "--build_deb=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:8", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:8", "--build_tarball=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:9", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("amazonlinux:2023", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("amazonlinux:2023", "--build_tarball=1")
                            }
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Focal(20.04) binary tarball(glibc2.31)') {
                    when {
                        expression { env.FULL_FEATURED != 'yes' }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11) binary tarball(glibc2.31)') {
                    when {
                        expression { env.FULL_FEATURED != 'yes' }
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_tarball=1")
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) binary tarball(glibc2.35)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1")
                            }
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
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:noble", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_tarball=1")
                            }
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
                    if (env.FULL_FEATURED == 'yes') {
                        // Replace by a new procedure when it's ready
                        sync2PrivateProdAutoBuild(params.CLOUD, PSMDB_REPO+"-pro", COMPONENT)
                    } else {
                        sync2ProdAutoBuild(params.CLOUD, PSMDB_REPO, COMPONENT)
                    }
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    if (env.FULL_FEATURED == 'yes') {
                        try {
                            uploadTarballToDownloadsTesting(params.CLOUD, "psmdb-gated", "${PSMDB_VERSION}")
                        }
                        catch (err) {
                            echo "Caught: ${err}"
                            currentBuild.result = 'UNSTABLE'
                        }
                    } else {
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
        }
        stage('Build docker containers') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                script {
                    if (env.FULL_FEATURED == 'YES') {
                        echo "The step is skipped"
                    } else {
                        echo "====> Build docker containers"
                        cleanUpWS()
                        sh '''
                            sleep 1200
                        '''
                        unstash 'uploadPath'
                        sh '''
                            sudo apt-get -y install apparmor
                            sudo aa-status
                            sudo systemctl stop apparmor
                            sudo systemctl disable apparmor
                            sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                            sudo apt-get -y install apparmor
                            sudo aa-status
                            sudo systemctl stop apparmor
                            sudo systemctl disable apparmor
                            sudo apt-get install -y docker-ce docker-ce-cli containerd.io
                            export DOCKER_CLI_EXPERIMENTAL=enabled
                            sudo mkdir -p /usr/libexec/docker/cli-plugins/
                            sudo curl -L https://github.com/docker/buildx/releases/download/v0.21.2/buildx-v0.21.2.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            sudo apt-get install -y qemu-system binfmt-support qemu-user-static
                            sudo qemu-system-x86_64 --version
                            sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                            git clone https://github.com/percona/percona-docker
                            cd percona-docker/percona-server-mongodb-6.0
                            sed -i "s/ENV PSMDB_VERSION.*/ENV PSMDB_VERSION ${PSMDB_VERSION}-${PSMDB_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PSMDB_REPO.*/ENV PSMDB_REPO ${COMPONENT}/g" Dockerfile
                            sudo docker build --no-cache --platform "linux/amd64" -t perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE} .

                            sed -i "s/ENV PSMDB_VERSION.*/ENV PSMDB_VERSION ${PSMDB_VERSION}-${PSMDB_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PSMDB_REPO.*/ENV PSMDB_REPO ${COMPONENT}/g" Dockerfile.aarch64
                            sudo docker build --no-cache -t perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-aarch64 --platform="linux/arm64" -f Dockerfile.aarch64 .

                            sudo docker images
                        '''
                        withCredentials([
                            usernamePassword(credentialsId: 'hub.docker.com',
                            passwordVariable: 'PASS',
                            usernameVariable: 'USER'
                            )]) {
                            sh '''
                                echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                                sudo docker push perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}
                                sudo docker push perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-aarch64

                                PSMDB_MAJOR_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f1)
                                PSMDB_MINOR_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f2)
                                PSMDB_PATCH_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f3)
                                sudo docker manifest create perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} \
                                    perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE} \
                                    perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-aarch64
                                sudo docker manifest annotate perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-aarch64 --os linux --arch arm64 --variant v8
                                sudo docker manifest annotate perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE} --os linux --arch amd64
                                sudo docker manifest inspect perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION}

                                sudo docker manifest create perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} \
                                    perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE} \
                                    perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-aarch64
                                sudo docker manifest annotate perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-aarch64 --os linux --arch arm64 --variant v8
                                sudo docker manifest annotate perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE} --os linux --arch amd64
                                sudo docker manifest inspect perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}

                                sudo docker manifest push perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION}
                                sudo docker manifest push perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}
                            '''
                           }
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                if (env.FULL_FEATURED == 'yes') {
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
