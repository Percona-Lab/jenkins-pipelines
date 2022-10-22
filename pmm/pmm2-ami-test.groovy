void runAMIStagingStart(AMI_ID, SSH_KEY) {
    amiStagingJob = build job: 'pmm2-ami-staging-start', parameters: [
        string(name: 'AMI_ID', value: AMI_ID),
        string(name: 'SSH_KEY', value: SSH_KEY)
    ]
    env.AMI_INSTANCE_ID = amiStagingJob.buildVariables.INSTANCE_ID
    env.AMI_INSTANCE_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.PMM_URL = "http://admin:admin@${AMI_INSTANCE_IP}"
    env.PMM_UI_URL = "https://${AMI_INSTANCE_IP}"
}

void runAMIStaginStop(INSTANCE_ID) {
    amiStagingStopJob = build job: 'pmm2-ami-staging-stop', parameters: [
        string(name: 'AMI_ID', value: INSTANCE_ID),
    ]
}

String OWNER = ''
String OWNER_SLACK = ''

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'dev-latest',
            description: 'PMM2 Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
            name: 'SSH_KEY')
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
        string(
            defaultValue: '',
            description: 'AMI Image version',
            name: 'AMI_ID')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
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
                        env.VM_NAME = 'pmm-' + OWNER.replaceAll("[^a-zA-Z0-9_.-]", "") + '-' + (new Date()).format("yyyyMMdd.HHmmss") + '-' + env.BUILD_NUMBER
                    }

                    if (params.NOTIFY == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        if (OWNER_SLACK) {
                            slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        }
                    }
                }
            }
        }
        stage('Run PMM2 AMI Instance') {
            steps {
                runAMIStagingStart(params.AMI_ID, params.SSH_KEY)
            }
        }
    }
    post {
        success {
            runAMIStaginStop(env.AMI_INSTANCE_ID)
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${env.AMI_INSTANCE_IP}"
                    if (OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${env.AMI_INSTANCE_IP}"
                    }
                }
            }
        }
        failure {
            runAMIStaginStop(env.AMI_INSTANCE_ID)
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${BUILD_URL} failed"
                    if (OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build ${BUILD_URL} failed"
                    }
                }
            }
        }
    }
}