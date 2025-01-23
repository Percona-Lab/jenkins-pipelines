library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void installCli(String PLATFORM) {
    sh """
        set -o xtrace
        if [ -d aws ]; then
            rm -rf aws
        fi
        if [ ${PLATFORM} = "deb" ]; then
            sudo apt-get update
            sudo apt-get -y install wget curl unzip
        elif [ ${PLATFORM} = "rpm" ]; then
            sudo yum -y install wget curl unzip
        fi
        curl https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o awscliv2.zip
        unzip awscliv2.zip
        sudo ./aws/install || true
    """
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        sh """
            set -o xtrace
            mkdir -p test
            wget --header="Authorization: token ${TOKEN}" --header="Accept: application/vnd.github.v3.raw" -O pxc_57_builder.sh \$(echo ${GIT_REPO} | sed -re 's|github.com|api.github.com/repos|; s|\\.git\$||')/contents/build-ps/pxc_57_builder.sh?ref=${GIT_BRANCH}
            sed -i "s|git clone --depth 1 --branch \\\$BRANCH \\\"\\\$REPO\\\"|git clone \$(echo ${GIT_REPO}| sed -re 's|github.com|${TOKEN}@github.com|') percona-xtradb-cluster|g" pxc_57_builder.sh
            grep "git clone" pxc_57_builder.sh
            export build_dir=\$(pwd -P)
            docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                set -o xtrace
                cd \${build_dir}
                bash -x ./pxc_57_builder.sh --builddir=\${build_dir}/test --install_deps=1
                bash -x ./pxc_57_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} --xb_version=${XB_VERSION} --bin_release=${BIN_RELEASE} ${STAGE_PARAM}"
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
        label 'docker-32gb'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-private.git',
            description: 'URL for percona-xtradb-cluster repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '5.7',
            description: 'Tag/Branch for percona-xtradb-cluster repository',
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
            defaultValue: '2.4.29',
            description: 'XB Version value',
            name: 'XB_VERSION')
        string(
            defaultValue: '1',
            description: 'BIN release value',
            name: 'BIN_RELEASE')
        string(
            defaultValue: 'pxc-57',
            description: 'PXC repo name',
            name: 'PXC_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases\n#releases-ci',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PXC source tarball') {
            steps {
               // slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("centos:7", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "DEST=UPLOAD" test/pxc-57.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pxc-57.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PXC generic source packages') {
            parallel {
                stage('Build PXC generic source rpm') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_src_rpm=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PXC generic source deb') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:xenial", "--build_source_deb=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PXC RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Centos 7') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_rpm=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 8') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:8", "--build_rpm=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Bionic(18.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_deb=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Buster(10)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_deb=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
/*
                stage('Centos 7 debug tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_tarball=1 --debug=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("debug/", AWS_STASH_PATH)
                    }
                }
*/
                stage('Centos 8 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:8", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 9 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Bionic (18.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal (20.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Buster (10) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye (11) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm (12) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-57.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_tarball=1")

                        stash includes: 'test/pxc-57.properties', name: 'pxc-57.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Upload packages and tarballs from S3') {
            agent {
                label 'min-jammy-x64'
            }
            steps {
                cleanUpWS()
                installCli("deb")
                unstash 'pxc-57.properties'

                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
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
                // sync2ProdAutoBuild(PXC_REPO, COMPONENT)
                sync2PrivateProdAutoBuild("pxc-57-eol", COMPONENT)
            }
        }
        stage('Build docker container') {
            agent {
                label 'min-focal-x64'
            }
            steps {
                script {
                    cleanUpWS()
                    unstash 'uploadPath'
                    def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com:${path_to_build}/binary/redhat/9/x86_64/*.rpm /tmp
                            ls -la /tmp
                        """
                    }
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                        sh '''
                            wget --header="Authorization: token ${TOKEN}" --header="Accept: application/vnd.github.v3.raw" -O MYSQL_VERSION \$(echo ${GIT_REPO} | sed -re 's|github.com|api.github.com/repos|; s|\\.git\$||')/contents/MYSQL_VERSION?ref=${GIT_BRANCH}
                            wget --header="Authorization: token ${TOKEN}" --header="Accept: application/vnd.github.v3.raw" -O WSREP_VERSION \$(echo ${GIT_REPO} | sed -re 's|github.com|api.github.com/repos|; s|\\.git\$||')/contents/WSREP_VERSION?ref=${GIT_BRANCH}
                            MYSQL_VERSION_MAJOR=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_MAJOR | awk -F= '{print \$2}')
                            MYSQL_VERSION_MINOR=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print \$2}')
                            MYSQL_VERSION_PATCH=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_PATCH | awk -F= '{print \$2}')
                            WSREP_VERSION_API=\$(cat WSREP_VERSION | grep WSREP_VERSION_API | awk -F= '{print \$2}')
                            WSREP_VERSION_PATCH=\$(cat WSREP_VERSION | grep WSREP_VERSION_PATCH | awk -F= '{print \$2}')

                            PXC_RELEASE=\${MYSQL_VERSION_MAJOR}.\${MYSQL_VERSION_MINOR}.\${MYSQL_VERSION_PATCH}-\${WSREP_VERSION_API}.\${WSREP_VERSION_PATCH}

                            sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                            sudo apt-get install -y docker.io
                            sudo systemctl status docker
                            sudo apt-get install -y qemu binfmt-support qemu-user-static
                            sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                            git clone https://github.com/percona/percona-docker
                            cd percona-docker/percona-xtradb-cluster-5.7
                            mv /tmp/*.rpm .
                            sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${PXC_RELEASE}.${RPM_RELEASE}/g" Dockerfile-pro
                            sed -i "s/ENV PXC_TELEMETRY_VERSION.*/ENV PXC_TELEMETRY_VERSION ${PXC_RELEASE}-${RPM_RELEASE}/g" Dockerfile-pro
                            sudo docker build -t percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE} -f Dockerfile-pro .
                            sudo docker tag percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE} percona/percona-xtradb-cluster:${PXC_RELEASE}
                            sudo docker images
                            sudo docker save -o percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}.docker.tar percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE} percona/percona-xtradb-cluster:${PXC_RELEASE} 
                            sudo useradd admin -g admin
                            sudo chown admin:admin percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}.docker.tar
                            sudo chmod a+r percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}.docker.tar
                            ls -la
                        '''
                    }
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            MYSQL_VERSION_MAJOR=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_MAJOR | awk -F= '{print \$2}')
                            MYSQL_VERSION_MINOR=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print \$2}')
                            MYSQL_VERSION_PATCH=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_PATCH | awk -F= '{print \$2}')
                            WSREP_VERSION_API=\$(cat WSREP_VERSION | grep WSREP_VERSION_API | awk -F= '{print \$2}')
                            WSREP_VERSION_PATCH=\$(cat WSREP_VERSION | grep WSREP_VERSION_PATCH | awk -F= '{print \$2}')

                            export PXC_RELEASE=\${MYSQL_VERSION_MAJOR}.\${MYSQL_VERSION_MINOR}.\${MYSQL_VERSION_PATCH}-\${WSREP_VERSION_API}.\${WSREP_VERSION_PATCH}
                            cd percona-docker/percona-xtradb-cluster-5.7
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} percona-xtradb-cluster-\${PXC_RELEASE}-${RPM_RELEASE}.docker.tar ${USER}@repo.ci.percona.com:${path_to_build}/binary/tarball
                        """
                    }
               }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting("pxc-gated", "${GIT_BRANCH}")
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
           // slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        failure {
            //slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                currentBuild.description = "Built on ${GIT_BRANCH} - packages [${COMPONENT}/${AWS_STASH_PATH}]"
            }
            deleteDir()
        }
    }
}
