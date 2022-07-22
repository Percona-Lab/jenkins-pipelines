import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

String PUBLIC_IP = ''

Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
With this job you can run an OVA image with PMM server on a Digital Ocean droplet. We use DO instead of AWS here because AWS doesn't support nested virtualization.
'''

void enableRepo(String REPO) {
    withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            export REPO=${REPO}
            ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} '
                sudo yum update -y percona-release || true
                sudo sed -i'' -e 's^/release/^/${REPO}/^' /etc/yum.repos.d/pmm2-server.repo
                sudo percona-release enable percona ${REPO}
                sudo yum clean all
            '
        """
    }
}

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM2-Server-dev-latest.ova',
            description: 'OVA Image version, for installing already released version, pass 2.x.y ex. 2.28.0',
            name: 'OVA_VERSION')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo, for RC testing',
            name: 'ENABLE_TESTING_REPO')
        choice(
            choices: ['yes', 'no'],
            description: 'Enable Experimental, for Dev Latest testing',
            name: 'ENABLE_EXPERIMENTAL_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for pmm-qa branch',
            name: 'PMM_QA_GIT_COMMIT_HASH')
    }
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))
        timeout(time: 1, unit: 'DAYS')
    }
    environment {
        VM_NAME = "pmm-ovf-staging-${BUILD_ID}"
        VM_MEMORY = "10240"
        OVF_PUBLIC_KEY=credentials('OVF_STAGING_PUB_KEY_QA');
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
                        echo "pmm-ovf-staging-${BUILD_ID}" > VM_NAME
                    """
                }
                script {
                    env.IP  = sh(returnStdout: true, script: "cat IP").trim()
                    env.VM_NAME = sh(returnStdout: true, script: "cat VM_NAME").trim()
                }
                archiveArtifacts 'IP'
                archiveArtifacts 'VM_NAME'
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
                    sh """
                        if [[ \$OVA_VERSION = 2* ]]; then
                            wget -O ${VM_NAME}.ova https://downloads.percona.com/downloads/pmm2/${OVA_VERSION}/ova/pmm-server-${OVA_VERSION}.ova
                        else
                            wget -O ${VM_NAME}.ova http://percona-vm.s3-website-us-east-1.amazonaws.com/${OVA_VERSION}
                        fi
                    """
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
                            --cpus 6 \
                            --natpf1 "guestweb,tcp,,80,,80" \
                            --uart1 0x3F8 4 --uartmode1 file /tmp/${VM_NAME}-console.log \
                            --groups "/pmm"
                        VBoxManage modifyvm ${VM_NAME} --natpf1 "guesthttps,tcp,,443,,443"
                        VBoxManage modifyvm ${VM_NAME} --natpf1 "guestssh,tcp,,3022,,22"
                        for p in \$(seq 0 30); do
                            VBoxManage modifyvm ${VM_NAME} --natpf1 "guestexporters\$p,tcp,,4200\$p,,4200\$p"
                        done
                        VBoxManage startvm --type headless ${VM_NAME}
                        cat /tmp/${VM_NAME}-console.log
                        timeout 50 bash -c 'until curl --insecure -I https://${PUBLIC_IP}; do sleep 3; done' | true
                        sleep 60
                        curl -s --user admin:admin http://${PUBLIC_IP}/v1/Settings/Change --data '{"ssh_key": "'"\${OVF_PUBLIC_KEY}"'"}'
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
                                message: "OVF instance for ${OVA_VERSION} was created. IP: https://${PUBLIC_IP}\nYou can stop it with: https://pmm.cd.percona.com/job/pmm2-ovf-staging-stop/build"
                    }
                }
            }
        }
        stage('Enable Testing Repo') {
            when {
                expression { env.ENABLE_TESTING_REPO == "yes" && env.ENABLE_EXPERIMENTAL_REPO == "no" }
            }
            steps {
                node(env.VM_NAME){
                    enableRepo('testing')
                }
            }
        }
        stage('Enable Experimental Repo') {
            when {
                expression { env.ENABLE_EXPERIMENTAL_REPO == "yes" && env.ENABLE_TESTING_REPO == "no" }
            }
            steps {
                node(env.VM_NAME){
                    enableRepo('experimental')
                }
            }
        }
        stage('Enable Release Repo') {
            when {
                expression { env.ENABLE_EXPERIMENTAL_REPO == "no" && env.ENABLE_TESTING_REPO == "no" }
            }
            steps {
                node(env.VM_NAME) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} '
                                sudo yum update -y percona-release || true
                                sudo yum clean all
                            '
                        """
                    }
                }
            }
        }
        stage('Setup QA Repo on OVF VM') {
            steps {
                node(env.VM_NAME) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} '
                                export PMM_QA_GIT_BRANCH=${PMM_QA_GIT_BRANCH}
                                export PMM_QA_GIT_COMMIT_HASH=${PMM_QA_GIT_COMMIT_HASH}
                                sudo yum install -y wget
                                sudo mkdir -p /srv/pmm-qa || :
                                pushd /srv/pmm-qa
                                    sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                                    sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                                    sudo wget https://raw.githubusercontent.com/Percona-QA/percona-qa/master/get_download_link.sh
                                    sudo chmod 755 get_download_link.sh
                                popd
                                sudo chmod 755 /srv/pmm-qa/pmm-tests/pmm-framework.sh
                            '
                        """
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                def node = Jenkins.instance.getNode(env.VM_NAME)
                Jenkins.instance.removeNode(node)
            }
        }
    }
}
