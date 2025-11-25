library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        ls -laR ./
        rm -rf test/*
        mkdir -p test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1
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
            defaultValue: 'v7.0',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '7.0.26',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.13.0',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-70',
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
        choice(
            name: 'BUILD_DOCKER',
            choices: ['true', 'false'],
            description: 'Build and push Docker images (default: true)')
        choice(
            name: 'BUILD_PACKAGES',
            choices: ['true', 'false'],
            description: 'Build packages (default: true)')
        choice(
            name: 'TARGET_REPO',
            choices: ['PerconaLab','DockerHub'],
            description: 'Target repo for docker image, use DockerHub for release only')
        choice(
            name: 'PSMDB_REPO_TYPE',
            choices: ['testing','release','experimental'],
            description: 'Packages repo for docker images')
        choice(
            name: 'DEBUG',
            choices: ['no','yes'],
            description: 'Additionally build debug image')
         choice(
             name: 'TESTS',
             choices: ['yes','no'],
             description: 'Run tests after building')
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
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    buildStage("oraclelinux:8", "--get_sources=1")
                }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-70.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-70.properties
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
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            parallel {
                stage('Build PSMDB generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
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
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            parallel {
                stage('Oracle Linux 8(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
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
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:noble", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            buildStage("debian:bullseye", "--build_deb=1")
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
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:noble", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Debian Bullseye(11) binary tarball(glibc2.31)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("debian:bullseye", "--build_tarball=1")
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
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
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
                    if (env.FULL_FEATURED == 'yes') {
                        sync2PrivateProdAutoBuild(params.CLOUD, PSMDB_REPO+"-pro", COMPONENT)
                    } else {
                        sync2ProdAutoBuild(params.CLOUD, PSMDB_REPO, COMPONENT)
                    }
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
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
        stage ('Build docker containers for aws ecr') {
            when {
                allOf {
                    expression { return params.BUILD_DOCKER == 'true' }
                    environment name: 'TARGET_REPO', value: 'AWS_ECR'
                }
            }
            steps {
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                     sh """
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
                         curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                         if [ -f "/usr/bin/yum" ] ; then sudo yum install -y unzip ; else sudo apt-get update && sudo apt-get -y install unzip ; fi
                         unzip -o awscliv2.zip
                         sudo ./aws/install
                         aws ecr-public get-login-password --region us-east-1 | sudo docker login --username AWS --password-stdin public.ecr.aws/e7j3v3n0
                         git clone https://github.com/percona/percona-docker
                         cd percona-docker/percona-server-mongodb-7.0
                         sed -i "s/ENV PSMDB_VERSION.*/ENV PSMDB_VERSION ${PSMDB_VERSION}-${PSMDB_RELEASE}/g" Dockerfile
                         sed -i "s/ENV PSMDB_REPO.*/ENV PSMDB_REPO ${PSMDB_REPO_TYPE}/g" Dockerfile
                         sudo docker build --no-cache --platform "linux/amd64" -t percona-server-mongodb-amd64 -f Dockerfile .
                         if [ ${DEBUG} = "yes" ]; then
                              sed -E "s/FROM percona(.+)/FROM percona-server-mongodb/" -i Dockerfile.debug
                              sudo docker build . -f Dockerfile.debug --no-cache --platform "linux/amd64" -t percona-server-mongodb-debug
                         fi
                         sudo docker tag percona-server-mongodb public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-${PSMDB_VERSION}-amd64
                         sudo docker push public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-${PSMDB_VERSION}-amd64
                         if [ ${DEBUG} = "yes" ]; then
                            sudo docker tag percona-server-mongodb-debug public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-${PSMDB_VERSION}-debug
                            sudo docker push public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-${PSMDB_VERSION}-debug
                         fi
                     """
                }
            }
        }
        stage('Build docker containers for PerconaLab') {
            when {
                allOf {
                    expression { return params.BUILD_DOCKER == 'true' }
                    environment name: 'TARGET_REPO', value: 'PerconaLab'
                }
            }
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                script {
                    if (env.FULL_FEATURED == 'yes') {
                        echo "The step is skipped"
                    } else {
                        echo "====> Build docker containers"
                        cleanUpWS()
                        sh '''
                            sleep 1500
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
                            cd percona-docker/percona-server-mongodb-7.0
                            sed -i "s/ENV PSMDB_VERSION.*/ENV PSMDB_VERSION ${PSMDB_VERSION}-${PSMDB_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PSMDB_REPO.*/ENV PSMDB_REPO ${PSMDB_REPO_TYPE}/g" Dockerfile
                            sudo docker build --no-cache --platform "linux/amd64" -t percona-server-mongodb-amd64 -f Dockerfile .
                            sudo docker tag percona-server-mongodb-amd64 perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64

                            if [ ${DEBUG} = "yes" ]; then
                                 sed -E "s/FROM percona(.+)/FROM percona-server-mongodb/" -i Dockerfile.debug
                                 sudo docker build . -f Dockerfile.debug --no-cache --platform "linux/amd64" -t percona-server-mongodb-debug
                            fi

                            sed -i "s/ENV PSMDB_VERSION.*/ENV PSMDB_VERSION ${PSMDB_VERSION}-${PSMDB_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PSMDB_REPO.*/ENV PSMDB_REPO ${PSMDB_REPO_TYPE}/g" Dockerfile.aarch64
                            sudo docker build --no-cache --platform "linux/arm64" -t percona-server-mongodb-arm64 -f Dockerfile.aarch64 .
                            sudo docker tag percona-server-mongodb-arm64 perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64

                            sudo docker images
                        '''
                        withCredentials([
                            usernamePassword(credentialsId: 'hub.docker.com',
                            passwordVariable: 'PASS',
                            usernameVariable: 'USER'
                            )]) {
                            sh '''
                                echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                                sudo docker push perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64
                                sudo docker push perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64

                                PSMDB_MAJOR_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f1)
                                PSMDB_MINOR_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f2)
                                PSMDB_PATCH_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f3)
                                sudo docker manifest create --amend perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} \
                                    perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64 \
                                    perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64
                                sudo docker manifest annotate perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                                sudo docker manifest annotate perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64 --os linux --arch amd64
                                sudo docker manifest inspect perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION}

                                sudo docker manifest create --amend perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} \
                                    perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64 \
                                    perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64
                                sudo docker manifest annotate perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                                sudo docker manifest annotate perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64 --os linux --arch amd64
                                sudo docker manifest inspect perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}

                                sudo docker manifest push perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION}
                                sudo docker manifest push perconalab/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}
                                if [ ${DEBUG} = "yes" ]; then
                                     sudo docker tag percona-server-mongodb-debug perconalab/percona-server-mongodb:${PSMDB_VERSION}-debug
                                     sudo docker push perconalab/percona-server-mongodb:${PSMDB_VERSION}-debug
                                fi

                            '''
                           }
                    }
                }
            }
        }
        stage('Build docker containers for DockerHub registry') {
            when {
                allOf {
                    expression { return params.BUILD_DOCKER == 'true' }
                    environment name: 'TARGET_REPO', value: 'DockerHub'
                }
            }
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                script {
                    if (env.FULL_FEATURED == 'yes') {
                        echo "The step is skipped"
                    } else {
                        echo "====> Build docker containers"
                        cleanUpWS()
                        sh '''
                            sleep 1500
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
                            cd percona-docker/percona-server-mongodb-7.0
                            sed -i "s/ENV PSMDB_VERSION.*/ENV PSMDB_VERSION ${PSMDB_VERSION}-${PSMDB_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PSMDB_REPO.*/ENV PSMDB_REPO ${PSMDB_REPO_TYPE}/g" Dockerfile
                            sudo docker build --no-cache --platform "linux/amd64" -t percona-server-mongodb-amd64 -f Dockerfile .
                            sudo docker tag percona-server-mongodb-amd64 percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64

                            if [ ${DEBUG} = "yes" ]; then
                                 sed -E "s/FROM percona(.+)/FROM percona-server-mongodb/" -i Dockerfile.debug
                                 sudo docker build . -f Dockerfile.debug --no-cache --platform "linux/amd64" -t percona-server-mongodb-debug
                            fi

                            sed -i "s/ENV PSMDB_VERSION.*/ENV PSMDB_VERSION ${PSMDB_VERSION}-${PSMDB_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PSMDB_REPO.*/ENV PSMDB_REPO ${PSMDB_REPO_TYPE}/g" Dockerfile.aarch64
                            sudo docker build --no-cache --platform "linux/arm64" -t percona-server-mongodb-arm64 -f Dockerfile.aarch64 .
                            sudo docker tag percona-server-mongodb-arm64 percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64

                            sudo docker images
                        '''
                        withCredentials([
                            usernamePassword(credentialsId: 'hub.docker.com',
                            passwordVariable: 'PASS',
                            usernameVariable: 'USER'
                            )]) {
                            sh '''
                                echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                                sudo docker push percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64
                                sudo docker push percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64

                                PSMDB_MAJOR_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f1)
                                PSMDB_MINOR_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f2)
                                PSMDB_PATCH_VERSION=$(echo $PSMDB_VERSION | cut -d'.' -f3)
                                sudo docker manifest create --amend percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} \
                                    percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64 \
                                    percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64
                                sudo docker manifest annotate percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64 --os linux
 --arch arm64 --variant v8
                                sudo docker manifest annotate percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION} percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64 --os linux
 --arch amd64
                                sudo docker manifest inspect percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION}

                                sudo docker manifest create --amend percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} \
                                    percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64 \
                                    percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64
                                sudo docker manifest annotate percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-arm64 --os linux --arch arm64 --variant  v8
                                sudo docker manifest annotate percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION} percona/percona-server-mongodb:${PSMDB_VERSION}-${PSMDB_RELEASE}-amd64 --os linux --arch amd64
                                sudo docker manifest inspect percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}

                                sudo docker manifest push percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}.${PSMDB_PATCH_VERSION}
                                sudo docker manifest push percona/percona-server-mongodb:${PSMDB_MAJOR_VERSION}.${PSMDB_MINOR_VERSION}

                                if [ ${DEBUG} = "yes" ]; then
                                     sudo docker tag percona-server-mongodb-debug percona/percona-server-mongodb:${PSMDB_VERSION}-debug
                                     sudo docker push percona/percona-server-mongodb:${PSMDB_VERSION}-debug
                                fi

                            '''
                           }
                    }
                }
            }
        }
        stage ('Run testing job') {
            when {
                allOf {
                    expression { return params.BUILD_DOCKER == 'true' }
                    environment name: 'TESTS', value: 'yes'
                }
            }
            steps {
                script {
                    def psmdb_image = 'percona/percona-server-mongodb:' + params.PSMDB_VERSION + '-amd64'
                    if ( params.PSMDB_REPO_TYPE == 'testing' ) {
                        psmdb_image = 'perconalab/percona-server-mongodb:' + params.PSMDB_VERSION + '-amd64'
                    }
                    if ( params.PSMDB_REPO_TYPE == 'experimental' ) {
                        psmdb_image = 'public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-' + params.PSMDB_VERSION + '-amd64'
                    }
                    def pbm_branch = sh(returnStdout: true, script: """
                        git clone https://github.com/percona/percona-backup-mongodb.git >/dev/null 2>/dev/null
                        PBM_RELEASE=\$(cd percona-backup-mongodb && git branch -r | grep release | sed 's|origin/||' | sort --version-sort | tail -1)
                        echo \$PBM_RELEASE
                        """).trim()
                    build job: 'hetzner-pbm-functional-tests', propagate: false, wait: false, parameters: [string(name: 'PBM_BRANCH', value: pbm_branch ), string(name: 'PSMDB', value: psmdb_image ), string(name: 'TESTING_BRANCH', value: "pbm-${pbm_branch}")]
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
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
