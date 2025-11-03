import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
With this job you can run an OVA image with PMM server on a Digital Ocean droplet. We use DO instead of AWS here because AWS doesn't support nested virtualization.
'''


pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM3-Server-dev-latest.ova',
            description: 'OVA Image version, for installing already released version, pass 3.x.y ex. 3.28.0',
            name: 'OVA_VERSION')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for pmm-qa branch',
            name: 'PMM_QA_GIT_COMMIT_HASH')
        string(
            defaultValue: '',
            description: 'public ssh key, please set if you need ssh access, it will set this ssh key to host machine for "root" user and "admin" user in VM',
            name: 'SSH_KEY')
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
                    env.DEFAULT_SSH_KEYS = getSSHKeys()
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

                        echo "${env.DEFAULT_SSH_KEYS}" | ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no root@${PUBLIC_IP} 'cat - >> /root/.ssh/authorized_keys'
                        if [ -n "$SSH_KEY" ]; then
                            echo "$SSH_KEY" | ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no root@${PUBLIC_IP} 'cat - >> /root/.ssh/authorized_keys'
                        fi
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
                    sh '''
                        if [[ ${OVA_VERSION} = 3* ]]; then
                            if [ "$(printf '%s\n' "3.0.0" "$v" | sort -V | head -n1)" = "3.0.0" ] && [ "$v" != "3.0.0" ]; then
                                wget -nv -O pmm-server.ova  https://downloads.percona.com/downloads/pmm3/${OVA_VERSION}/ova/pmm-server-${OVA_VERSION}.ova
                            else
                                wget -nv -O pmm-server.ova  https://downloads.percona.com/downloads/pmm3/${OVA_VERSION}/ova/PMM3-Server-${OVA_VERSION}-1.ova
                            fi
                        else
                            wget -nv -O pmm-server.ova http://percona-vm.s3-website-us-east-1.amazonaws.com/${OVA_VERSION}
                        fi
                    '''
                    sh '''
                        export BUILD_ID=dont-kill-virtualbox
                        export JENKINS_NODE_COOKIE=dont-kill-virtualbox

                        tar xvf pmm-server.ova
                        export OVF_NAME=$(find -type f -name '*.ovf');
                        VBoxManage import $OVF_NAME --vsys 0 --memory ${VM_MEMORY} --vmname pmm-server
                        VBoxManage modifyvm pmm-server \
                            --memory ${VM_MEMORY} \
                            --audio none \
                            --cpus 6 \
                            --natpf1 "guestweb,tcp,,80,,80" \
                            --uart1 0x3F8 4 --uartmode1 file /tmp/pmm-server-console.log \
                            --groups "/pmm"
                        VBoxManage modifyvm pmm-server --natpf1 "guesthttps,tcp,,443,,443"
                        VBoxManage modifyvm pmm-server --natpf1 "guestssh,tcp,,3022,,22"
                        for p in $(seq 0 30); do
                            PORT=$((42000+p))
                            VBoxManage modifyvm pmm-server --natpf1 "guestexporters$p,tcp,,$PORT,,$PORT"
                        done
                        VBoxManage modifyvm pmm-server --vrde on
                        VBoxManage modifyvm pmm-server --vrdeport 5000
                        VBoxManage startvm --type headless pmm-server
                        timeout 200 bash -c "until curl -ksf https://${IP}/graph/login > /dev/null; do sleep 5; done" || true
                    '''
                    sh '''
                        # Isolate this step in case it fails
                        sleep 10
                        curl -k --user admin:admin -X PUT https://${IP}/v1/server/settings --data '{"ssh_key": "'"$OVF_PUBLIC_KEY"'"}'
                    '''
                }
            }
        }
        stage('Setup QA Repo on OVF VM') {
            steps {
                node(env.VM_NAME) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null admin@${IP} '
                                sudo dnf install -y wget git
                                sudo mkdir -p /srv/pmm-qa || :
                                set -x
                                pushd /srv/pmm-qa
                                    sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                                    sudo git checkout ${PMM_QA_GIT_COMMIT_HASH}
                                    sudo curl -O https://raw.githubusercontent.com/Percona-QA/percona-qa/master/get_download_link.sh
                                    sudo chmod 755 get_download_link.sh
                                popd
                                sudo chmod 755 /srv/pmm-qa/pmm-tests/pmm-framework.sh
                                set +x
                            '
                        """
                    }
                }
            }
        }
        stage('Setup user SSH key') {
            steps {
                node(env.VM_NAME) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null admin@${IP} '
                                if [ -n "${env.DEFAULT_SSH_KEYS}" ]; then
                                    echo "${env.DEFAULT_SSH_KEYS}" >> /home/admin/.ssh/authorized_keys
                                fi
                                if [ -n "$SSH_KEY" ]; then
                                    echo "$SSH_KEY" | sudo tee -a /home/admin/.ssh/authorized_keys
                                fi
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
                            message: "OVF instance of ${OVA_VERSION} has been created. IP: https://${IP}\nYou can stop it with: https://pmm.cd.percona.com/job/pmm3-ovf-staging-stop/parambuild/?VM=${IP}"
                }
            }
        }
        failure {
            withCredentials([string(credentialsId: 'f5415992-e274-45c2-9eb9-59f9e8b90f43', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
                node(env.VM_NAME){
                    sh '''
                        set -o xtrace

                        # https://docs.digitalocean.com/products/droplets/how-to/retrieve-droplet-metadata/
                        DROPLET_ID=$(curl -s http://169.254.169.254/metadata/v1/id)
                        doctl compute droplet delete $DROPLET_ID --force
                    '''
                }
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
