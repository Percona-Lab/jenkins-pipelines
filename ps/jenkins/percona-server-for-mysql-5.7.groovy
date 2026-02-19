/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
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

void installCli32(String PLATFORM) {
    sh """
        set -o xtrace
        if [ -d aws ]; then
            rm -rf aws
        fi
        if [ ${PLATFORM} = "deb" ]; then
            sudo apt-get update
            sudo apt-get -y install wget curl unzip awscli
        elif [ ${PLATFORM} = "rpm" ]; then
            sudo yum -y install wget curl unzip
        fi
    """
}
void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        sh """
            set -o xtrace
            mkdir -p test
            wget --header="Authorization: token ${TOKEN}" --header="Accept: application/vnd.github.v3.raw" -O ps_builder.sh \$(echo ${GIT_REPO} | sed -re 's|github.com|api.github.com/repos|; s|\\.git\$||')/contents/build-ps/percona-server-5.7_builder.sh?ref=${BRANCH}
            sed -i "s|git clone \\\"\\\$REPO\\\"|git clone \$(echo ${GIT_REPO}| sed -re 's|github.com|${TOKEN}@github.com|') percona-server|g" ps_builder.sh
            grep "git clone" ps_builder.sh
            pwd -P
            export build_dir=\$(pwd -P)
            set -o xtrace
            cd \${build_dir}
            if [ -f ./test/percona-server-5.7.properties ]; then
                . ./test/percona-server-5.7.properties
            fi
            sudo bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
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
        label 'docker'
    }
parameters {
        string(defaultValue: 'https://github.com/percona/percona-server-private.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'release-5.7.44-52', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '0', description: 'PerconaFT repository', name: 'PERCONAFT_REPO')
        string(defaultValue: 'Percona-Server-5.7.32-35', description: 'Tag/Branch for PerconaFT repository', name: 'PERCONAFT_BRANCH')
        string(defaultValue: '0', description: 'TokuBackup repository', name: 'TOKUBACKUP_REPO')
        string(defaultValue: 'Percona-Server-5.7.32-35', description: 'Tag/Branch for TokuBackup repository', name: 'TOKUBACKUP_BRANCH')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version', name: 'DEB_RELEASE')
        choice(
            choices: 'NO\nYES',
            description: 'Set if required to build for noble OS',
            name: 'BUILD_NOBLE')
        choice(
            choices: 'laboratory\ntesting\nexperimental\nrelease',
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
        stage('Create PS source tarball') {
            agent {
               label 'min-bionic-x64'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("deb")
                buildStage("ubuntu:bionic", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-5.7.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-5.7.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-5.7.properties', name: 'properties'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PS generic source packages') {
            parallel {
                stage('Build PS generic source rpm') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PS generic source deb') {
                    agent {
                        label 'min-bionic-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_source_deb=1")

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PS RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Centos 7') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Bionic(18.04)') {
                    agent {
                        label 'min-bionic-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label 'min-noble-x64'
                    }
                    steps {
                        script {
                            if ("${BUILD_NOBLE}" == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("deb")
                                unstash 'properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:noble", "--build_deb=1")

                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Buster(10)') {
                    agent {
                        label 'min-buster-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'min-bullseye-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label 'min-bookworm-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 tarball') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 debug tarball') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 tarball') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 debug tarball') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 tarball') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 debug tarball') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Bionic tarball') {
                    agent {
                        label 'min-bionic-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Bionic debug tarball') {
                    agent {
                        label 'min-bionic-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal tarball') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal debug tarball') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy tarball') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy debug tarball') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Buster tarball') {
                    agent {
                        label 'min-buster-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Buster debug tarball') {
                    agent {
                        label 'min-buster-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye tarball') {
                    agent {
                        label 'min-bullseye-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye debug tarball') {
                    agent {
                        label 'min-bullseye-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm tarball') {
                    agent {
                        label 'min-bookworm-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm debug tarball') {
                    agent {
                        label 'min-bookworm-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
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
                unstash 'properties'

                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
            }
        }
        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Build docker container') {
            agent {
                label 'min-bullseye-x64'
            }
            steps {
                script {
                    cleanUpWS()
                    installCli("deb")
                    unstash 'uploadPath'
                    def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com:${path_to_build}/binary/redhat/8/x86_64/*.rpm /tmp 
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com:${path_to_build}/binary/redhat/9/x86_64/*.rpm /tmp 
                            ls -la /tmp
                        """
                    }
                    sh '''
                        PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                        PS_MAJOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | awk '{print substr($0, 0, 3)}')
                        PS_MAJOR_MINOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | awk '{print substr($0, 0, 7)}' | sed "s/-//g")
                        sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                        sudo apt-get install -y docker.io
                        sudo systemctl status docker
                        sudo apt-get install -y qemu binfmt-support qemu-user-static
                        sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                        git clone https://github.com/percona/percona-docker
                        cd percona-docker/percona-server-5.7
                        mv /tmp/*.rpm .
                        sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${PS_RELEASE}.${RPM_RELEASE}/g" Dockerfile-pro
                        sed -i "s/ENV PS_TELEMETRY_VERSION.*/ENV PS_TELEMETRY_VERSION ${PS_RELEASE}-${RPM_RELEASE}/g" Dockerfile-pro
                        sudo docker build -t percona/percona-server:${PS_RELEASE}.${RPM_RELEASE} --progress plain -f Dockerfile-pro .
                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE} percona/percona-server:${PS_RELEASE}
                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE} percona/percona-server:${PS_MAJOR_RELEASE}
                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE} percona/percona-server:${PS_MAJOR_MINOR_RELEASE}
                        sudo docker images
                        sudo docker save -o percona-server-${PS_RELEASE}-${RPM_RELEASE}.docker.tar percona/percona-server:${PS_RELEASE}.${RPM_RELEASE} percona/percona-server:${PS_RELEASE} percona/percona-server:${PS_MAJOR_RELEASE} percona/percona-server:${PS_MAJOR_MINOR_RELEASE}
                        sudo chown admin:admin percona-server-${PS_RELEASE}-${RPM_RELEASE}.docker.tar
                        sudo chmod a+r percona-server-${PS_RELEASE}-${RPM_RELEASE}.docker.tar
                        ls -la
                    '''
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            cd percona-docker/percona-server-5.7
                            export PS_RELEASE=`echo ${BRANCH} | sed 's/release-//g'`
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} percona-server-\${PS_RELEASE}-${RPM_RELEASE}.docker.tar ${USER}@repo.ci.percona.com:${path_to_build}/binary/tarball
                        """
                    }
               }
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                //sync2ProdAutoBuild('ps-57', COMPONENT)
                sync2PrivateProdAutoBuild("ps-57-eol", COMPONENT)
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting("ps-gated", "${BRANCH}")
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
            slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
            unstash 'properties'
            script {
                currentBuild.description = "Built on ${BRANCH}; path to packages: ${COMPONENT}/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH} - [${BUILD_URL}]")
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
