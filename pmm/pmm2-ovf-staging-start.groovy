library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

String PUBLIC_IP = ''

pipeline {
    agent {
        label 'ovf-do'
    }
    parameters {
        string(
            defaultValue: 'PMM2-Server-dev-latest.ova',
            description: 'OVA Image version',
            name: 'OVA_VERSION')
    }
    options {
        skipDefaultCheckout()
        timeout(time: 1, unit: 'DAYS')
    }
    environment {
        VM_NAME = "pmm-staging-${BUILD_ID}"
        VM_MEMORY = "4096"
    }
    stages {
        stage('Run PMM-Server') {
            steps {
                script {
                    PUBLIC_IP = sh(returnStdout: true, script: 'curl ifconfig.me')
                    currentBuild.description = "<a href='https://${PUBLIC_IP}'>${PUBLIC_IP}</a>"
                }
                sh "wget -O ${VM_NAME}.ova http://percona-vm.s3-website-us-east-1.amazonaws.com/${OVA_VERSION}"
                sh """
                    export BUILD_ID=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_NODE_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_SERVER_COOKIE=dear-jenkins-please-dont-kill-virtualbox

                    tar xvf ${VM_NAME}.ova
                    export ovf_name=\$(find -type f -name '*.ovf');
                    VBoxManage import \$ovf_name --vsys 0 --memory ${VM_MEMORY} --vmname ${VM_NAME}
                    VBoxManage modifyvm ${VM_NAME} \
                        --memory ${VM_MEMORY} \
                        --audio none \
                        --natpf1 "guestssh,tcp,,80,,80" \
                        --uart1 0x3F8 4 --uartmode1 file /tmp/${VM_NAME}-console.log \
                        --groups "/pmm"
                    VBoxManage modifyvm ${VM_NAME} --natpf1 "guesthttps,tcp,,443,,443"
                    for p in \$(seq 0 15); do
                        VBoxManage modifyvm ${VM_NAME} --natpf1 "guestexporters\$p,tcp,,4200\$p,,4200\$p"
                    done
                    VBoxManage startvm --type headless ${VM_NAME}
                    cat /tmp/${VM_NAME}-console.log
                    timeout 50 bash -c 'until curl --insecure -I https://${PUBLIC_IP}; do sleep 3; done' | true
                """
                script {

                    def OWNER_SLACK = slackUserIdFromEmail(
                        botUser: true,
                        email: BUILD_USER_EMAIL,
                        tokenCredentialId: 'JenkinsCI-SlackBot-v2'
                    )

                    slackSend botUser: true,
                              channel: "@${OWNER_SLACK}",
                              color: '#00FF00',
                              message: "OVA staging for ${OVA_VERSION} was created. IP: https://${PUBLIC_IP}\nYou can stop instance here: ${BUILD_URL}/console"
                }
            }
        }
        stage('Waiting for ') {
            input {
                message 'Do you want to shutdown instance?'
            }
            steps {
                echo 'Instance will be shutdown'
            }
        }
    }
}
