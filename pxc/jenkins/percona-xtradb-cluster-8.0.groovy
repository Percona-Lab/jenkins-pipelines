library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir -p test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/build-ps/pxc_builder.sh -O pxc_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} --bin_release=${BIN_RELEASE} ${STAGE_PARAM}"
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
        label 'docker-32gb'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster.git',
            description: 'URL for percona-xtradb-cluster repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '8.0',
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
            defaultValue: '1',
            description: 'BIN release value',
            name: 'BIN_RELEASE')
        string(
            defaultValue: 'pxc-80',
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
                slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("centos:7", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "DEST=UPLOAD" test/pxc-80.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pxc-80.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
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
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_src_rpm=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
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
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:xenial", "--build_source_deb=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
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
                        unstash 'pxc-80.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_rpm=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 8') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:8", "--build_rpm=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Bionic(18.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_deb=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Buster(10)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_deb=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_tarball=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Centos 7 debug tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_tarball=1 --debug=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("debug/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 9 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_tarball=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_tarball=1")

                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
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
                sync2ProdAutoBuild(PXC_REPO, COMPONENT)
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting("pxc", "${GIT_BRANCH}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Build docker containers') {
            agent {
                label 'min-bionic-x64'
            }
            steps {
                echo "====> Build docker containers"
                cleanUpWS()
                sh '''
                   sleep 900
                '''
                unstash 'pxc-80.properties'
                sh '''
                    PXC_RELEASE=$(echo ${GIT_BRANCH} | sed 's/release-//g')
                    sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                    sudo apt-get install -y docker.io
                    sudo systemctl status docker
                    sudo apt-get install -y qemu binfmt-support qemu-user-static
                    sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                    curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                    source MYSQL_VERSION
                    git clone https://github.com/percona/percona-docker

                    cd percona-docker/percona-xtradb-cluster-8.0
                    sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${PXC_RELEASE}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                    sed -i "s/ENV PXC_REPO .*/ENV PXC_REPO testing/g" Dockerfile
                    sudo docker build -t perconalab/percona-xtradb-cluster:${PXC_RELEASE}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE} .
                    sudo docker build --build-arg DEBUG=1 -t perconalab/percona-xtradb-cluster:${PXC_RELEASE}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug .

                    cd ../percona-xtradb-cluster-8.0-backup
                    sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${PXC_RELEASE}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                    sed -i "s/ENV PXC_REPO .*/ENV PXC_REPO testing/g" Dockerfile
                    sudo docker build -t perconalab/percona-xtradb-cluster-operator:${PXC_RELEASE}-pxc8.0-backup .

                    sudo docker images
                 '''
                 withCredentials([
                     usernamePassword(credentialsId: 'hub.docker.com',
                     passwordVariable: 'PASS',
                     usernameVariable: 'USER'
                     )]) {
                 sh '''
                     echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                     PXC_RELEASE=$(echo ${GIT_BRANCH} | sed 's/release-//g')
                     curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                     source MYSQL_VERSION
                     sudo docker push perconalab/percona-xtradb-cluster:${PXC_RELEASE}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                     sudo docker push perconalab/percona-xtradb-cluster:${PXC_RELEASE}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug
                     sudo docker push perconalab/percona-xtradb-cluster-operator:${PXC_RELEASE}-pxc8.0-backup
                 '''
                 }
            }
        }
    }
    post {
        success {
            slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        failure {
            slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
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
