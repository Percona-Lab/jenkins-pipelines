library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/packaging/scripts/mongodb-backup_builder.sh -O mongodb-backup_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./mongodb-backup_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./mongodb-backup_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --version=${VERSION} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}"
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
            defaultValue: 'https://github.com/percona/percona-backup-mongodb.git',
            description: 'URL for percona-mongodb-backup repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'dev',
            description: 'Tag/Branch for percona-mongodb-backup repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        string(
            defaultValue: '2.10.0',
            description: 'VERSION value',
            name: 'VERSION')
        string(
            defaultValue: 'pbm',
            description: 'PBM repo name',
            name: 'PBM_REPO')
        choice(
            choices: 'experimental\nlaboratory\ntesting\nexperimental',
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
            name: 'PBM_REPO_TYPE',
            choices: ['testing','release','experimental'],
            description: 'Packages repo for docker images')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PBM source tarball') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-backup-mongodb.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-backup-mongodb.properties
                   cat uploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PBM generic source packages') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            parallel {
                stage('Build PBM generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_src_rpm=1")

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PBM generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_src_deb=1")

                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PBM RPMs/DEBs/Binary tarballs') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            parallel {
                stage('Oracle Linux 8(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8(aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                /* stage('Oracle Linux 10(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                } */ // Commented out in scope of PKG-1083
                /* stage('Oracle Linux 10(aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                } */ // Commented out in scope of PKG-1083
                stage('Amazon Linux 2023(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023(aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy 22.04(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy 22.04(aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble 24.04(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble 24.04(aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Oraclelinux 8 tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_tarball=1")

                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
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
                sync2ProdAutoBuild(params.CLOUD, PBM_REPO, COMPONENT)
            }
        }
        stage('Push Tarballs to TESTING download area') {
            when {
                expression { return params.BUILD_PACKAGES == 'true' }
            }
            steps {
                uploadTarballToDownloadsTesting(params.CLOUD, "pbm", "${VERSION}")
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
                    echo "====> Build docker containers"
                    cleanUpWS()
                    sh '''
                        sleep 1200
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
                        cd percona-docker/percona-backup-mongodb
                        sed -i "s/ENV PBM_VERSION.*/ENV PBM_VERSION ${VERSION}-${RPM_RELEASE}/g" Dockerfile
                        sed -i "s/ENV PBM_REPO_CH.*/ENV PBM_REPO_CH ${PBM_REPO_TYPE}/g" Dockerfile
                        sudo docker build --no-cache --platform "linux/amd64" -t percona-backup-mongodb-amd64 -f Dockerfile .
                        sudo docker tag percona-backup-mongodb-amd64 perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64

                        sed -i "s/ENV PBM_VERSION.*/ENV PBM_VERSION ${VERSION}-${RPM_RELEASE}/g" Dockerfile.aarch64
                        sed -i "s/ENV PBM_REPO_CH.*/ENV PBM_REPO_CH ${PBM_REPO_TYPE}/g" Dockerfile.aarch64
                        sudo docker build --no-cache --platform "linux/arm64" -t percona-backup-mongodb-arm64 -f Dockerfile.aarch64 .
                        sudo docker tag percona-backup-mongodb-arm64 perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                        sudo docker images
                    '''
                    withCredentials([
                        usernamePassword(credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS',
                        usernameVariable: 'USER'
                        )]) {
                        sh '''
                            echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                            sudo docker push perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64
                            sudo docker push perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                            PBM_MAJOR_VERSION=$(echo $VERSION | cut -d'.' -f1)
                            PBM_MINOR_VERSION=$(echo $VERSION | cut -d'.' -f2)
                            PBM_PATCH_VERSION=$(echo $VERSION | cut -d'.' -f3)
                            sudo docker manifest create --amend perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION} \
                                perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 \
                                perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                            sudo docker manifest annotate perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION} perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION} perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 --os linux --arch amd64
                            sudo docker manifest inspect perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION}

                            sudo docker manifest create --amend perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION} \
                                perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 \
                                perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                            sudo docker manifest annotate perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION} perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION} perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 --os linux --arch amd64
                            sudo docker manifest inspect perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}

                            sudo docker manifest create --amend perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION} \
                                perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 \
                                perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                            sudo docker manifest annotate perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION} perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION} perconalab/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 --os linux --arch amd64
                            sudo docker manifest inspect perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}

                            sudo docker manifest push perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION}
                            sudo docker manifest push perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}
                            sudo docker manifest push perconalab/percona-backup-mongodb:${PBM_MAJOR_VERSION}
                        '''
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
                    echo "====> Build docker containers"
                    cleanUpWS()
                    sh '''
                        sleep 1200
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
                        cd percona-docker/percona-backup-mongodb
                        sed -i "s/ENV PBM_VERSION.*/ENV PBM_VERSION ${VERSION}-${RPM_RELEASE}/g" Dockerfile
                        sed -i "s/ENV PBM_REPO_CH.*/ENV PBM_REPO_CH ${PBM_REPO_TYPE}/g" Dockerfile
                        sudo docker build --no-cache --platform "linux/amd64" -t percona-backup-mongodb-amd64 -f Dockerfile .
                        sudo docker tag percona-backup-mongodb-amd64 percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64

                        sed -i "s/ENV PBM_VERSION.*/ENV PBM_VERSION ${VERSION}-${RPM_RELEASE}/g" Dockerfile.aarch64
                        sed -i "s/ENV PBM_REPO_CH.*/ENV PBM_REPO_CH ${PBM_REPO_TYPE}/g" Dockerfile.aarch64
                        sudo docker build --no-cache --platform "linux/arm64" -t percona-backup-mongodb-arm64 -f Dockerfile.aarch64 .
                        sudo docker tag percona-backup-mongodb-arm64 percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                        sudo docker images
                    '''
                    withCredentials([
                        usernamePassword(credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS',
                        usernameVariable: 'USER'
                        )]) {
                        sh '''
                            echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                            sudo docker push percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64
                            sudo docker push percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                            PBM_MAJOR_VERSION=$(echo $VERSION | cut -d'.' -f1)
                            PBM_MINOR_VERSION=$(echo $VERSION | cut -d'.' -f2)
                            PBM_PATCH_VERSION=$(echo $VERSION | cut -d'.' -f3)
                            sudo docker manifest create --amend percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION} \
                                percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 \
                                percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                            sudo docker manifest annotate percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION} percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION} percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 --os linux --arch amd64
                            sudo docker manifest inspect percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION}

                            sudo docker manifest create --amend percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION} \
                                percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 \
                                percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                            sudo docker manifest annotate percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION} percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION} percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 --os linux --arch amd64
                            sudo docker manifest inspect percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}

                            sudo docker manifest create --amend percona/percona-backup-mongodb:${PBM_MAJOR_VERSION} \
                                percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 \
                                percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64
                            sudo docker manifest annotate percona/percona-backup-mongodb:${PBM_MAJOR_VERSION} percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate percona/percona-backup-mongodb:${PBM_MAJOR_VERSION} percona/percona-backup-mongodb:${VERSION}-${RPM_RELEASE}-amd64 --os linux --arch amd64
                            sudo docker manifest inspect percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}

                            sudo docker manifest push percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}.${PBM_PATCH_VERSION}
                            sudo docker manifest push percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}.${PBM_MINOR_VERSION}
                            sudo docker manifest push percona/percona-backup-mongodb:${PBM_MAJOR_VERSION}
                        '''
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
