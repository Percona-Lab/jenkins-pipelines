void runUITests(CLIENT_VERSION, CLIENT_INSTANCE, SERVER_IP, AMI_TEST, AMI_INSTANCE_ID) {
    stagingJob = build job: 'pmm2-ui-tests', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'AMI_TEST', value: AMI_TEST),
        string(name: 'AMI_INSTANCE_ID', value: AMI_INSTANCE_ID)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://admin:admin@${SERVER_IP}"
    env.PMM_UI_URL = "https://${SERVER_IP}"
}

void runAMIStagingStart(AMI_ID, SSH_KEY) {
    amiStagingJob = build job: 'pmm2-ami-staging-start', parameters: [
        string(name: 'AMI_ID', value: AMI_ID),
        string(name: 'SSH_KEY', value: SSH_KEY)
    ]
    env.AMI_INSTANCE_ID = amiStagingJob.buildVariables.INSTANCE_ID
    env.AMI_INSTANCE_IP = amiStagingJob.buildVariables.IP
    env.PMM_URL = "http://admin:admin@${AMI_INSTANCE_IP}"
    env.PMM_UI_URL = "https://${AMI_INSTANCE_IP}"
}

void runAMIStaginStop(INSTANCE_ID) {
    amiStagingStopJob = build job: 'pmm2-ami-staging-stop', parameters: [
        string(name: 'AMI_ID', value: INSTANCE_ID),
    ]
}

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
        string(
            defaultValue: 'true',
            description: 'Use this AMI Setup as PMM-client',
            name: 'SETUP_CLIENT')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona-qa repository',
            name: 'GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                withCredentials([usernamePassword(credentialsId: 'Jenkins API', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        curl -s -u ${USER}:${PASS} ${BUILD_URL}api/json \
                            | python -c "import sys, json; print json.load(sys.stdin)['actions'][1]['causes'][0]['userId']" \
                            | sed -e 's/@percona.com//' \
                            > OWNER
                        echo "pmm-\$(cat OWNER | cut -d . -f 1)-\$(date -u '+%Y%m%d%H%M')" \
                            > VM_NAME
                    """
                }
                stash includes: 'OWNER,VM_NAME', name: 'VM_NAME'
                script {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()
                    if ("${NOTIFY}" == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        slackSend botUser: true, channel: "@${OWNER}", color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                    }
                }
            }
        }
        stage('Run PMM2 AMI Instance') {
            steps {
                runAMIStagingStart(AMI_ID, SSH_KEY)
            }
        }
    }
    post {

        success {
            runAMIStaginStop("${env.AMI_INSTANCE_ID}")
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${env.AMI_INSTANCE_IP}"
                    slackSend botUser: true, channel: "@${OWNER}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${env.AMI_INSTANCE_IP}"
                }
            }
        }
        failure {
            runAMIStaginStop("${env.AMI_INSTANCE_ID}")
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                    slackSend botUser: true, channel: "@${OWNER}", color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                }
            }
        }
    }
}