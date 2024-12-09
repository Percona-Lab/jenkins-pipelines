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
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/main/percona-packaging/scripts/pg_stat_monitor_builder.sh -O psm_builder.sh || curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/main/percona-packaging/scripts/pg_stat_monitor_builder.sh -o psm_builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        set -o xtrace
        cd \${build_dir}
        sudo bash -x ./psm_builder.sh --builddir=\${build_dir}/test --pg_release=\${PG_RELEASE} --ppg_repo_name=\${PPG_REPO} --install_deps=1
        bash -x ./psm_builder.sh --builddir=\${build_dir}/test --version=\${VERSION} --branch=\${BRANCH} --repo=\${GIT_REPO} --rpm_release=\${RPM_RELEASE} --deb_release=\${DEB_RELEASE} --pg_release=\${PG_RELEASE} --ppg_repo_name=\${PPG_REPO} "$STAGE_PARAM"
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
        string(
            defaultValue: '1.0.0',
            description: 'General version of the product',
            name: 'VERSION'
         )
        string(
            defaultValue: 'https://github.com/percona/pg_stat_monitor.git',
            description: 'pg_stat_monitor repo',
            name: 'GIT_REPO'
         )
        string(
            defaultValue: 'main',
            description: 'Branch for tests',
            name: 'BRANCH'
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
            choices: ['11', '12', '13', '14', '15', '16', '17']
        )
        string(
            defaultValue: 'ppg-16.0',
            description: 'PPG repo name',
            name: 'PPG_REPO')
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
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("deb")
                buildStage("ubuntu:focal", "--get_sources=1")
                sh ''' 
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/pg-stat-monitor.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pg-stat-monitor.properties
                   cat uploadPath
                   cat awsUploadPath
                ''' 
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/pg-stat-monitor.properties', name: 'properties'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        } //stage
        stage('Build pg_stat_monitor generic source packages') {
            parallel {
                stage('Source rpm') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        echo "====> Build pg_stat_monitor generic source rpm"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Source deb') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        echo "====> Build pg_stat_monitor generic source deb"
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
        stage('Build pg_stat_monitor RPMs') {
            parallel {
                stage('OL 8') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        echo "====> Build pg_stat_monitor rpm on OL 8 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                } //stage
                stage('OL 9') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        echo "====> Build pg_stat_monitor rpm on OL 9 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                } //stage
            } //parallel
        } //stage
        stage('Build pg_stat_monitor DEBs') {
            parallel {
                stage('Ubuntu 20.04') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        echo "====> Build pg_stat_monitor deb on Ubuntu 20.04 PG${PG_RELEASE}"
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
                        echo "====> Build pg_stat_monitor deb on Ubuntu 22.04 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Ubuntu 24.04') {
                    agent {
                        label 'min-noble-x64'
                    }
                    steps {
                        echo "====> Build pg_stat_monitor deb on Ubuntu 24.04 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Debian 11') {
                    agent {
                        label 'min-bullseye-x64'
                    }
                    steps {
                        echo "====> Build pg_stat_monitor deb on Debian 11 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Debian 12') {
                    agent {
                        label 'min-bookworm-x64'
                    }
                    steps {
                        echo "====> Build pg_stat_monitor deb on Debian 12 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                } //stage
            } //parallel
        } //stage
        stage('Build docker container') {
            agent {
                label 'min-focal-x64'
            }
            steps {
                echo "====> Build docker container on Ubuntu 20.04 PG${PG_RELEASE}"
                cleanUpWS()
                installCli("deb")
                unstash 'properties'
                popArtifactFolder("rpm/", AWS_STASH_PATH)
                sh ''' 
                    sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                    sudo apt-get install -y docker.io
                    sudo systemctl status docker
                    git clone https://github.com/percona/percona-docker
                    cp rpm/percona-pg_stat_monitor${PG_RELEASE}-${VERSION}-${RPM_RELEASE}.el8.x86_64.rpm percona-docker/percona-distribution-postgresql-${PG_RELEASE}/
                    cd percona-docker/percona-distribution-postgresql-${PG_RELEASE}
                    sed -i 's/mirror/vault/g' Dockerfile
                    sed -i '/percona-postgresql-common/d' Dockerfile
                    sed -i "s/ppg-${PG_RELEASE}.* */${PPG_REPO} testing/g" Dockerfile
                    MAJ_VER=\$(echo ${PPG_REPO} | cut -f2 -d'-' | cut -f1 -d'.')
                    echo \$MAJ_VER
                    MIN_VER=\$(echo ${PPG_REPO} | cut -f2 -d'-' | cut -f2 -d'.')
                    echo \$MIN_VER

                    sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile
                    sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile
                    sed -E "s/16.4/\$MAJ_VER.\${MIN_VER}/g" -i Dockerfile
                    sed -i 's/-\${FULL_PERCONA_VERSION}//g' Dockerfile
                    sed -i "s/percona-pg-stat-monitor${PG_RELEASE}/percona-postgresql-common; rpm -i percona-pg_stat_monitor${PG_RELEASE}-${VERSION}-${RPM_RELEASE}.el8.x86_64.rpm/g" Dockerfile
                    sed -i "11 a COPY percona-pg_stat_monitor${PG_RELEASE}-${VERSION}-${RPM_RELEASE}.el8.x86_64.rpm percona-pg_stat_monitor${PG_RELEASE}-${VERSION}-${RPM_RELEASE}.el8.x86_64.rpm" Dockerfile
                    cat -n Dockerfile
                    sudo docker build -t perconalab/percona-distribution-postgresql:${PG_RELEASE}-dev .
                    sudo docker images
                '''
                withCredentials([
                    usernamePassword(credentialsId: 'hub.docker.com',
                    passwordVariable: 'PASS',
                    usernameVariable: 'USER'
                    )]) {
                    sh '''
                        echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                        sudo docker push perconalab/percona-distribution-postgresql:${PG_RELEASE}-dev
                    '''
                }
            }
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
                sync2ProdAutoBuild(PPG_REPO, COMPONENT)
            }
        }
    } //stages
    post {
        success {
              slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
              deleteDir()
              echo "Success"
        }
        failure {
              slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
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
