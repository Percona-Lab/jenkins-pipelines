library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/postgres.git',
            description: 'URL for pg repository',
            name: 'GIT_REPO')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Get release branches') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        EC=0
                        aws s3 ls s3://percona-jenkins-artifactory/pg17/branch_commit_id_17.properties || EC=\$?

            if [ \${EC} = 1 ]; then
              LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release-17\\* | tail -1)
              BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
              COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)

              echo "BRANCH_NAME=\${BRANCH_NAME}" > branch_commit_id_17.properties
              echo "COMMIT_ID=\${COMMIT_ID}" >> branch_commit_id_17.properties

              aws s3 cp branch_commit_id_17.properties s3://percona-jenkins-artifactory/pg17/
                          echo "START_NEW_BUILD=NO" > startBuild
            else
                          aws s3 cp s3://percona-jenkins-artifactory/pg17/branch_commit_id_17.properties .
              source ./branch_commit_id_17.properties
              cat ./branch_commit_id_17.properties

              LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release-17\\* | tail -1)
              LATEST_BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
              LATEST_COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)
              VERSION=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d - -f 2-3)

              if [ "x\${COMMIT_ID}" != "x\${LATEST_COMMIT_ID}" ] || [ "x\${BRANCH_NAME}" != "x\${LATEST_BRANCH_NAME}" ]; then
                echo "START_NEW_BUILD=YES" > startBuild
              else
                echo "START_NEW_BUILD=NO" > startBuild
              fi

              echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id_17.properties
              echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id_17.properties
              echo "VERSION=\${VERSION}" >> branch_commit_id_17.properties
                          aws s3 cp branch_commit_id_17.properties s3://percona-jenkins-artifactory/pg17/
                        fi
                    """
                }
                script {
                    START_NEW_BUILD = sh(returnStdout: true, script: "source ./startBuild; echo \${START_NEW_BUILD}").trim()
                    BRANCH_NAME = sh(returnStdout: true, script: "source ./branch_commit_id_17.properties; echo \${BRANCH_NAME}").trim()
                    COMMIT_ID = sh(returnStdout: true, script: "source ./branch_commit_id_17.properties; echo \${COMMIT_ID}").trim()
                    VERSION = sh(returnStdout: true, script: "source ./branch_commit_id_17.properties; echo \${BRANCH_NAME} | cut -d - -f 2-3 ").trim()
                }

            }

        }
        stage('Build packages needed') {
            when {
                expression { START_NEW_BUILD == 'YES' }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        echo ${START_NEW_BUILD}: build required
                    """
                }
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: new changes for branch ${BRANCH_NAME}[commit id: ${COMMIT_ID}] were detected, build will be started soon")
                build job: 'hetzner-postgresql-server-17_nightly'
                build job: 'hetzner-pg_tarballs-nightly'

            }
        }
        stage('Build skipped') {
            when {
                expression { START_NEW_BUILD == 'NO' }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        echo ${START_NEW_BUILD} build required
                    """
                }
            }
        }
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}