pipeline {
    agent {
        label 'awscli'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version ("dev-latest" for master branch, "latest" or "X.X.X" for released version, "http://..." for feature build)',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
            name: 'SSH_KEY')
        choice(
            choices: '7\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n21\n22\n23\n24\n25\n26\n27\n28\n29\n30',
            description: 'Stop the instance after, days ("0" value disables autostop and recreates instance in case of AWS failure)',
            name: 'DAYS')
        string(
            defaultValue: '5.7',
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        string(
            defaultValue: '5.7',
            description: 'Percona Server for MySQL version',
            name: 'PS_VERSION')
        string(
            defaultValue: '8.0',
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        string(
            defaultValue: '10.5',
            description: 'Postgre SQL Server version',
            name: 'PGSQL_VERSION')
        string(
            defaultValue: '10.2',
            description: 'MariaDB Server version',
            name: 'MD_VERSION')
        string(
            defaultValue: '3.6',
            description: 'Percona Server for MongoDB version',
            name: 'MO_VERSION')
        string(
            defaultValue: '--addclient=ps,1',
            description: 'Configure PMM Clients. ps - Percona Server for MySQL, pxc - Percona XtraDB Cluster, ms - MySQL Community Server, md - MariaDB Server, MO - Percona Server for MongoDB, pgsql - Postgre SQL Server',
            name: 'CLIENTS')
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
                        DOCKER_VERSION: ${DOCKER_VERSION}
                        CLIENT_VERSION: ${CLIENT_VERSION}
                        PXC_VERSION:    ${PXC_VERSION}
                        PS_VERSION:     ${PS_VERSION}
                        MS_VERSION:     ${MS_VERSION}
                        MD_VERSION:     ${MD_VERSION}
                        MO_VERSION:     ${MO_VERSION}
                        PGSQL_VERSION:  ${PGSQL_VERSION}
                        CLIENTS:        ${CLIENTS}
                        OWNER:          ${OWNER}
                    """
                    if ("${NOTIFY}" == "true") {
                        slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        slackSend channel: "@${OWNER}", color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                    }
                }
            }
        }

        stage('Run VM') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        export VM_NAME=\$(cat VM_NAME)
                        export OWNER=\$(cat OWNER)
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
                                            "VolumeSize": 20,
                                            "VolumeType": "gp2"
                                        }
                                    }
                                ],
                                "EbsOptimized": false,
                                "ImageId": "ami-15e9c770",
                                "InstanceType": "m4.large",
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
                            "SpotPrice": "0.035",
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
                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) date; do
                            sleep 1
                        done

                        if [ -n "$SSH_KEY" ]; then
                            echo '$SSH_KEY' | ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) 'cat - >> .ssh/authorized_keys'
                        fi

                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) "
                            set -o errexit
                            set -o xtrace

                            echo "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC3q+CcD0UcPx3g87WB59d+Mn5OAS1LbjUXlckNn+B0iexF8tsM40i0oeoJ/KJqiGqC8+7Shz8rODcKSCpSCQwvUAdBlUXeZZ1roinHAx9CtzxJSSiRhFGlCT55aKFW6E+CEIoz6gvOpCbCx/x/sXo6QcA18Lv0JkAtB5gJu45Sog2BWcja2Qjty5+Ltl7POipQtNRKTmFGYcv6Gx3yJM2cBNGnRM64AjOL3IrgP08XKY7LKN+Z2zWUjpf72pPRBuHqudf8xzWIdfS7HKFnuWtqK8zlAvPrZ18JXxlcUqD5Nqo8ajd5SQvT4tHt9cf1kzz5zbxZlPCnrlQJthxycI6B KD" >> .ssh/authorized_keys
                            echo "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDHwTZyQ0H5JtFByPwOUV9oX45dAr5FsDOOlFmbNc1FVE3lvwVn1ZoMzvooIGfcix6/9RxF59GPmjnN/B52mMjiJ2M5fKNBsRjHizZhe4C7I4EuU8YCOvMLGROSr5c9vevCYjmjN9isUtP+e3F9XeZ1f4IWX7YU8qOpKwbYheNiXcySv8sXIHa8gDU85obgNbDl1oeNa1XpWIpJiP4Tl+9OH/ge/0Z7z3XYe2HvmsOPU8MykQH2jg3g/pxA9yudCYjvYZsxhyoSuvE1jcs0hb1smkCQrarXG8iFjRME0PxWe0J114PS0ddCfG5NtHuARqVVo32CLjqDynh6y6etLjdt MM" >> .ssh/authorized_keys


                            sudo yum -y update --security
                            sudo yum -y install http://www.percona.com/downloads/percona-release/redhat/0.1-4/percona-release-0.1-4.noarch.rpm
                            sudo yum -y install svn docker sysbench mysql
                            sudo yum -y install bats --enablerepo=epel
                            sudo usermod -aG docker ec2-user
                            sudo service docker start

                            sudo mkdir -p /srv/percona-qa || :
                            pushd /srv/percona-qa
                                sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/pmm-tests
                                sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/get_download_link.sh
                                sudo chmod 755 get_download_link.sh
                            popd
                        "
                    """
                }
                script {
                    env.IP      = sh(returnStdout: true, script: "cat IP").trim()
                    env.VM_NAME = sh(returnStdout: true, script: "cat VM_NAME").trim()
                }
                archiveArtifacts 'IP'
            }
        }

        stage('Run Docker') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        export IP=\$(cat IP)
                        export VM_NAME=\$(cat VM_NAME)

                        export CLIENT_VERSION=${CLIENT_VERSION}
                        if [[ \$CLIENT_VERSION = latest ]]; then
                            CLIENT_VERSION=\$(
                                curl -s https://www.percona.com/downloads/pmm/ \
                                    | egrep -o 'pmm/[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}' \
                                    | sed -e 's/pmm\\///' \
                                    | sort -u -V \
                                    | tail -1
                            )
                        fi

                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) "
                            set -o errexit
                            set -o xtrace

                            docker create \
                                -v /opt/prometheus/data \
                                -v /opt/consul-data \
                                -v /var/lib/mysql \
                                -v /var/lib/grafana \
                                --name \${VM_NAME}-data \
                                ${DOCKER_VERSION} /bin/true

                            docker run -d \
                                -p 80:80 \
                                -p 443:443 \
                                --volumes-from \${VM_NAME}-data \
                                --name \${VM_NAME}-server \
                                --restart always \
                                -e METRICS_RESOLUTION=5s \
                                ${DOCKER_VERSION}

                            if [[ \$CLIENT_VERSION = dev-latest ]]; then
                                sudo yum -y install pmm-client --enablerepo=percona-testing-*
                            else
                                if [[ \$CLIENT_VERSION == http* ]]; then
                                    wget -O pmm-client.tar.gz --progress=dot:giga "\${CLIENT_VERSION}"
                                else
                                    wget -O pmm-client.tar.gz --progress=dot:giga "https://www.percona.com/downloads/pmm-client/pmm-client-\${CLIENT_VERSION}/binary/tarball/pmm-client-\${CLIENT_VERSION}.tar.gz"
                                fi
                                tar -zxpf pmm-client.tar.gz
                                pushd pmm-client-*
                                    sudo ./install
                                popd
                            fi

                            sleep 10
                            docker logs \${VM_NAME}-server

                            export PATH=\$PATH:/usr/sbin:/sbin
                            sudo pmm-admin config --client-name pmm-client-hostname --server \\\$(ip addr show eth0 | grep 'inet ' | awk '{print\\\$2}' | cut -d '/' -f 1)
                        "
                    """
                }
            }
        }

        stage('Run Clients') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        [ -z "${CLIENTS}" ] && exit 0 || :
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) "
                            set -o errexit
                            set -o xtrace

                            export PATH=\$PATH:/usr/sbin
                            test -f /usr/lib64/libsasl2.so.2 || sudo ln -s /usr/lib64/libsasl2.so.3.0.0 /usr/lib64/libsasl2.so.2

                            bash /srv/percona-qa/pmm-tests/pmm-framework.sh \
                                --pxc-version ${PXC_VERSION} \
                                --ps-version  ${PS_VERSION} \
                                --ms-version  ${MS_VERSION} \
                                --md-version  ${MD_VERSION} \
                                --mo-version  ${MO_VERSION} \
                                --pgsql-version ${PGSQL_VERSION} \
                                --download \
                                ${CLIENTS} \
                                --sysbench-data-load \
                                --sysbench-oltp-run
                        "
                    """
                }
            }
        }

    }

    post {
        success {
            script {
                if ("${NOTIFY}" == "true") {
                    def PUBLIC_IP = sh(returnStdout: true, script: "cat IP").trim()
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, owner: @${OWNER}, link: https://${PUBLIC_IP}"
                    slackSend channel: "@${OWNER}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${PUBLIC_IP}"
                }
            }
        }
        failure {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                    export REQUEST_ID=\$(cat REQUEST_ID)
                    if [ -n "$REQUEST_ID" ]; then
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids \$REQUEST_ID
                        aws ec2 --region us-east-2 terminate-instances --instance-ids \$(cat ID)
                    fi
                '''
            }
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed, owner: @${OWNER}"
                    slackSend channel: "@${OWNER}", color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                }
            }
        }
    }
}
