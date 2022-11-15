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
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for percona-server-for-mongodb repository',
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
                        aws s3 ls s3://percona-jenkins-artifactory/percona-server-mongodb/branch_commit_id_42.properties || EC=\$?

			if [ \${EC} = 1 ]; then
			  LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release-4.2\\* | tail -1)
			  BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
			  COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)

			  echo "BRANCH_NAME=\${BRANCH_NAME}" > branch_commit_id_42.properties
			  echo "COMMIT_ID=\${COMMIT_ID}" >> branch_commit_id_42.properties

			  aws s3 cp branch_commit_id_42.properties s3://percona-jenkins-artifactory/percona-server-mongodb/
                          echo "START_NEW_BUILD=NO" > startBuild
			else
                          aws s3 cp s3://percona-jenkins-artifactory/percona-server-mongodb/branch_commit_id_42.properties .
			  source branch_commit_id_42.properties

			  LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release-4.2\\* | tail -1)
			  LATEST_BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
			  LATEST_COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)

			  if [ "x\${COMMIT_ID}" != "x\${LATEST_COMMIT_ID}" ] || [ "x\${BRANCH_NAME}" != "x\${LATEST_BRANCH_NAME}" ]; then
			    echo "START_NEW_BUILD=YES" > startBuild
			  else
			    echo "START_NEW_BUILD=NO" > startBuild
			  fi

			  echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id_42.properties
			  echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id_42.properties
                          aws s3 cp branch_commit_id_42.properties s3://percona-jenkins-artifactory/percona-server-mongodb/
                        fi
                    """
                }
                script {
                    START_NEW_BUILD = sh(returnStdout: true, script: "source startBuild; echo \${START_NEW_BUILD}").trim()
                    BRANCH_NAME = sh(returnStdout: true, script: "source branch_commit_id_42.properties; echo \${BRANCH_NAME}").trim()
                    COMMIT_ID = sh(returnStdout: true, script: "source branch_commit_id_42.properties; echo \${COMMIT_ID}").trim()
                    VERSION = sh(returnStdout: true, script: "source branch_commit_id_42.properties; echo \${BRANCH_NAME} | cut -d - -f 2 ").trim()
                    RELEASE = sh(returnStdout: true, script: "source branch_commit_id_42.properties; echo \${BRANCH_NAME} | cut -d - -f 3 ").trim()
                }

            }

        }
        stage('Build needed') {
            when {
                expression { START_NEW_BUILD == 'YES' }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        echo ${START_NEW_BUILD}: build required
                    """
                }
                slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: new changes for branch ${BRANCH_NAME}[commit id: ${COMMIT_ID}] were detected, build will be started soon")
                build job: 'psmdb42-autobuild-RELEASE', parameters: [string(name: 'GIT_BRANCH', value: BRANCH_NAME), string(name: 'PSMDB_VERSION', value: VERSION), string(name: 'PSMDB_RELEASE', value: RELEASE), string(name: 'COMPONENT', value: 'testing')]
                build job: 'psmdb-multijob-testing', propagate: false, wait: false, parameters: [string(name: 'PSMDB_VERSION', value: VERSION), string(name: 'PSMDB_RELEASE', value: RELEASE)]
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
