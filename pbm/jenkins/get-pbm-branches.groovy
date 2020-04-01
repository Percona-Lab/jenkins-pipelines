library changelog: false, identifier: 'lib@pbmjen', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/vorsel/jenkins-pipelines.git'
]) _

void checkBranches(String GIT_REPO) {
    sh """
        set -o xtrace
        mkdir test
        pwd -P
        ls -laR
        git -c 'versionsort.suffix=-' ls-remote --tags --sort='v:refname' $GIT_REPO
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/vorsel/percona-backup-mongodb.git',
            description: 'URL for percona-mongodb-backup repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-mongodb-backup repository',
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
            defaultValue: '1.3.0',
            description: 'VERSION value',
            name: 'VERSION')
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
                        pwd
                        EC=0
                        aws s3 ls s3://percona-jenkins-artifactory/percona-backup-mongodb/branch_commit_id.properties || EC=\$?

                        ls -la .

			if [ \${EC} = 1 ]; then
			  LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release\\* | tail -1)
			  BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
			  COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)
			  echo "BRANCH_NAME=\${BRANCH_NAME}" > branch_commit_id.properties
			  echo "COMMIT_ID=\${COMMIT_ID}" >> branch_commit_id.properties
			  aws s3 cp branch_commit_id.properties s3://percona-jenkins-artifactory/percona-backup-mongodb/
			else
                          aws s3 cp s3://percona-jenkins-artifactory/percona-backup-mongodb/branch_commit_id.properties .
			  source branch_commit_id.properties

			  LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release\\* | tail -1)
			  LATEST_BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
			  LATEST_COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)

			  if [ "x\${COMMIT_ID}" != "x\${LATEST_COMMIT_ID}" ] || [ "x\${BRANCH_NAME}" != "x\${LATEST_BRANCH_NAME}" ]; then
			    echo "START_NEW_BUILD=YES" > startBuild
			  else
			    echo "START_NEW_BUILD=NO" > startBuild
			  fi

			  echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id.properties
			  echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id.properties
                          aws s3 cp branch_commit_id.properties s3://percona-jenkins-artifactory/percona-backup-mongodb/
                        fi

			ls -la .

                    """
                }
                script {
                    START_NEW_BUILD = sh(returnStdout: true, script: "source startBuild; echo \${START_NEW_BUILD}").trim()
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
                build job: 'pbm-test-RELEASE'
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
