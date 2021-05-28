pipeline {
    agent {
        label 'awscli'
    }
    parameters {
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
        string(
            defaultValue: 'master',
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
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                withCredentials([usernamePassword(credentialsId: 'Jenkins API', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        curl -s -u ${USER}:${PASS} ${BUILD_URL}api/json \
                            | python -c "import sys, json; print json.load(sys.stdin)['actions'][1]['causes'][0]['userId']" \
                            | sed -e 's/@percona.com//' \
                            > OWNER
                        echo "pmm-\$(cat OWNER | cut -d . -f 1)-\$(date -u '+%Y%m%d%H%M')" \
                            > VM_NAME
                    """
                }
                script {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()
                    echo """
                        AMI Image ID: ${AMI_ID}
                        OWNER:          ${OWNER}
                    """
                    if ("${NOTIFY}" == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        slackSend botUser: true, channel: "@${OWNER}", color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                    }
                }
            }
        }
        stage('Run VM with PMM server') {
            steps 
            {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID',  credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        export VM_NAME=\$(cat VM_NAME)
                        export OWNER=\$(cat OWNER)
                        export AWS_DEFAULT_REGION=us-east-1
                        
                        export SG1=\$(
                            aws ec2 describe-security-groups \
                                --region us-east-1 \
                                --output text \
                                --filters "Name=group-name,Values=pmm" \
                                --query 'SecurityGroups[].GroupId'
                        )
                        export SG2=\$(
                            aws ec2 describe-security-groups \
                                --region us-east-1 \
                                --output text \
                                --filters "Name=group-name,Values=SSH" \
                                --query 'SecurityGroups[].GroupId'
                        )

                        export SS1=\$(
                            aws ec2 describe-subnets \
                                --region us-east-1 \
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
                        aws ec2 describe-key-pairs --key-name nailya

                        INSTANCE_ID=\$(
                            aws ec2 run-instances \
                            --image-id $AMI_ID \
                            --security-group-ids $SG1 $SG2\
                            --instance-type t2.large \
                            --subnet-id $SS1 \
                            --region us-east-1 \
                            --key-name jenkins-admin \
                            --query Instances[].InstanceId \
                            --output text
                        )

                        echo \$INSTANCE_ID > INSTANCE_ID

                        INSTANCE_NAME=\$VM_NAME
                            aws ec2 create-tags  \
                            --resources $INSTANCE_ID \
                            --region us-east-1 \
                            --tags Key=Name,Value=$INSTANCE_NAME \
                            Key=iit-billing-tag,Value=qa \
                            Key=stop-after-days,Value=${DAYS}
                           
                        echo "INSTANCE_NAME: $INSTANCE_NAME"
                        
                        

                       IP_PUBLIC=\$(
                              aws ec2 describe-instances \
                              --instance-ids $INSTANCE_ID \
                              --region us-east-1 \
                              --output text \
                              --query 'Reservations[].Instances[].PublicIpAddress' \
                             | tee IP
                             )
                        
                        echo \$IP_PUBLIC > IP_PUBLIC
                         

                         IP_PRIVATE=\$(
                            aws ec2 describe-instances \
                              --instance-ids $INSTANCE_ID \
                              --region us-east-1 \
                              --output text \
                              --query 'Reservations[].Instances[].PrivateIpAddress' \
                             | tee IP
                             )

                        echo \$IP_PRIVATE > IP_PRIVATE
                    '''
                }
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@\$(cat IP_PUBLIC) date; do
                            sleep 1
                        done

                        if [ -n "$SSH_KEY" ]; then
                            echo '$SSH_KEY' | ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@\$(cat IP_PUBLIC) 'cat - >> .ssh/authorized_keys'
                        fi
                        sleep 60
                    """
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@\$(cat IP_PUBLIC) "
                            set -o errexit
                            set -o xtrace
                            [ ! -d "/home/centos" ] && echo "Home directory for centos user does not exist"
                            sudo yum -y install git svn sysbench
                            sudo mkdir -p /srv/pmm-qa || :
                            pushd /srv/pmm-qa
                                sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                                sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                                sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/get_download_link.sh
                                sudo chmod 755 get_download_link.sh
                            popd
                        "
                    """
                }
                script {
                    env.IP_PRIVATE  = sh(returnStdout: true, script: "cat IP_PRIVATE").trim()
                    env.IP  = sh(returnStdout: true, script: "cat IP_PUBLIC").trim()
                    env.INSTANCE_ID  = sh(returnStdout: true, script: "cat INSTANCE_ID").trim()
                    env.VM_NAME = sh(returnStdout: true, script: "cat VM_NAME").trim()
                }
                archiveArtifacts 'IP'  
                archiveArtifacts 'INSTANCE_ID' 
            }
        }
        stage('Enable Testing Repo') {
            when {
                expression { env.ENABLE_TESTING_REPO == "yes" }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@\$(cat IP_PUBLIC) '
                            sudo sed -i'' -e 's^/release/^/testing/^' /etc/yum.repos.d/pmm2-server.repo
                            sudo percona-release enable original testing
                            sudo yum clean all
                        '
                    """
                }
            }
        }
        stage('Enable Experimental Repo') {
            when {
                expression { env.ENABLE_EXPERIMENTAL_REPO == "yes" && env.ENABLE_TESTING_REPO == "no" }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@\$(cat IP_PUBLIC) '
                            sudo sed -i'' -e 's^/release/^/experimental/^' /etc/yum.repos.d/pmm2-server.repo
                            sudo percona-release enable original experimental
                            sudo yum clean all
                        '
                    """
                }
            }
        }
    }
    post {
        success {
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, owner: @${OWNER} - https://${IP} Instance ID: ${INSTANCE_ID}"
                    slackSend botUser: true, channel: "@${OWNER}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${IP} Instance ID: ${INSTANCE_ID}"
                }
            }
        }
        failure {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                    export INSTANCE_ID=\$(cat INSTANCE_ID)
                    
                    if [ -n "$INSTANCE_ID" ]; then
                      aws ec2 --region us-east-1 terminate-instances --instance-ids \$INSTANCE_ID
                    fi
                '''
            }
            script {
                if ("${NOTIFY}" == "false") {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed, owner: @${OWNER}"
                    slackSend botUser: true, channel: "@${OWNER}", color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                }
            }
        }
    }
}
