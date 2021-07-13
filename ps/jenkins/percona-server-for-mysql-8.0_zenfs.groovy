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
        mkdir test
        wget https://raw.githubusercontent.com/EvgeniyPatlan/percona-server/dev/PS-7757-8.0-zenfs/build-ps/percona-server-8.0_builder.sh -O ps_builder.sh || https://raw.githubusercontent.com/EvgeniyPatlan/percona-server/dev/PS-7757-8.0-zenfs/build-ps/percona-server-8.0_builder.sh -o ps_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        set -o xtrace
        cd \${build_dir}
        sudo apt-get install -y chrpath ghostscript
        sudo bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
        bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} --with_zenfs=1 ${STAGE_PARAM}
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
        string(defaultValue: 'https://github.com/EvgeniyPatlan/percona-server.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'dev/PS-7757-8.0-zenfs', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '0', description: 'PerconaFT repository', name: 'PERCONAFT_REPO')
        string(defaultValue: 'Percona-Server-8.0.23-14', description: 'Tag/Branch for PerconaFT repository', name: 'PERCONAFT_BRANCH')
        string(defaultValue: '0', description: 'TokuBackup repository', name: 'TOKUBACKUP_REPO')
        string(defaultValue: 'Percona-Server-8.0.23-14', description: 'Tag/Branch for TokuBackup repository', name: 'TOKUBACKUP_BRANCH')
        string(defaultValue: '2', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '2', description: 'DEB version', name: 'DEB_RELEASE')
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
               label 'min-xenial-x64'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH}")
                cleanUpWS()
                installCli("deb")
                buildStage("ubuntu:xenial", "--get_sources=1")
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
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PS generic source packages') {
            parallel {
                stage('Build PS generic source deb') {
                    agent {
                        label 'min-xenial-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:xenial", "--build_source_deb=1")

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PS DEBs/Binary tarballs') {
            parallel {
                stage('Ubuntu Hirsute(21.04)') {
                    agent {
                        label 'min-hirsute-x64-zenfs'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:hirsute", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Binary tarball') {
                    agent {
                        label 'min-hirsute-x64-zenfs'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:hirsute", "--build_tarball=1")

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
            }
        }

        stage('Sign packages') {
            steps {
                signDEB()
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild('ps-80', COMPONENT)
            }
        }

    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH}")
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH}")
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
