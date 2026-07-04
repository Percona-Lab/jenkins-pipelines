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
            defaultValue: 'https://github.com/percona/percona-server.git',
            description: 'URL for percona-server repository',
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
              aws s3 ls s3://percona-jenkins-artifactory/percona-server/branch_commit_id_8.4.properties || EC=\$?

              if [ \${EC} = 1 ]; then
                LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} 8.4 | tail -1)
                BRANCH_NAME="8.4"
                COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)

                echo "BRANCH_NAME=\${BRANCH_NAME}" > branch_commit_id_8.4.properties
                echo "COMMIT_ID=\${COMMIT_ID}" >> branch_commit_id_8.4.properties

                aws s3 cp branch_commit_id_8.4.properties s3://percona-jenkins-artifactory/percona-server/
                echo "START_NEW_BUILD=NO" > startBuild
              else
                aws s3 cp s3://percona-jenkins-artifactory/percona-server/branch_commit_id_8.4.properties .
                source ./branch_commit_id_8.4.properties
                cat ./branch_commit_id_8.4.properties

                LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} 8.4 | tail -1)
                LATEST_BRANCH_NAME="8.4"
                LATEST_COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)
                VERSION=\$(curl -s "https://raw.githubusercontent.com/percona/percona-server/\${LATEST_BRANCH_NAME}/MYSQL_VERSION" | \
                    awk -F= '
                       /^MYSQL_VERSION_MAJOR/  { major=\$2 }
                       /^MYSQL_VERSION_MINOR/  { minor=\$2 }
                       /^MYSQL_VERSION_PATCH/  { patch=\$2 }
                       /^MYSQL_VERSION_EXTRA/{ extra=\$2 }
                       END { printf "%s.%s.%s%s\\n", major, minor, patch, extra }
                    ')

                if [ "x\${COMMIT_ID}" != "x\${LATEST_COMMIT_ID}" ] || [ "x\${BRANCH_NAME}" != "x\${LATEST_BRANCH_NAME}" ]; then
                  echo "START_NEW_BUILD=YES" > startBuild
                else
                  echo "START_NEW_BUILD=NO" > startBuild
                fi

                echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id_8.4.properties
                echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id_8.4.properties
                echo "VERSION=\${VERSION}" >> branch_commit_id_8.4.properties
                aws s3 cp branch_commit_id_8.4.properties s3://percona-jenkins-artifactory/percona-server/
              fi
            """
          }
          script {
               START_NEW_BUILD = sh(returnStdout: true, script: "source ./startBuild; echo \${START_NEW_BUILD}").trim()
               BRANCH_NAME = sh(returnStdout: true, script: "source ./branch_commit_id_8.4.properties; echo \${BRANCH_NAME}").trim()
               COMMIT_ID = sh(returnStdout: true, script: "source ./branch_commit_id_8.4.properties; echo \${COMMIT_ID}").trim()
               VERSION = sh(returnStdout: true, script: "source ./branch_commit_id_8.4.properties; echo \${BRANCH_NAME} | cut -d - -f 2-3 ").trim()
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
                        echo "✅ ${START_NEW_BUILD}: build required"
                    """
                }
                script {
                    def emojis = ['🚀', '🌟', '💫', '🔥', '⚡', '🎯', '🏆', '✨', '🎲', '🌈', '🦄', '🍀', '🎉', '🔮', '🎸', '🦋', '🌊', '🎪', '🏄', '🎭', '🌺', '🦁', '🐉', '🎨', '🌙', '⭐']
                    def randomEmoji = emojis[new Random().nextInt(emojis.size())]
                    slackNotify("#mysql_operators", "#00FF00", "${randomEmoji} [${JOB_NAME}]: new changes for branch ${BRANCH_NAME}[commit id: ${COMMIT_ID}] were detected, build will be started soon")
                }
                build job: 'ps8.0-autobuild-RELEASE', parameters: [string(name: 'CLOUD', value: 'AWS'), string(name: 'BRANCH', value: BRANCH_NAME), string(name: 'COMPONENT', value: 'experimental'), string(name: 'SLACKNOTIFY', value: '#releases-ci'), string(name: 'BUILD_STAGES', value: 'Oracle Linux 9,Oracle Linux 9 ARM')]

            }
        }
        stage('Build skipped') {
            when {
                expression { START_NEW_BUILD == 'NO' }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        echo "💤 no build required"
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
