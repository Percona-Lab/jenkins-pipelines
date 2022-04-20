import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher
import jenkins.model.*

final jenkins   = Jenkins.getInstanceOrNull()
final currentJob     = jenkins.getItem(env.JOB_NAME)

description = "testing"
currentJob.setDescription(description)
currentJob.save()

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

String PUBLIC_IP = ''

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM2-Server-dev-latest.ova',
            description: 'OVA Image version',
            name: 'OVA_VERSION')
    }
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))
        timeout(time: 1, unit: 'DAYS')
    }
    environment {
        VM_NAME = "pmm-ovf-staging-${BUILD_ID}"
        VM_MEMORY = "10240"
    }
    stages {
        stage('Run staging server') {
            steps {
                withCredentials([
                        sshUserPrivateKey(credentialsId: 'e54a801f-e662-4e3c-ace8-0d96bec4ce0e', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER'),
                        string(credentialsId: '82c0e9e0-75b5-40ca-8514-86eca3a028e0', variable: 'DIGITALOCEAN_ACCESS_TOKEN')
                    ]) {
                    sh """
                        SSH_KEY_ID=\$(doctl compute ssh-key list | grep Jenkins | awk '{ print \$1}')
                        echo "Key ID: \${SSH_KEY_ID}"
                        IMAGE_ID=\$(doctl compute image list | grep pmm-agent | awk '{ print \$1}')
                        echo "Image ID: \${IMAGE_ID}"
                        PUBLIC_IP=\$(doctl compute droplet create --region ams3 --image \$IMAGE_ID --wait --ssh-keys \$SSH_KEY_ID --tag-name jenkins-pmm --size s-8vcpu-16gb-intel ${env.VM_NAME} -o json | jq -r '.[0].networks.v4[0].ip_address')
                        echo "Public IP: \$PUBLIC_IP"
                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no root@\${PUBLIC_IP}; do
                            sleep 5
                        done
                        echo \$PUBLIC_IP > IP
                    """
                }
                script {
                    env.IP = sh(returnStdout: true, script: "cat IP").trim()

                    SSHLauncher ssh_connection = new SSHLauncher(env.IP, 22, 'e54a801f-e662-4e3c-ace8-0d96bec4ce0e')
                    DumbSlave node = new DumbSlave(env.VM_NAME, "OVA staging instance: ${VM_NAME}", "/root", "1", Mode.EXCLUSIVE, "", ssh_connection, RetentionStrategy.INSTANCE)

                    currentBuild.description = "IP: ${env.IP} NAME: ${env.VM_NAME}"
                    Jenkins.instance.addNode(node)
                }
                node(env.VM_NAME){
                    script {
                        PUBLIC_IP = sh(returnStdout: true, script: 'curl ifconfig.me')
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
                                message: "OVA staging for ${OVA_VERSION} was created. IP: https://${PUBLIC_IP}\nYou can stop instance here: https://pmm.cd.percona.com/job/pmm2-ovf-staging-stop/build"
                    }
                }
            }
        }
    }
}
