String OWNER = ''
String OWNER_SLACK = ''

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: '',
            description: 'AMI Instance ID',
            name: 'AMI_ID')
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30', daysToKeepStr: '60'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                script {
                    wrap([$class: 'BuildUser']) {
                        OWNER = (env.BUILD_USER_EMAIL ?: '').split('@')[0] ?: env.BUILD_USER_ID
                        OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: env.BUILD_USER_EMAIL, tokenCredentialId: 'JenkinsCI-SlackBot-v2')
                    }

                    echo """
                        AMI Instance ID: ${AMI_ID}
                        OWNER:          ${OWNER}
                    """

                    if (params.NOTIFY == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        if (OWNER_SLACK) {
                            slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        }
                    }
                }
            }
        }
        stage('Run VM with PMM server') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID',  credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                   sh """
                        if [ -n "${AMI_ID}" ]; then
                            aws ec2 --region us-east-1 terminate-instances --instance-ids "${AMI_ID}"
                        fi
                    """
                }
            }
        }
    }
    post {
        success {
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build ${BUILD_URL} finished, owner: @${OWNER}, Instance ID: ${AMI_ID}"
                    if (OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build ${BUILD_URL} finished, Instance ID: ${AMI_ID}"
                    }
                }
            }
        }
        failure {
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${BUILD_URL} failed, owner: @${OWNER}"
                    if (OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build ${BUILD_URL} failed"
                    }
                }
            }
        }
    }
}
