import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def DEFAULT_SSH_KEYS = getSHHKeysPMM()

pipeline {
    agent {
        label 'cli'
    }

    parameters {
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user for accessing portal',
            name: 'SSH_KEY')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for infra repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'latest',
            description: 'Docker tag for authed service',
            name: 'AUTHED_TAG')
        string(
            defaultValue: 'latest',
            description: 'Docker tag for orgd service',
            name: 'ORGD_TAG')
        string(
            defaultValue: 'latest',
            description: 'Docker tag for eventd service',
            name: 'EVENTD_TAG')
        string(
            defaultValue: 'latest',
            description: 'Docker tag for telemetryd service',
            name: 'TELEMETRYD_TAG')
        string(
            defaultValue: 'latest',
            description: 'Docker tag for checked service',
            name: 'CHECKED_TAG')
        string(
            defaultValue: 'latest',
            description: 'Docker tag for saas-ui service',
            name: 'SAAS_UI_TAG')
        string(
            defaultValue: '1',
            description: 'Stop the instance after, days ("0" value disables autostop and recreates instance in case of AWS failure)',
            name: 'DAYS')
        choice(
            choices: ['true', 'false'],
            description: 'Enable Slack notification',
            name: 'NOTIFY')
    }

    environment {
        OKTA_TOKEN=credentials('OKTA_TOKEN');
        OAUTH_CLIENT_ID=credentials('OAUTH_CLIENT_ID');
        OAUTH_CLIENT_SECRET=credentials('OAUTH_CLIENT_SECRET');
        OAUTH_PMM_CLIENT_ID=credentials('OAUTH_PMM_CLIENT_ID');
        OAUTH_PMM_CLIENT_SECRET=credentials('OAUTH_PMM_CLIENT_SECRET');
        DOCKER_REGISTRY_PASSWORD=credentials('DOCKER_REGISTRY_PASSWORD');
        ORGD_CIVO_APIKEY=credentials('ORGD_CIVO_APIKEY');
        ORGD_SES_KEY=credentials('ORGD_SES_KEY');
        ORGD_SES_SECRET=credentials('ORGD_SES_SECRET');
        ORGD_SERVICENOW_PASSWORD=credentials('ORGD_SERVICENOW_PASSWORD');
        OAUTH_ISSUER_URL="https://id-dev.percona.com/oauth2/aus15pi5rjdtfrcH51d7";
        DOCKER_REGISTRY_USERNAME="percona-robot";
        OKTA_URL_DEV="id-dev.percona.com";
        OAUTH_SCOPES="percona";
        MINIKUBE_MEM=16384;
        MINIKUBE_CPU=8;
    }

    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                // getPMMBuildParams sets envvars: VM_NAME, OWNER, OWNER_SLACK
                getPMMBuildParams('portal-')
            }
        }

        stage('Run VM') {
            steps {
                // This sets envvars: SPOT_PRICE, REQUEST_ID, IP, ID (AMI_ID)
                launchSpotInstance('m5.2xlarge', 'FAIR', 30)

                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${USER}@${IP}; do
                            sleep 5
                        done
                    """
                }
                script {
                    SSHLauncher ssh_connection = new SSHLauncher(env.IP, 22, 'aws-jenkins')
                    DumbSlave node = new DumbSlave(env.VM_NAME, "Portal staging instance: ${VM_NAME}", "/home/ec2-user/", "1", Mode.EXCLUSIVE, "", ssh_connection, RetentionStrategy.INSTANCE)

                    Jenkins.instance.addNode(node)
                    currentBuild.description = "IP: ${env.IP} NAME: ${env.VM_NAME} PRICE: ${env.SPOT_PRICE}"
                }
                node(env.VM_NAME){
                    sh """
                        set -o errexit
                        set -o xtrace

                        echo "${DEFAULT_SSH_KEYS}" >> /home/ec2-user/.ssh/authorized_keys
                        if [ -n "${SSH_KEY}" ]; then
                            echo "${SSH_KEY}" >> /home/ec2-user/.ssh/authorized_keys
                        fi

                        # sudo yum -y update --security      # disabled on 20220926 due to failure with existing image
                        # see what software gets installed to every ec2 instance
                        # https://github.com/percona/pmm-infra/blob/main/packer/ansible/agent.yml#L38-L122
                        sudo usermod -aG docker ec2-user
                        sudo systemctl start docker
                        docker version

                        # Install golang, conntrack, nss-tools and minisign
                        sudo yum install golang conntrack nss-tools minisign -y

                        # Install mkcert
                        curl -sSL https://github.com/FiloSottile/mkcert/releases/download/v1.4.1/mkcert-v1.4.1-linux-amd64 > mkcert && chmod +x mkcert
                        sudo mv ./mkcert /usr/local/bin/

                        # Install kubectl
                        curl -LO https://storage.googleapis.com/kubernetes-release/release/`curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt`/bin/linux/amd64/kubectl && chmod +x ./kubectl
                        sudo mv ./kubectl /usr/local/bin/kubectl

                        # Install minikube
                        curl -Lo minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && chmod +x minikube
                        sudo mv ./minikube /usr/local/bin
                        minikube version


                        # Install direnv
                        wget -O direnv https://github.com/direnv/direnv/releases/download/v2.6.0/direnv.linux-amd64
                        chmod +x direnv
                        sudo mv direnv /usr/local/bin/

                        # direnv hook
                        echo 'eval "\$(direnv hook bash)"' >> ~/.bashrc
                        source ~/.bashrc
                    """
                }
                script {
                    def node = Jenkins.instance.getNode(env.VM_NAME)
                    Jenkins.instance.removeNode(node)
                    Jenkins.instance.addNode(node)
                }
            }
        }
        stage('Configure and start minikube') {
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            git branch: GIT_BRANCH, credentialsId: 'GitHub SSH Key', url: 'git@github.com:percona-platform/infra.git'
                            sh """
                                set -o errexit
                                set -o xtrace

                                export PATH="/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin:/home/ec2-user/bin"
                                # Configure minikube
                                minikube delete --all --purge
                                rm -rf ~/.minikube

                                pushd k8s/platform-saas/local

                                cat <<EOF > .envrc
echo LOCAL MINIKUBE

# Login into GitHub registry to pull private images (authed, checked, orgd and etc.)
export DOCKER_REGISTRY_USERNAME=$DOCKER_REGISTRY_USERNAME
export DOCKER_REGISTRY_PASSWORD=$DOCKER_REGISTRY_PASSWORD


# OKTA config
export OKTA_TOKEN=$OKTA_TOKEN
export OKTA_URL_DEV=$OKTA_URL_DEV

export OAUTH_ISSUER_URL=$OAUTH_ISSUER_URL
export OAUTH_CLIENT_ID=$OAUTH_CLIENT_ID
export OAUTH_CLIENT_SECRET=$OAUTH_CLIENT_SECRET
export OAUTH_SCOPES=$OAUTH_SCOPES


# AWS SES credentials
export ORGD_SES_KEY=$ORGD_SES_KEY
export ORGD_SES_SECRET=$ORGD_SES_SECRET
export ORGD_CIVO_APIKEY=$ORGD_CIVO_APIKEY


# ServiceNow credentials
export ORGD_SERVICENOW_PASSWORD=$ORGD_SERVICENOW_PASSWORD


# Control of docker image tags, which will be pulled during 'make env-up'
export AUTHED_TAG=$AUTHED_TAG
export CHECKED_TAG=$CHECKED_TAG
export EVENTD_TAG=$EVENTD_TAG
export ORGD_TAG=$ORGD_TAG
export SAAS_UI_TAG=$SAAS_UI_TAG
export TELEMETRYD_TAG=$TELEMETRYD_TAG

export MINIKUBE_MEM=$MINIKUBE_MEM
export MINIKUBE_CPU=$MINIKUBE_CPU
EOF
                                direnv allow
                                make env-up
                                sudo -E chown -R \$(stat --format="%U:%G" \${HOME}) /etc/hosts
                                make tunnel-background
                            """
                            script {
                                env.MINIKUBE_IP = sh(returnStdout: true, script: "awk -F _ 'END{print}' /etc/hosts | cut -d' ' -f 1").trim()
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def node = Jenkins.instance.getNode(env.VM_NAME)
                if (node) {
                    Jenkins.instance.removeNode(node)
                }
            }
        }
        success {
            script {
                if (params.NOTIFY == "true") {
                    def SLACK_MESSAGE = """[${JOB_NAME}]: build finished, URL: ${BUILD_URL}, IP: ${env.IP}
In order to access the instance you need:
1. make sure that `/etc/hosts` on your machine contains the following line
```127.0.0.1 platform.localhost check.localhost pmm.localhost```
2. run the following command in your terminal to proxy-pass http(s) ports:
```sudo ssh -N -L :443:${env.MINIKUBE_IP}:443 -N -L :80:${env.MINIKUBE_IP}:80 ec2-user@${env.IP}```
3. open https://platform.localhost in your browser
4. to allow another person to access the instance, please run this command in your terminal:
```ssh ec2-user@${env.IP} 'echo "THEIR_PUBLIC_SSH_KEY" >> ~/.ssh/authorized_keys'```
*Note: the other user should also complete the steps 1-2*
                    """

                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "${SLACK_MESSAGE}"
                    if (env.OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "${SLACK_MESSAGE}"
                    }
                }
            }
        }
        failure {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                    set -o xtrace
                    if [ -n "${REQUEST_ID}" ]; then
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids ${REQUEST_ID}
                        aws ec2 --region us-east-2 terminate-instances --instance-ids ${ID}
                    fi
                '''
            }
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed ${BUILD_URL}"
                    if (env.OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build failed ${BUILD_URL}"
                    }
                }
            }
        }
    }
}
