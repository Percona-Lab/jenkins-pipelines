import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
With this job you can run an OVA image with PMM server on a Digital Ocean droplet. We use DO instead of AWS here because AWS doesn't support nested virtualization.
'''

void enableRepo(String REPO, String PUBLIC_IP) {
    withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            export REPO=${REPO}
            export PUBLIC_IP=${PUBLIC_IP}
            ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null admin@${PUBLIC_IP} '
                sudo yum update -y percona-release || true
                sudo sed -i'' -e 's^/release/^/${REPO}/^' /etc/yum.repos.d/pmm2-server.repo
                sudo percona-release enable pmm2-client ${REPO}
                sudo yum clean all
                sudo yum clean metadata
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
        timeout(time: 6, unit: 'HOURS')
    }
    environment {
        VM_MEMORY = "10240"
        OVF_PUBLIC_KEY=credentials('OVF_STAGING_PUB_KEY_QA')
    }
    stages {
        stage('Run staging server') {
            steps {
                deleteDir()
                script {
                    env.VM_NAME = "pmm-ovf-staging-${BUILD_ID}"
                }
                withCredentials([
                        sshUserPrivateKey(credentialsId: 'e54a801f-e662-4e3c-ace8-0d96bec4ce0e', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER'),
                        string(credentialsId: 'f5415992-e274-45c2-9eb9-59f9e8b90f43', variable: 'DIGITALOCEAN_ACCESS_TOKEN')
                    ]) {
                    sh '''
                        # Constants we rely on for PMM builds/tests:
                        # - droplet tag name: is set to `jenkins-pmm`
                        # - image id: is set to `pmm-ovf-agent`
                        # - ssh-key name: is set to `Jenkins`
                        # - firewall name: is set to `pmm-firewall`

                        set -o xtrace

                        SSH_KEY_ID=$(doctl compute ssh-key list -o json | jq -r '.[] | select(.name=="Jenkins") | .id')
                        IMAGE_ID=$(doctl compute image list -o json | jq -r '.[] | select(.name=="pmm-ovf-agent") | .id')
                        set +x
                        DROPLET=$(doctl compute droplet create --region ams3 --image $IMAGE_ID --wait --ssh-keys $SSH_KEY_ID --tag-name jenkins-pmm --size s-8vcpu-16gb-intel ${VM_NAME} -o json)
                        PUBLIC_IP=$(echo $DROPLET | jq -r '.[0].networks.v4[0].ip_address')
                        DROPLET_ID=$(echo $DROPLET | jq -r '.[0].id')
                        set -x
                        FIREWALL_ID=$(doctl compute firewall list -o json | jq -r '.[] | select(.name=="pmm-firewall") | .id')
                        doctl compute firewall add-droplets $FIREWALL_ID --droplet-ids $DROPLET_ID

                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null root@$PUBLIC_IP; do
                            sleep 5
                        done
                        echo "$PUBLIC_IP" | tee IP
                    '''
                }
                script {
                    env.IP = sh(returnStdout: true, script: "cat IP").trim()

                    SSHLauncher ssh_connection = new SSHLauncher(env.IP, 22, 'e54a801f-e662-4e3c-ace8-0d96bec4ce0e')
                    DumbSlave node = new DumbSlave(env.VM_NAME, "OVA staging instance: ${VM_NAME}", "/root", "1", Mode.EXCLUSIVE, "", ssh_connection, RetentionStrategy.INSTANCE)

                    currentBuild.description = "IP: ${env.IP} NAME: ${env.VM_NAME}"
                    Jenkins.instance.addNode(node)
                }
                node(env.VM_NAME){
                    sh """
                        if [[ ${OVA_VERSION} = 2* ]]; then
                            wget -nv -O ${VM_NAME}.ova https://downloads.percona.com/downloads/pmm2/${OVA_VERSION}/ova/pmm-server-${OVA_VERSION}.ova
                        else
                            wget -nv -O ${VM_NAME}.ova http://percona-vm.s3-website-us-east-1.amazonaws.com/${OVA_VERSION}
                        fi
                    """
                    sh """
                        export BUILD_ID=dont-kill-virtualbox
                        export JENKINS_NODE_COOKIE=dont-kill-virtualbox

                        tar xvf ${VM_NAME}.ova
                        export OVF_NAME=\$(find -type f -name '*.ovf');
                        VBoxManage import \$OVF_NAME --vsys 0 --memory ${VM_MEMORY} --vmname ${VM_NAME}
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
                        timeout 100 bash -c 'until curl --insecure -LI https://${IP}; do sleep 5; done' || true
                    """
                    sh """
                        # This fails sometimes, so we want to isolate this step
                        sleep 60
                        curl -s --user admin:admin http://${IP}/v1/Settings/Change --data '{"ssh_key": "'"\${OVF_PUBLIC_KEY}"'"}'
                    """
                }
            }
        }
        stage('Enable Testing Repo') {
            when {
                expression { env.ENABLE_TESTING_REPO == "yes" && env.ENABLE_EXPERIMENTAL_REPO == "no" }
            }
            steps {
                node(env.VM_NAME){
                    enableRepo('testing', env.IP)
                }
            }
        }
        stage('Enable Experimental Repo') {
            when {
                expression { env.ENABLE_EXPERIMENTAL_REPO == "yes" && env.ENABLE_TESTING_REPO == "no" }
            }
            steps {
                node(env.VM_NAME){
                    enableRepo('experimental', env.IP)
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
                        sh '''
                            ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null admin@${IP} '
                                sudo yum update -y percona-release || true
                                sudo yum clean all
                            '
                        '''
                    }
                }
            }
        }
        stage('Setup QA Repo on OVF VM') {
            steps {
                node(env.VM_NAME) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null admin@${IP} '
                                export PMM_QA_GIT_BRANCH=${PMM_QA_GIT_BRANCH}
                                export PMM_QA_GIT_COMMIT_HASH=${PMM_QA_GIT_COMMIT_HASH}
                                sudo yum install -y wget git
                                sudo mkdir -p /srv/pmm-qa || :
                                pushd /srv/pmm-qa
                                    sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                                    sudo git checkout ${PMM_QA_GIT_COMMIT_HASH}
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
        success {
            script {
                wrap([$class: 'BuildUser']) {
                    env.OWNER_SLACK = slackUserIdFromEmail(
                        botUser: true,
                        email: env.BUILD_USER_EMAIL,
                        tokenCredentialId: 'JenkinsCI-SlackBot-v2'
                    )
                }

                if (env.OWNER_SLACK) {
                    slackSend botUser: true,
                            channel: "@${OWNER_SLACK}",
                            color: '#00FF00',
                            message: "OVF instance of ${OVA_VERSION} has been created. IP: https://${IP}\nYou can stop it with: https://pmm.cd.percona.com/job/pmm2-ovf-staging-stop/parambuild/?VM=${IP}"
                }
            }
        }
        failure {
            withCredentials([string(credentialsId: 'f5415992-e274-45c2-9eb9-59f9e8b90f43', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
                sh '''
                    set -o xtrace

                    # https://docs.digitalocean.com/products/droplets/how-to/retrieve-droplet-metadata/
                    DROPLET_ID=$(curl -s http://169.254.169.254/metadata/v1/id)
                    doctl compute droplet delete $DROPLET_ID
                '''
            }
        }
        cleanup {
            script {
                deleteDir()
                def node = Jenkins.instance.getNode(env.VM_NAME)
                if (node) {
                    Jenkins.instance.removeNode(node)
                } else {
                    echo "Warning: no node to remove"
                }
            }
        }
    }
}
