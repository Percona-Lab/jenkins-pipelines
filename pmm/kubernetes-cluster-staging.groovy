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
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
            name: 'SSH_KEY')
        choice(
            choices: '1\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n21\n22\n23\n24\n25\n26\n27\n28\n29\n30',
            description: 'Stop the instance after, days ("0" value disables autostop and recreates instance in case of AWS failure)',
            name: 'DAYS')
        choice(
            choices: ['1.23.1', '1.22.1', '1.21.1', '1.20.1'],
            description: 'Select Kubernetes version',
            name: 'KUBE_VERSION')
        choice(
            choices: ['none', 'v1.10.0', 'v1.9.0', 'v1.8.0'],
            description: 'Select version of PXC operator',
            name: 'PXC_OPERATOR_VERSION')
        choice(
            choices: ['none', 'v1.12.0', 'v1.11.0', 'v1.10.0', 'v1.9.0', 'v1.8.0'],
            description: 'Select version of PSMDB operator',
            name: 'PSMDB_OPERATOR_VERSION')                    
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
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
        timeout(time: 15, unit: 'MINUTES')
    }
    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                script {
                    // This sets envvars as folows: VM_NAME, OWNER, OWNER_SLACK
                    getPMMBuildParams('pmm-k8s-cluster-')

                    echo "KUBE_VERSION: ${KUBE_VERSION}"

                    if (params.NOTIFY == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: cluster creation - ${BUILD_URL}"
                        if (env.OWNER_SLACK) {
                            slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#0000FF', message: "[${JOB_NAME}]: cluster creation - ${BUILD_URL}"
                        }
                    }
                }
            }
        }

        stage('Run VM') {
            steps {
                // This sets envvars as follows: SPOT_PRICE, REQUEST_ID, IP, ID (AMI_ID)
                launchSpotInstance('c5n.4xlarge', 'FAIR', 70)

                script {
                    currentBuild.description = "PRICE: ${SPOT_PRICE}, IP: ${IP}, OWNER: ${OWNER}"

                    SSHLauncher ssh_connection = new SSHLauncher(env.IP, 22, 'aws-jenkins')
                    DumbSlave node = new DumbSlave(env.VM_NAME, "k8s cluster staging instance: ${VM_NAME}", "/home/ec2-user/", "1", Mode.EXCLUSIVE, "", ssh_connection, RetentionStrategy.INSTANCE)

                    Jenkins.instance.addNode(node)
                }
                node(env.VM_NAME){
                    sh """
                        set -o errexit
                        set -o xtrace

                        echo "$DEFAULT_SSH_KEYS" >> /home/ec2-user/.ssh/authorized_keys

                        if [ -n "$SSH_KEY" ]; then
                            echo '$SSH_KEY' >> /home/ec2-user/.ssh/authorized_keys
                        fi

                        sudo yum -y update --security
                        sudo amazon-linux-extras install epel -y
                        sudo usermod -aG docker ec2-user
                        sudo systemctl start docker
                        sudo mkdir -p /srv/pmm-qa || :
                        pushd /srv/pmm-qa
                            sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                            sudo git checkout ${PMM_QA_GIT_COMMIT_HASH}
                        popd
                    """
                }
                script {
                    def node = Jenkins.instance.getNode(env.VM_NAME)
                    Jenkins.instance.removeNode(node)
                    Jenkins.instance.addNode(node)
                }
            }
        }
        stage('Setup Minikube') {
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                sudo yum -y install curl
                                /srv/pmm-qa/pmm-tests/install_k8s_tools.sh --minikube --kubectl --sudo
                                sleep 5
                            """
                        }
                    }
                }
            }
        }
        stage('Run Operator Setup Script') {
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                export PATH=\$PATH:/usr/sbin
                                minikube version
                                rm ~/.kube/config && minikube delete
                                minikube config set cpus 8
                                minikube config set memory 29000
                                minikube config set kubernetes-version ${KUBE_VERSION}
                                export CHANGE_MINIKUBE_NONE_USER=true
                                sudo yum install -y conntrack
                                minikube start --driver=none
                                sudo chown -R $USER $HOME/.kube $HOME/.minikube
                                sed -i s:/root:$HOME:g $HOME/.kube/config
                                bash /srv/pmm-qa/pmm-tests/minikube_operators_setup.sh ${PXC_OPERATOR_VERSION} ${PSMDB_OPERATOR_VERSION}
                                sleep 10
                            """
                        }
                    }
                }
            }
        }
        stage('Check Operators Status') {
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                export PATH=\$PATH:/usr/sbin
                                kubectl get nodes
                                kubectl get pods

                                if [ "${PXC_OPERATOR_VERSION}" != none ]; then
                                    kubectl wait --for=condition=Available --timeout=60s deployment percona-xtradb-cluster-operator
                                fi

                                if [ "${PSMDB_OPERATOR_VERSION}" != none ]; then
                                    kubectl wait --for=condition=Available --timeout=60s deployment percona-server-mongodb-operator
                                fi                                
                            """
                        }
                    }
                }
            }
        }
        stage('Generate Kubeconfig') {
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                export PATH=\$PATH:/usr/sbin
                                kubectl config view --flatten --minify > kubeconfig.yml
                            """
                        }
                        withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                            sh """
                                scp -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                                    ${USER}@${IP}:workspace/kubernetes-cluster-staging/kubeconfig.yml \
                                    kubeconfig
                            """
                        }
                        script {
                            env.KUBECONFIG = sh(returnStdout: true, script: "cat kubeconfig").trim()
                        }
                        stash includes: 'kubeconfig', name: 'kubeconfig'
                        archiveArtifacts 'kubeconfig'
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
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: cluster creation finished, owner: @${OWNER}, Cluster IP: ${IP}, Build: ${BUILD_URL}"
                    if (env.OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: cluster creation finished - Cluster IP: ${IP}, Build: ${BUILD_URL}"
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
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: cluster creation failed, owner: @${OWNER}, Failed Build: ${BUILD_URL}"
                    if (env.OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: cluster creation failed, Failed Build: ${BUILD_URL}"
                    }
                }
            }
        }
    }
}
