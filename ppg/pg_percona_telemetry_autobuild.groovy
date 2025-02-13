/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
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
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/main/percona-packaging/scripts/pg_percona_telemetry_builder.sh -O ppt_builder.sh || curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/main/percona-packaging/scripts/pg_percona_telemetry_builder.sh -o ppt_builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        set -o xtrace
        cd \${build_dir}
        sudo bash -x ./ppt_builder.sh --builddir=\${build_dir}/test --pg_release=\${PG_RELEASE} --ppg_repo_name=\${PPG_REPO} --install_deps=1
        bash -x ./ppt_builder.sh --builddir=\${build_dir}/test --version=\${VERSION} --branch=\${BRANCH} --repo=\${GIT_REPO} --rpm_release=\${RPM_RELEASE} --deb_release=\${DEB_RELEASE} --pg_release=\${PG_RELEASE} --ppg_repo_name=\${PPG_REPO} "$STAGE_PARAM"
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
            defaultValue: '1.0.0',
            description: 'General version of the product',
            name: 'VERSION'
         )
        string(
            defaultValue: 'https://github.com/Percona-Lab/percona_pg_telemetry.git',
            description: 'percona_pg_telemetry repo',
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
            choices: ['11', '12', '13', '14', '15', '16']
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
                //slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("deb")
                buildStage("ubuntu:focal", "--get_sources=1")
                sh ''' 
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/pg-percona-telemetry.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pg-percona-telemetry.properties
                   cat uploadPath
                   cat awsUploadPath
                ''' 
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/pg-percona-telemetry.properties', name: 'properties'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        } //stage
        stage('Build percona_pg_telemetry generic source packages') {
            parallel {
                stage('Source rpm') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        echo "====> Build percona_pg_telemetry generic source rpm"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_src_rpm=1")

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Source deb') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        echo "====> Build percona_pg_telemetry generic source deb"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_source_deb=1")

                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                } //stage
            }  //parallel
        } //stage
        stage('Build percona_pg_telemetry RPMs') {
            parallel {
                stage('OL 8') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        echo "====> Build percona_pg_telemetry rpm on OL 8 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                } //stage
                stage('OL 9') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        echo "====> Build percona_pg_telemetry rpm on OL 9 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                } //stage
            } //parallel
        } //stage
        stage('Build percona_pg_telemetry DEBs') {
            parallel {
                stage('Ubuntu 20.04') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        echo "====> Build percona_pg_telemetry deb on Ubuntu 20.04 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Ubuntu 22.04') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        echo "====> Build percona_pg_telemetry deb on Ubuntu 22.04 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                } //stage
		stage('Ubuntu Noble(24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
			echo "====> Build percona_pg_telemetry deb on Ubuntu 24.04 PG${PG_RELEASE}"
                        cleanUpWS()
			installCli("deb")
			unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Debian 11') {
                    agent {
                        label 'min-bullseye-x64'
                    }
                    steps {
                        echo "====> Build percona_pg_telemetry deb on Debian 11 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                } //stage
                stage('Debian 12') {
                    agent {
                        label 'min-bookworm-x64'
                    }
                    steps {
                        echo "====> Build percona_pg_telemetry deb on Debian 12 PG${PG_RELEASE}"
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                } //stage
            } //parallel
        } //stage
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild(params.CLOUD, PPG_REPO, COMPONENT)
            }
        }
    } //stages
    post {
        success {
              //slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
              deleteDir()
              echo "Success"
        }
        failure {
              //slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
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
