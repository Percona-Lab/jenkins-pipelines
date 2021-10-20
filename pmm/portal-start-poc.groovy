import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'awscli'
    }
    
    parameters {
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
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
        string(
            defaultValue: 'true',
            description: 'notify',
            name: 'NOTIFY')
    }

    environment {
        OKTA_TOKEN=credentials('OKTA_TOKEN');
        OAUTH_CLIENT_ID=credentials('OAUTH_CLIENT_ID');
        OAUTH_CLIENT_SECRET=credentials('OAUTH_CLIENT_SECRET');
        OAUTH_PMM_CLIENT_ID=credentials('OAUTH_PMM_CLIENT_ID');
        OAUTH_PMM_CLIENT_SECRET=credentials('OAUTH_PMM_CLIENT_SECRET');
        DOCKER_REGISTRY_PASSWORD=credentials('github-api-token');
    }

    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                wrap([$class: 'BuildUser']) {
                    sh """
                        echo "\${BUILD_USER_EMAIL}" > OWNER_EMAIL
                        echo "\${BUILD_USER_EMAIL}" | awk -F '@' '{print \$1}' > OWNER_FULL
                        echo "pmm-\$(cat OWNER_FULL)-\$(date -u '+%Y%m%d%H%M%S')-${BUILD_NUMBER}" \
                            > VM_NAME
                    """
                }
                script {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER_FULL").trim()
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')

                    echo """
                        INFRA_BRANCH:   ${GIT_BRANCH}
                        AUTHED_TAG:     ${AUTHED_TAG}
                        ORGD_TAG:       ${ORGD_TAG}
                        TELEMETRYD_TAG: ${TELEMETRYD_TAG}
                        CHECKED_TAG:    ${CHECKED_TAG}
                        SAAS_UI_TAG:    ${SAAS_UI_TAG}
                    """
                }
                sh 'printenv'
            }
        }

        stage('Run VM') {
            steps {
                launchSpotInstance('m5.2xlarge', 'FAIR', 20)
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) 'java -version; sudo yum install -y java-1.8.0-openjdk; sudo /usr/sbin/alternatives --set java /usr/lib/jvm/jre-1.8.0-openjdk.x86_64/bin/java; java -version;' ; do
                            sleep 5
                        done

                        pwd
                    """
                }
                script {
                    env.IP      = sh(returnStdout: true, script: "cat IP").trim()
                    env.VM_NAME = sh(returnStdout: true, script: "cat VM_NAME").trim()

                    SSHLauncher ssh_connection = new SSHLauncher(env.IP, 22, 'aws-jenkins')
                    DumbSlave node = new DumbSlave(env.VM_NAME, "spot instance job", "/home/ec2-user/", "1", Mode.EXCLUSIVE, "", ssh_connection, RetentionStrategy.INSTANCE)

                    Jenkins.instance.addNode(node)
                }
                node(env.VM_NAME){
                    sh 'printenv'
                    sh """
                        set -o errexit
                        set -o xtrace

                        if [ -n "$SSH_KEY" ]; then
                            echo '$SSH_KEY' >> /home/ec2-user/.ssh/authorized_keys
                        fi

                        sudo yum -y update --security
                        sudo yum -y install git svn docker
                        sudo amazon-linux-extras install epel -y
                        sudo usermod -aG docker ec2-user
                        sudo systemctl start docker

                        # Install golang, conntrack, nss-tools and minisign
                        sudo yum install golang -y
                        sudo yum install conntrack -y
                        sudo yum install nss-tools -y
                        sudo yum install minisign -y

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
                    """
                }
                script {
                    def node = Jenkins.instance.getNode(env.VM_NAME)
                    Jenkins.instance.removeNode(node)
                    Jenkins.instance.addNode(node)                   
                }
                archiveArtifacts 'IP'
            }
        }
        stage('Configure and start minikube') {
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        sh """
                        pwd

                        echo \$IP
                        echo \$VM_NAME
                        """
                        node(env.VM_NAME){
                            git branch: 'main', credentialsId: 'GitHub SSH Key', url: 'git@github.com:percona-platform/infra.git'
                            sh 'printenv'
                            sh """
                                set -o errexit
                                set -o xtrace

                                # Setting minikube related env vars
                                export AUTHED_TAG=${AUTHED_TAG}
                                export ORGD_TAG=${ORGD_TAG}
                                export TELEMETRYD_TAG=${TELEMETRYD_TAG}
                                export CHECKED_TAG=${CHECKED_TAG}
                                export SAAS_UI_TAG=${SAAS_UI_TAG}

                                export DOCKER_REGISTRY_USERNAME=percona-robot
                                export DOCKER_REGISTRY_PASSWORD=\$DOCKER_REGISTRY_PASSWORD

                                export OKTA_TOKEN=\$OKTA_TOKEN
                                
                                export OAUTH_ISSUER_URL=https://id-dev.percona.com/oauth2/aus15pi5rjdtfrcH51d7
                                export OAUTH_CLIENT_ID=\$OAUTH_CLIENT_ID
                                export OAUTH_CLIENT_SECRET=\$OAUTH_CLIENT_SECRET
                                export OAUTH_PMM_CLIENT_ID=\$OAUTH_PMM_CLIENT_ID
                                export OAUTH_PMM_CLIENT_SECRET=\$OAUTH_PMM_CLIENT_SECRET
                                export OAUTH_SCOPES=percona
                                
                                # Configure minikube
                                minikube delete --all --purge
                                rm -rf ~/.minikube
                                minikube config set cpus 2
                                minikube config set memory 4096
                            """
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
                Jenkins.instance.removeNode(node)
            }
        }
        success {
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER_FULL = sh(returnStdout: true, script: "cat OWNER_FULL").trim()
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')

                    slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${env.IP}"
                }
            }
        }
        failure {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                    set -o xtrace
                    export REQUEST_ID=\$(cat REQUEST_ID)
                    if [ -n "$REQUEST_ID" ]; then
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids \$REQUEST_ID
                        aws ec2 --region us-east-2 terminate-instances --instance-ids \$(cat ID)
                    fi
                '''
            }
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER_FULL = sh(returnStdout: true, script: "cat OWNER_FULL").trim()
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')

                    // slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed, owner: @${OWNER_FULL}"
                    slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                }
            }
        }
    }
}
