/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])
void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/percona-packaging/scripts/pg_stat_monitor_builder.sh -O builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./builder.sh --builddir=\${build_dir}/test --branch=${BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}"
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
            defaultValue: '2.0.0',
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
            choices: ['11', '12', '13', '14', '15', '16']
        )
        string(
            defaultValue: 'ppg-16.1',
            description: 'PPG repo name',
            name: 'PPG_REPO'
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
               label 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("centos:7", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/pg-stat-monitor.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pg-stat-monitor.properties
                   cat uploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        } //stage
        stage('Build pg_stat_monitor generic source packages') {
            parallel {
                stage('Source rpm') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } //stage
        stage('Build pg_stat_monitor RPMs') {
            parallel {
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                } //stage
            } //parallel
        } //stage
        stage('Sign packages') {
            steps {
                signRPM()
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
              slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for PG${PG_RELEASE}, repo branch: ${BRANCH} - [${BUILD_URL}]")
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
