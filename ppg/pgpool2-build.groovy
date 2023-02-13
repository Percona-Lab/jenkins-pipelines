/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

void installCli(String PLATFORM) {
    sh """ 
        set -o xtrace
        if [ -d aws ]; then
            rm -rf aws
        fi
        if [ ${PLATFORM} = "deb" ]; then
            sudo apt-get update
            sudo apt-get -y install wget curl unzip gnupg2
        elif [ ${PLATFORM} = "rpm" ]; then
            sudo yum -y install wget curl unzip gnupg2
        fi
        curl https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o awscliv2.zip
        unzip awscliv2.zip
        sudo ./aws/install || true
    """ 
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        echo "Docker: $DOCKER_OS, Release: PG$PG_RELEASE, Stage: $STAGE_PARAM"
        set -o xtrace
        mkdir -p test
        wget \$(echo ${GIT_BUILD_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BUILD_BRANCH}/pgpool2/pgpool2_builder.sh -O pg2_builder.sh || curl \$(echo ${GIT_BUILD_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BUILD_BRANCH}/pgpool2/pgpool2_builder.sh -o pg2_builder.sh
        #git clone ${GIT_BUILD_REPO}
        #cd postgres-packaging
        #git checkout ${BUILD_BRANCH}
        #cd ..
        #cp postgres-packaging/pgpool2/pgpool2_builder.sh pg2_builder.sh
        if [ -f /etc/redhat-release ]; then
            sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        else
            DEBIAN_VERSION=\$(lsb_release -sc)
            # sed -e '/.*backports.*/d' /etc/apt/sources.list > sources.list.new
            # sudo mv -vf sources.list.new /etc/apt/sources.list
            if [ \${DEBIAN_VERSION} = bionic ]; then
                wget -O - https://apt.llvm.org/llvm-snapshot.gpg.key | sudo apt-key add -
                echo "deb http://apt.llvm.org/bionic/ llvm-toolchain-bionic-11 main" | sudo tee -a /etc/apt/sources.list
            fi
            if [ \${DEBIAN_VERSION} = buster ]; then
                sudo apt-get -y update --allow-releaseinfo-change || true
            fi
            until sudo apt-get update; do
                sleep 30
                echo "Waiting ..."
            done
            until sudo apt-get -y install gpgv curl clang-11 gnupg; do
                sleep 30
                echo "Waiting ..."
            done
            sudo wget https://repo.percona.com/apt/percona-release_latest.\$(lsb_release -sc)_all.deb
            sudo dpkg -i percona-release_latest.\$(lsb_release -sc)_all.deb
        fi
        sudo percona-release enable ppg-${PG_RELEASE} release
        pwd -P
        export build_dir=\$(pwd -P)
        set -o xtrace
        cd \${build_dir}
        if [ -f ./test/pgpool2.properties ]; then
            . ./test/pgpool2.properties
        fi
        sed -i "s:VERSION=\\"1.0.0:VERSION=\\"$VERSION:" pg2_builder.sh
        sed -i "s:PG_RELEASE=11:PG_RELEASE=\"${PG_RELEASE}\":" pg2_builder.sh

        sudo bash -x ./pg2_builder.sh --builddir=\${build_dir}/test --install_deps=1
        bash -x ./pg2_builder.sh --builddir=\${build_dir}/test --branch=\${BRANCH} --repo=\${GIT_REPO} --pp_branch=\${BUILD_BRANCH} --pp_repo=\${GIT_BUILD_REPO} --rpm_release=\${RPM_RELEASE} --deb_release=\${DEB_RELEASE} --pg_release=\${PG_RELEASE} "$STAGE_PARAM"
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
        label 'micro-amazon'
    }
    parameters {
        string(
            defaultValue: '4.4.2',
            description: 'General version of the product',
            name: 'VERSION'
         )
        string(
            defaultValue: 'https://git.postgresql.org/git/pgpool2.git',
            description: 'pgpool2 repo',
            name: 'GIT_REPO'
         )
        string(
            defaultValue: 'V4_4_STABLE',
            description: 'Branch for pgpool2 repo',
            name: 'BRANCH'
         )
        string(
            defaultValue: 'https://github.com/percona/postgres-packaging.git',
            description: 'Build pgpool2 repo',
            name: 'GIT_BUILD_REPO'
         )
        string(
            defaultValue: 'main',
            description: 'Branch for build repo',
            name: 'BUILD_BRANCH'
         )
        string(
            defaultValue: '1',
            description: 'rpm release number',
            name: 'RPM_RELEASE'
         )
        string(
            defaultValue: '1',
            description: 'deb release number',
            name: 'DEB_RELEASE'
         )
        choice(
            name: 'PG_RELEASE',
            description: 'PPG major version to test',
            choices: ['15', '14', '13', '12', '11']
        )
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
        stage('Download source from github') {
            agent {
               label 'min-focal-x64'
            }
            steps {
                echo '====> Source will be downloaded from github'
            //    slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("deb")
                buildStage("ubuntu:focal", "--get_sources=1")
                sh ''' 
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/pgpool2.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pgpool2.properties
                   cat uploadPath
                   cat awsUploadPath
                ''' 
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/pgpool2.properties', name: 'properties'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        } //stage
        stage('Build pgpool2 generic source packages') {
            parallel {
                stage('Source rpm') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 generic source rpm"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Source deb') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 generic source deb"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_source_deb=1")

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                } //stage
            }  //parallel
        } //stage
        stage('Build pgpool2 RPMs') {
            parallel {
                stage('Centos 7') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 rpm on Centos 7 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Centos 8') {
                    agent {
                        label 'min-centos-8-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 rpm on Centos 8 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:8", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                } //stage
            } //parallel
        } //stage
        stage('Build pgpool2 DEBs') {
            parallel {
                stage('Ubuntu 18.04') {
                    agent {
                        label 'min-bionic-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 deb on Ubuntu 18.04 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Ubuntu 20.04') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 deb on Ubuntu 20.04 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Ubuntu 22.04') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 deb on Ubuntu 22.04 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Debian 10') {
                    agent {
                        label 'min-buster-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 deb on Debian 10 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Debian 11') {
                    agent {
                        label 'min-bullseye-x64'
                    }
                    steps {
                        echo "====> Build pgpool2 deb on Debian 11 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
            } //parallel
        } //stage
        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild("ppg-${PG_RELEASE}", COMPONENT)
            }
        }
    } //stages
    post {
        success {
//              slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
              deleteDir()
              echo "Success"
        }
        failure {
//              slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
              deleteDir()
              echo "Failure"
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
            echo "Always"
        }
    }
}
