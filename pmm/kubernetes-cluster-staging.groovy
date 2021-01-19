import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

pipeline {
    agent {
        label 'awscli'
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
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for pmm-qa branch',
            name: 'PMM_QA_GIT_COMMIT_HASH')
    }
    options {
        skipDefaultCheckout()
    }

    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                wrap([$class: 'BuildUser']) {
                    sh """
                        echo "\${BUILD_USER_EMAIL}" > OWNER_EMAIL
                        echo "\${BUILD_USER_EMAIL}" | awk -F '@' '{print \$1}' > OWNER_FULL
                        echo "pmm-kubernetes-cluster-\$(cat OWNER_FULL)-\$(date -u '+%Y%m%d%H%M%S')-${BUILD_NUMBER}" \
                            > VM_NAME
                    """
                }
                script {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER_FULL").trim()
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')

                    if ("${NOTIFY}" == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                    }
                }
            }
        }

        stage('Run VM') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        export VM_NAME=\$(cat VM_NAME)
                        export OWNER=\$(cat OWNER_FULL)
                        export SUBNET=\$(
                            aws ec2 describe-subnets \
                                --region us-east-2 \
                                --output text \
                                --filters "Name=tag:aws:cloudformation:stack-name,Values=pmm-staging" \
                                --query 'Subnets[].SubnetId' \
                                | tr '\t' '\n' \
                                | sort --random-sort \
                                | head -1
                        )
                        export SG1=\$(
                            aws ec2 describe-security-groups \
                                --region us-east-2 \
                                --output text \
                                --filters "Name=tag:aws:cloudformation:stack-name,Values=pmm-staging" \
                                          "Name=group-name,Values=HTTP" \
                                --query 'SecurityGroups[].GroupId'
                        )
                        export SG2=\$(
                            aws ec2 describe-security-groups \
                                --region us-east-2 \
                                --output text \
                                --filters "Name=tag:aws:cloudformation:stack-name,Values=pmm-staging" \
                                          "Name=group-name,Values=SSH" \
                                --query 'SecurityGroups[].GroupId'
                        )

                        echo '{
                            "DryRun": false,
                            "InstanceCount": 1,
                            "InstanceInterruptionBehavior": "terminate",
                            "LaunchSpecification": {
                                "BlockDeviceMappings": [
                                    {
                                        "DeviceName": "/dev/xvda",
                                        "Ebs": {
                                            "DeleteOnTermination": true,
                                            "VolumeSize": 50,
                                            "VolumeType": "gp2"
                                        }
                                    }
                                ],
                                "EbsOptimized": false,
                                "ImageId": "ami-0a0ad6b70e61be944",
                                "UserData": "c3VkbyB5dW0gaW5zdGFsbCAteSBqYXZhLTEuOC4wLW9wZW5qZGsKCnN1ZG8gL3Vzci9zYmluL2FsdGVybmF0aXZlcyAtLXNldCBqYXZhIC91c3IvbGliL2p2bS9qcmUtMS44LjAtb3Blbmpkay54ODZfNjQvYmluL2phdmEKCnN1ZG8gL3Vzci9zYmluL2FsdGVybmF0aXZlcyAtLXNldCBqYXZhYyAvdXNyL2xpYi9qdm0vanJlLTEuOC4wLW9wZW5qZGsueDg2XzY0L2Jpbi9qYXZhYwoKc3VkbyB5dW0gcmVtb3ZlIGphdmEtMS43Cg==",
                                "InstanceType": "c4.4xlarge",
                                "KeyName": "jenkins",
                                "Monitoring": {
                                    "Enabled": false
                                },
                                "IamInstanceProfile": {
                                    "Name": "jenkins-pmm-slave"
                                },
                                "SecurityGroupIds": [
                                    "security-group-id-1",
                                    "security-group-id-2"
                                ],
                                "SubnetId": "subnet-id"
                            },
                            "SpotPrice": "0.1448",
                            "Type": "persistent"
                        }' \
                            | sed -e "s/subnet-id/\${SUBNET}/" \
                            | sed -e "s/security-group-id-1/\${SG1}/" \
                            | sed -e "s/security-group-id-2/\${SG2}/" \
                            > config.json

                        REQUEST_ID=\$(
                            aws ec2 request-spot-instances \
                                --output text \
                                --region us-east-2 \
                                --cli-input-json file://config.json \
                                --query SpotInstanceRequests[].SpotInstanceRequestId
                        )
                        echo \$REQUEST_ID > REQUEST_ID

                        until [ -s IP ]; do
                            sleep 1
                            aws ec2 describe-instances \
                                --filters "Name=spot-instance-request-id,Values=\${REQUEST_ID}" \
                                --query 'Reservations[].Instances[].PublicIpAddress' \
                                --output text \
                                --region us-east-2 \
                                | tee IP
                        done

                        aws ec2 describe-instances \
                            --filters "Name=spot-instance-request-id,Values=\${REQUEST_ID}" \
                            --query 'Reservations[].Instances[].InstanceId' \
                            --output text \
                            --region us-east-2 \
                            | tee ID

                        VOLUMES=$(
                            aws ec2 describe-instances \
                                --region us-east-2 \
                                --output text \
                                --instance-ids \$(cat ID) \
                                --query 'Reservations[].Instances[].BlockDeviceMappings[].Ebs.VolumeId'
                        )

                        aws ec2 create-tags  \
                            --region us-east-2 \
                            --resources \$REQUEST_ID \$(cat ID) \$VOLUMES \
                            --tags Key=Name,Value=\$VM_NAME \
                                   Key=iit-billing-tag,Value=pmm-staging \
                                   Key=stop-after-days,Value=${DAYS} \
                                   Key=owner,Value=\$OWNER
                    '''
                }
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) 'java -version; sudo yum install -y java-1.8.0-openjdk; sudo /usr/sbin/alternatives --set java /usr/lib/jvm/jre-1.8.0-openjdk.x86_64/bin/java; java -version;' ; do
                            sleep 5
                        done
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
                        sudo mkdir -p /srv/pmm-qa || :
                        pushd /srv/pmm-qa
                            sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                            sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                        popd
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
        stage('Setup Minikube') {
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        sh """
                        export IP=\$(cat IP)
                        export VM_NAME=\$(cat VM_NAME)
                        """
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                sudo yum -y install curl
                                sudo curl -Lo /usr/local/sbin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
                                sudo chmod +x /usr/local/sbin/minikube
                                sudo ln -s /usr/local/sbin/minikube /usr/sbin/minikube
                                alias kubectl='minikube kubectl --'
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
                        sh """
                            export IP=\$(cat IP)
                            export VM_NAME=\$(cat VM_NAME)
                        """
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                export PATH=\$PATH:/usr/sbin
                                minikube version
                                minikube config set cpus 8
                                minikube config set memory 29000
                                minikube config set kubernetes-version 1.16.15
                                export CHANGE_MINIKUBE_NONE_USER=true
                                minikube start --driver=none
                                sudo chown -R $USER $HOME/.kube $HOME/.minikube
                                sed -i s:/root:$HOME:g $HOME/.kube/config
                                bash /srv/pmm-qa/pmm-tests/minikube_operators_setup.sh
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
                        sh """
                            export IP=\$(cat IP)
                            export VM_NAME=\$(cat VM_NAME)
                        """
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                export PATH=\$PATH:/usr/sbin
                                minikube kubectl -- get nodes
                                minikube kubectl -- get pods
                                minikube kubectl -- wait --for=condition=Available deployment percona-xtradb-cluster-operator
                                minikube kubectl -- wait --for=condition=Available deployment percona-server-mongodb-operator
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
                        sh """
                            export IP=\$(cat IP)
                            export VM_NAME=\$(cat VM_NAME)
                        """
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                export PATH=\$PATH:/usr/sbin
                                minikube kubectl -- config view --flatten --minify > kubeconfig.yml
                            """
                        }
                        withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                            sh """
                                scp -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                                    ${USER}@${IP}:workspace/kubernetes-cluster-staging/kubeconfig.yml \
                                    kubeconfig.yml
                            """
                        }
                        stash includes: 'kubeconfig.yml', name: 'kubeconfig'
                        archiveArtifacts 'kubeconfig.yml'
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
                    def PUBLIC_IP = sh(returnStdout: true, script: "cat IP").trim()
                    def OWNER_FULL = sh(returnStdout: true, script: "cat OWNER_FULL").trim()
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')

                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, owner: @${OWNER_FULL}, Cluster IP: ${PUBLIC_IP}"
                    slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - Cluster IP: ${PUBLIC_IP}"
                }
            }
        }
        failure {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
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

                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed, owner: @${OWNER_FULL}"
                    slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                }
            }
        }
    }
}