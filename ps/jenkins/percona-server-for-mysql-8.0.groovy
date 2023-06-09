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

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir -p test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -O ps_builder.sh || curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -o ps_builder.sh
        export build_dir=\$(pwd -P)
        if [ "$DOCKER_OS" = "none" ]; then
            set -o xtrace
            cd \${build_dir}
            if [ -f ./test/percona-server-8.0.properties ]; then
                . ./test/percona-server-8.0.properties
            fi
            sudo bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
            if [${BUILD_TOKUDB_TOKUBACKUP} = "ON" ]; then
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
            else
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
            fi
        else
            docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                set -o xtrace
                cd \${build_dir}
                if [ -f ./test/percona-server-8.0.properties ]; then
                    . ./test/percona-server-8.0.properties
                fi
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
                if [ ${BUILD_TOKUDB_TOKUBACKUP} = \"ON\" ]; then
                    bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                else
                    bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                fi"
        fi
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
        label 'docker'
    }
parameters {
        string(defaultValue: 'https://github.com/percona/percona-server.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'release-8.0.28-19', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version', name: 'DEB_RELEASE')
        choice(
            choices: 'OFF\nON',
            description: 'The TokuDB storage is no longer supported since 8.0.28',
            name: 'BUILD_TOKUDB_TOKUBACKUP')
        string(defaultValue: '0', description: 'PerconaFT repository', name: 'PERCONAFT_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for PerconaFT repository', name: 'PERCONAFT_BRANCH')
        string(defaultValue: '0', description: 'TokuBackup repository', name: 'TOKUBACKUP_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for TokuBackup repository', name: 'TOKUBACKUP_BRANCH')
        choice(
            choices: 'ON\nOFF',
            description: 'Compile with ZenFS support?, only affects Ubuntu Hirsute',
            name: 'ENABLE_ZENFS')
        choice(
            choices: 'laboratory\ntesting\nexperimental\nrelease',
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
        stage('Create PS source tarball') {
            agent {
               label 'min-bionic-x64'
            }
            steps {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("deb")
                buildStage("none", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-8.0.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-8.0.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-8.0.properties', name: 'properties'
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
                        buildStage("none", "--build_src_rpm=1")

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
                        buildStage("none", "--build_source_deb=1")

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
                        buildStage("none", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 8') {
                    agent {
                        label 'min-centos-8-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("none", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 8 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:8", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
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
                        buildStage("none", "--build_rpm=1 --with_zenfs=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
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
                        buildStage("none", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
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
                        buildStage("none", "--build_deb=1 --with_zenfs=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
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
                        buildStage("none", "--build_deb=1 --with_zenfs=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
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
                        buildStage("none", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
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
                        buildStage("none", "--build_deb=1 --with_zenfs=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 binary tarball') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
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
                        buildStage("none", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Centos 8 binary tarball') {
                    agent {
                        label 'min-centos-8-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Centos 8 debug tarball') {
                    agent {
                        label 'min-centos-8-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
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
                        buildStage("none", "--build_tarball=1")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Oracle Linux 9 ZenFS tarball') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--build_tarball=1 --with_zenfs=1")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Bionic(18.04) binary tarball') {
                    agent {
                        label 'min-bionic-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Bionic(18.04) debug tarball') {
                    agent {
                        label 'min-bionic-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--debug=1 --build_tarball=1 ")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Ubuntu Focal(20.04) tarball') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--build_tarball=1")

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--build_tarball=1")

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Ubuntu Jammy(22.04) ZenFS tarball') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("none", "--build_tarball=1 --with_zenfs=1")

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
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
                sync2ProdAutoBuild('ps-80', COMPONENT)
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting("ps", "${BRANCH}")
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
                echo "====> Build docker container"
                cleanUpWS()
                installCli("deb")
                sh '''
                   sleep 900
                '''
                unstash 'properties'
                sh '''
                    PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                    sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                    sudo apt-get install -y docker.io
                    sudo systemctl status docker
                    sudo apt-get install -y qemu binfmt-support qemu-user-static
                    sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                    git clone https://github.com/percona/percona-docker
                    cd percona-docker/percona-server-8.0
                    sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${PS_RELEASE}.${RPM_RELEASE}/g" Dockerfile
                    sed -i "s/ENV PS_REPO .*/ENV PS_REPO testing/g" Dockerfile
                    sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${PS_RELEASE}.${RPM_RELEASE}/g" Dockerfile.aarch64
                    sed -i "s/ENV PS_REPO .*/ENV PS_REPO testing/g" Dockerfile.aarch64
                    sudo docker build -t perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE} .
                    sudo docker build -t perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-aarch64 -f Dockerfile.aarch64 .
                    sudo docker images
                 '''
                 withCredentials([
                     usernamePassword(credentialsId: 'hub.docker.com',
                     passwordVariable: 'PASS',
                     usernameVariable: 'USER'
                     )]) {
                 sh '''
                     echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                     PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                     sudo docker tag perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE} perconalab/percona-server:${PS_RELEASE}
                     sudo docker push perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}
                     sudo docker push perconalab/percona-server:${PS_RELEASE}
                     sudo docker tag perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-aarch64 perconalab/percona-server:${PS_RELEASE}-aarch64
                     sudo docker push perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-aarch64
                     sudo docker push perconalab/percona-server:${PS_RELEASE}-aarch64
                 '''
                 }
                 sh '''
                    PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                    sudo docker manifest create perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-multi \
                         perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE} \
                         perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-aarch64
                    sudo docker manifest annotate perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-multi perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-aarch64 --os linux --arch arm64 --variant v8
                    sudo docker manifest annotate perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-multi perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE} --os linux --arch amd64
                    sudo docker manifest inspect perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-multi
                '''
                 withCredentials([
                    usernamePassword(credentialsId: 'hub.docker.com',
                    passwordVariable: 'PASS',
                    usernameVariable: 'USER'
                    )]) {
                 sh '''
                    PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                    echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                    PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                    sudo docker manifest push perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-multi
                 '''
                 }
           }
       }
    }
    post {
        success {
            slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
            slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: Triggering Builds for Package Testing for ${BRANCH} - [${BUILD_URL}]")
            unstash 'properties'
            script {
                currentBuild.description = "Built on ${BRANCH}; path to packages: ${COMPONENT}/${AWS_STASH_PATH}"
                REVISION = sh(returnStdout: true, script: "grep REVISION test/percona-server-8.0.properties | awk -F '=' '{ print\$2 }'").trim()
                echo "${REVISION} is the commit ID"
                build job: 'package-testing-ps80', propagate: false, wait: false, parameters: [string(name: 'product_to_test', value: 'ps80'),string(name: 'install_repo', value: "testing"),string(name: 'node_to_test', value: "all"),string(name: 'action_to_test', value: "all"),string(name: 'check_warnings', value: "yes"),string(name: 'install_mysql_shell', value: "no"),string(name: 'REVISION', value: "${REVISION}")]
            }
            deleteDir()
        }
        failure {
            slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${BRANCH}"
            }
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
