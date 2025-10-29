library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/config/scripts/pt_builder.sh -O pt_builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            sed -i "s:archive.ubuntu.com:mirrors.edge.kernel.org:g" /etc/apt/sources.list
            cd \${build_dir}
            bash -x ./pt_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./pt_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --version=${VERSION} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}"
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
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/percona-toolkit.git',
            description: 'URL for percona-toolkit repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'release-3.7.0-1',
            description: 'Tag/Branch for percona toolkit repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '3.7.0',
            description: 'Percona Toolkit release value',
            name: 'VERSION')
        string(
            defaultValue: '1.1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1.1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        string(
            defaultValue: 'pt',
            description: 'PT repo name',
            name: 'PT_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Create Percona Toolkit source tarball') {
            steps {
                // slackNotify("", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("centos:7", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-toolkit.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-toolkit.properties
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
        stage('Build Percona Toolkit generic source packages') {
            parallel {
                stage('Build Percona Toolkit generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_src_rpm=1")

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build Percona Toolkit generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_src_deb=1")

                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build Percona Toolkit RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")
            
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                } 
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
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
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Xenial(16.04) tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:xenial", "--build_tarball=1")

                        pushArtifactFolder(params.CLOUD, "test/tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS(params.CLOUD, "test/tarball/", AWS_STASH_PATH, 'binary')
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
                sync2ProdAutoBuild(params.CLOUD, PT_REPO, COMPONENT)
            }
        }
        stage('Build docker containers') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                script {
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
                        cd percona-docker/percona-toolkit
                        sed -i "s/ENV PT_VERSION.*/ENV PT_VERSION ${VERSION}-${RPM_RELEASE}/g" Dockerfile
                        sed -i "s/pt release/pt ${COMPONENT}/g" Dockerfile
                        sudo docker build --no-cache --platform "linux/amd64" -t perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE} .

                        sudo docker build --no-cache -t perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-aarch64 --platform="linux/arm64" -f Dockerfile .

                        sudo docker images
                    '''
                    withCredentials([
                        usernamePassword(credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS',
                        usernameVariable: 'USER'
                        )]) {
                        sh '''
                            echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                            sudo docker push perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}
                            sudo docker push perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-aarch64

                            PT_MAJOR_VERSION=$(echo $VERSION | cut -d'.' -f1)
                            PT_MINOR_VERSION=$(echo $VERSION | cut -d'.' -f2)
                            PT_PATCH_VERSION=$(echo $VERSION | cut -d'.' -f3)
                            sudo docker manifest create perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION}.${PT_PATCH_VERSION} \
                                perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE} \
                                perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-aarch64
                            sudo docker manifest annotate perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION}.${PT_PATCH_VERSION} perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-aarch64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION}.${PT_PATCH_VERSION} perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE} --os linux --arch amd64
                            sudo docker manifest inspect perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION}.${PT_PATCH_VERSION}

                           sudo docker manifest create perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION} \
                                perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE} \
                                perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-aarch64
                            sudo docker manifest annotate perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION} perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-aarch64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION} perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE} --os linux --arch amd64
                            sudo docker manifest inspect perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION}

                            sudo docker manifest create perconalab/percona-toolkit:${PT_MAJOR_VERSION} \
                                perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE} \
                                perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-aarch64
                            sudo docker manifest annotate perconalab/percona-toolkit:${PT_MAJOR_VERSION} perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-aarch64 --os linux --arch arm64 --variant v8
                            sudo docker manifest annotate perconalab/percona-toolkit:${PT_MAJOR_VERSION} perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE} --os linux --arch amd64
                            sudo docker manifest inspect perconalab/percona-toolkit:${PT_MAJOR_VERSION}

                            sudo docker manifest push perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION}.${PT_PATCH_VERSION}
                            sudo docker manifest push perconalab/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION}
                            sudo docker manifest push perconalab/percona-toolkit:${PT_MAJOR_VERSION}
                        '''
                    }
                }
            }
        }
    }
    post {
        success {
            // slackNotify("", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}"
            }
            deleteDir()
        }
        failure {
           // slackNotify("", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
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
