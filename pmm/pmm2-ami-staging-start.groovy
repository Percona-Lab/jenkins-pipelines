String OWNER = ''
String OWNER_SLACK = ''

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH')
        string(
            defaultValue: '',
            description: 'AMI Image version',
            name: 'AMI_ID')
        choice(
            choices: '1\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16',
            description: 'Stop the instance after, days ("0" value disables autostop and recreates instance in case of AWS failure)',
            name: 'DAYS')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo, for RC testing',
            name: 'ENABLE_TESTING_REPO')
        choice(
            choices: ['yes', 'no'],
            description: 'Enable Experimental Repo, for dev-latest',
            name: 'ENABLE_EXPERIMENTAL_REPO')
        choice(
            choices: ['false', 'true'],
            description: 'Enable to setup Docker-compose for remote instances',
            name: 'AMI_UPGRADE_TESTING_INSTANCE')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for pmm-qa branch',
            name: 'PMM_QA_GIT_COMMIT_HASH')
        string(
            defaultValue: '',
            description: 'public ssh key for "admin" user, please set if you need ssh access',
            name: 'SSH_KEY')
        choice(
            choices: ['true', 'false'],
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                script {
                    wrap([$class: 'BuildUser']) {
                        OWNER = (env.BUILD_USER_EMAIL ?: '').split('@')[0] ?: env.BUILD_USER_ID
                        OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: env.BUILD_USER_EMAIL, tokenCredentialId: 'JenkinsCI-SlackBot-v2')
                        env.VM_NAME = 'pmm-' + OWNER.replaceAll("[^a-zA-Z0-9_.-]", "") + '-' + (new Date()).format("yyyyMMdd.HHmmss") + '-' + env.BUILD_NUMBER
                    }

                    echo """
                        AMI Image ID: ${AMI_ID}
                        OWNER:          ${OWNER}
                    """

                    if (params.NOTIFY == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        if (OWNER_SLACK) {
                            slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        }
                    }
                }
            }
        }
        stage('Run VM with PMM server') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID',  credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        # TODO: see if this envvar is sufficient for every awscli call down below
                        export AWS_DEFAULT_REGION=us-east-1

                        export SG1=$(
                            aws ec2 describe-security-groups \
                                --region $AWS_DEFAULT_REGION \
                                --output text \
                                --filters "Name=group-name,Values=pmm" \
                                --query 'SecurityGroups[].GroupId'
                        )
                        export SG2=$(
                            aws ec2 describe-security-groups \
                                --region $AWS_DEFAULT_REGION \
                                --output text \
                                --filters "Name=group-name,Values=SSH" \
                                --query 'SecurityGroups[].GroupId'
                        )

                        export SS1=$(
                            aws ec2 describe-subnets \
                                --region $AWS_DEFAULT_REGION \
                                --output text \
                                --query 'Subnets[].SubnetId' \
                                --filter 'Name=tag-value,Values=pmm2-ami-staging-start'
                        )

                        IMAGE_NAME=$(
                            aws ec2 describe-images \
                                --image-ids $AMI_ID \
                                --query 'Images[].Name' \
                                --output text
                        )
                        echo "IMAGE_NAME:    $IMAGE_NAME"

                        # The default value of the EBS volume's `DeleteOnTermination` is set to `false`,
                        # which leaves out unused volumes after instances get shut down.
                        INSTANCE_ID=$(
                            aws ec2 run-instances \
                                --image-id $AMI_ID \
                                --security-group-ids $SG1 $SG2\
                                --instance-type t2.large \
                                --subnet-id $SS1 \
                                --region $AWS_DEFAULT_REGION \
                                --key-name jenkins-admin \
                                --query Instances[].InstanceId \
                                --block-device-mappings \
                                '[{ "DeviceName": "/dev/sdb","Ebs": {"DeleteOnTermination": true} }]' \
                                --output text \
                                | tee INSTANCE_ID
                        )

                        echo "INSTANCE_ID: $INSTANCE_ID"

                        aws ec2 create-tags  \
                            --resources $INSTANCE_ID \
                            --region $AWS_DEFAULT_REGION \
                            --tags Key=Name,Value=${VM_NAME} \
                            Key=iit-billing-tag,Value=qa \
                            Key=stop-after-days,Value=${DAYS}


                        aws ec2 describe-instances \
                            --instance-ids $INSTANCE_ID \
                            --region $AWS_DEFAULT_REGION \
                            --output text \
                            --query 'Reservations[].Instances[].PublicIpAddress' \
                            | tee PUBLIC_IP

                        echo "PUBLIC_IP: $(cat PUBLIC_IP)"


                        aws ec2 describe-instances \
                            --instance-ids $INSTANCE_ID \
                            --region $AWS_DEFAULT_REGION \
                            --output text \
                            --query 'Reservations[].Instances[].PrivateIpAddress' \
                            | tee PRIVATE_IP
                        
                        # wait for the instance to get ready
                        aws ec2 wait instance-running \
                            --instance-ids $INSTANCE_ID
                    '''
                }
                script {
                    env.PRIVATE_IP  = sh(returnStdout: true, script: "cat PRIVATE_IP").trim()
                    env.PUBLIC_IP  = sh(returnStdout: true, script: "cat PUBLIC_IP").trim()
                    env.INSTANCE_ID  = sh(returnStdout: true, script: "cat INSTANCE_ID").trim()
                    currentBuild.description = "IP: ${env.PUBLIC_IP} NAME: ${env.VM_NAME}, ID: ${env.INSTANCE_ID}"
                }
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} date; do
                            sleep 5
                        done

                        if [ -n "$SSH_KEY" ]; then
                            echo "$SSH_KEY" | ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} 'cat - >> .ssh/authorized_keys'
                        fi
                    '''
                    sh '''
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} "
                            set -o errexit
                            set -o xtrace
                            [ ! -d "/home/centos" ] && echo "Home directory for centos user does not exist"
                            echo "exclude=mirror.es.its.nyu.edu" | sudo tee -a /etc/yum/pluginconf.d/fastestmirror.conf
                            sudo yum makecache
                            sudo yum -y install git svn docker
                            sudo systemctl start docker
                            curl -L -s https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 | sudo tee /usr/bin/docker-compose > /dev/null
                            sudo chmod +x /usr/bin/docker-compose
                            sudo mkdir -p /srv/pmm-qa || :
                            pushd /srv/pmm-qa
                                sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                                sudo git checkout ${PMM_QA_GIT_COMMIT_HASH}
                                sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/get_download_link.sh
                                sudo chmod 755 get_download_link.sh
                            popd
                        "
                    '''
                }
                archiveArtifacts 'PUBLIC_IP'
                archiveArtifacts 'INSTANCE_ID'
            }
        }
        stage('Upgrade workaround for nginx package') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} "
                            sudo sed -i 's/- nginx/- "nginx*"/' /usr/share/pmm-update/ansible/playbook/tasks/update.yml
                        "
                    '''
                }
            }
        }
        stage('Enable Testing Repo') {
            when {
                expression { env.ENABLE_TESTING_REPO == "yes" }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} '
                            sudo yum update -y percona-release
                            sudo sed -i'' -e 's^/release/^/testing/^' /etc/yum.repos.d/pmm2-server.repo
                            sudo percona-release enable percona testing
                            sudo yum clean all
                        '
                    '''
                }
            }
        }
        stage('Enable Experimental Repo') {
            when {
                expression { env.ENABLE_EXPERIMENTAL_REPO == "yes" && env.ENABLE_TESTING_REPO == "no" }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} '
                            sudo yum update -y percona-release
                            sudo sed -i'' -e 's^/release/^/experimental/^' /etc/yum.repos.d/pmm2-server.repo
                            sudo percona-release enable percona experimental
                            sudo yum clean all
                        '
                    '''
                }
            }
        }
        stage('Setup DBs for Remote Verification') {
            when {
                expression { env.AMI_UPGRADE_TESTING_INSTANCE.toBoolean() }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${PUBLIC_IP} "
                            sudo git clone --single-branch --branch ${GIT_BRANCH} https://github.com/percona/pmm-ui-tests.git
                            cd pmm-ui-tests
                            sudo PWD=$(pwd) docker-compose up -d mysql
                            sudo PWD=$(pwd) docker-compose up -d mongo
                            sudo PWD=$(pwd) docker-compose up -d postgres
                            sudo PWD=$(pwd) docker-compose up -d proxysql
                            sleep 30
                            sudo bash -x testdata/db_setup.sh
                        "
                    '''
                }
            }
        }
    }
    post {
        success {
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, 
                        channel: '#pmm-ci', 
                        color: '#00FF00', 
                        message: "[${JOB_NAME}]: build ${BUILD_URL} finished, owner: @${OWNER} - https://${PUBLIC_IP}, Instance ID: ${INSTANCE_ID}"
                    if (OWNER_SLACK) {
                        slackSend botUser: true, 
                            channel: "@${OWNER_SLACK}", 
                            color: '#00FF00', 
                            message: "[${JOB_NAME}]: build ${BUILD_URL} finished - https://${PUBLIC_IP}, Instance ID: ${INSTANCE_ID}"
                    }
                }
            }
        }
        failure {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                    if [ -n "${INSTANCE_ID}" ]; then
                        aws ec2 --region us-east-1 terminate-instances --instance-ids ${INSTANCE_ID}
                    fi
                '''
            }
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${BUILD_URL} failed, owner: @${OWNER}"
                    if (OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build ${BUILD_URL} failed"
                    }
                }
            }
        }
    }
}
