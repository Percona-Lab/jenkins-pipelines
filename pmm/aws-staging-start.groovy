pipeline {
    agent {
        label 'awscli'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag ex. perconalab/pmm-server:dev-latest or perconalab/pmm-server:pmm1-dev-latest)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version ("dev-latest" for master branch, "pmm1-dev-latest" for 1.x latest, "latest" or "X.X.X" for released version, "http://..." for feature build)',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
            name: 'SSH_KEY')
        choice(
            choices: ['pmm2', 'pmm1'],
            description: 'Which Version of PMM-Server',
            name: 'PMM_VERSION')
        choice(
            choices: '7\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n21\n22\n23\n24\n25\n26\n27\n28\n29\n30',
            description: 'Stop the instance after, days ("0" value disables autostop and recreates instance in case of AWS failure)',
            name: 'DAYS')
        string(
            defaultValue: '5.7',
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        choice(
            choices: ['5.7', '8.0'],
            description: "Percona Server for MySQL version",
            name: 'PS_VERSION')
        choice(
            choices: ['5.7', '8.0'],
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        choice(
            choices: ['10.8', '11', '9.6'],
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION')
        string(
            defaultValue: '10.2',
            description: 'MariaDB Server version',
            name: 'MD_VERSION')
        choice(
            choices: ['4.0', '3.6'],
            description: "Percona Server for MongoDB version",
            name: 'MO_VERSION')
        choice(
            choices: ['4.2', '4.0'],
            description: "Official MongoDB version from MongoDB Inc",
            name: 'MODB_VERSION')
        choice(
            choices: ['perfschema', 'slowlog'],
            description: "Query Source for Monitoring",
            name: 'QUERY_SOURCE')
        text(
            defaultValue: '--addclient=ps,1',
            description: '''
            Configure PMM Clients
            ms - MySQL (ex. --addclient=ms,1),
            ps - Percona Server for MySQL (ex. --addclient=ps,1),
            pxc - Percona XtraDB Cluster, --with-proxysql (to be used with proxysql only ex. --addclient=pxc,1 --with-proxysql),
            md - MariaDB Server (ex. --addclient=md,1),
            mo - Percona Server for MongoDB(ex. --addclient=mo,1),
            modb - Official MongoDB version from MongoDB Inc (ex. --addclient=modb,1),
            pgsql - Postgre SQL Server (ex. --addclient=pgsql,1)
            An example: --addclient=ps,1 --addclient=mo,1 --addclient=md,1 --addclient=pgsql,2 --addclient=modb,2
            ''',
            name: 'CLIENTS')
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
    }
    options {
        skipDefaultCheckout()
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
                        PMM_VERSION:    ${PMM_VERSION}
                        PXC_VERSION:    ${PXC_VERSION}
                        PS_VERSION:     ${PS_VERSION}
                        MS_VERSION:     ${MS_VERSION}
                        MD_VERSION:     ${MD_VERSION}
                        MO_VERSION:     ${MO_VERSION}
                        MODB_VERSION:   ${MODB_VERSION}
                        PGSQL_VERSION:  ${PGSQL_VERSION}
                        QUERY_SOURCE:   ${QUERY_SOURCE}
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

                            sudo yum -y update --security
                            sudo yum -y install https://repo.percona.com/yum/percona-release-0.1-7.noarch.rpm
                            sudo rpm --import /etc/pki/rpm-gpg/PERCONA-PACKAGING-KEY
                            sudo yum -y install svn docker sysbench mysql57-server
                            sudo service mysqld start
                            sudo yum -y install bats --enablerepo=epel
                            sudo usermod -aG docker ec2-user
                            sudo service docker start

                            sudo mkdir -p /srv/pmm-qa || :
                            pushd /srv/pmm-qa
                                sudo svn export https://github.com/percona/pmm-qa.git/trunk/pmm-tests
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
                    script {
                        withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
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

                                if [[ \$PMM_VERSION == pmm2 ]]; then
                                    docker create \
                                        -v /srv \
                                        --name \${VM_NAME}-data \
                                        ${DOCKER_VERSION} /bin/true

                                    docker run -d \
                                        -p 80:80 \
                                        -p 443:443 \
                                        --volumes-from \${VM_NAME}-data \
                                        --name \${VM_NAME}-server \
                                        --restart always \
                                        ${DOCKER_VERSION}
                                else
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
                                fi
                                sleep 10
                                docker logs \${VM_NAME}-server

                                if [[ \$CLIENT_VERSION = dev-latest ]]; then
                                    sudo yum -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm
                                    sudo percona-release disable all
                                    sudo percona-release enable original testing
                                    sudo yum -y install pmm2-client
                                    sudo yum -y update
                                elif [[ \$CLIENT_VERSION = pmm1-dev-latest ]]; then
                                    sudo yum -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm
                                    sudo percona-release disable all
                                    sudo percona-release enable original testing
                                    sudo yum -y install pmm-client
                                    sudo yum -y update
                                else
                                    if [[ \$PMM_VERSION == pmm1 ]]; then
                                         if [[ \$CLIENT_VERSION == http* ]]; then
                                            wget -O pmm-client.tar.gz --progress=dot:giga "\${CLIENT_VERSION}"
                                        else
                                            wget -O pmm-client.tar.gz --progress=dot:giga "https://www.percona.com/downloads/pmm-client/pmm-client-\${CLIENT_VERSION}/binary/tarball/pmm-client-\${CLIENT_VERSION}.tar.gz"
                                        fi
                                        tar -zxpf pmm-client.tar.gz
                                        pushd pmm-client-*
                                            sudo ./install
                                        popd
                                    else
                                        if [[ \$CLIENT_VERSION == http* ]]; then
                                            wget -O pmm2-client.tar.gz --progress=dot:giga "\${CLIENT_VERSION}"
                                        else
                                            wget -O pmm2-client.tar.gz --progress=dot:giga "https://www.percona.com/downloads/pmm2-client/pmm2-client-\${CLIENT_VERSION}/binary/tarball/pmm2-client-\${CLIENT_VERSION}.tar.gz"
                                        fi
                                        export BUILD_ID=dear-jenkins-please-dont-kill-virtualbox
                                        export JENKINS_NODE_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                                        export JENKINS_SERVER_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                                        tar -zxpf pmm2-client.tar.gz
                                        rm -r pmm2-client.tar.gz
                                        export PMM_CLIENT_BASEDIR=\\\$(ls -1td pmm2-client-* 2>/dev/null | grep -v ".tar" | head -n1)
                                        export PATH="$PWD/pmm2-client-2.0.0/bin:$PATH"
                                        echo "export PATH=$PWD/pmm2-client-2.0.0/bin:$PATH" >> ~/.bash_profile
                                        source ~/.bash_profile
                                        pmm-admin --version
                                        cat pmm-agent.log
                                        pmm-agent setup --config-file=$PWD/pmm2-client-2.0.0/config/pmm-agent.yaml --server-address=\$IP:443 --server-insecure-tls --server-username=admin --server-password=admin --trace
                                        sleep 10
                                        JENKINS_NODE_COOKIE=dontKillMe nohup bash -c 'pmm-agent --config-file=$PWD/pmm2-client-2.0.0/config/pmm-agent.yaml > pmm-agent.log 2>&1 &'
                                        sleep 10
                                        cat pmm-agent.log
                                        pmm-admin status
                                    fi
                                fi
                            export PATH=\$PATH:/usr/sbin:/sbin
                            if [[ \$PMM_VERSION == pmm2 ]]; then
                                if [[ \$CLIENT_VERSION != http* ]]; then
                                    pmm-admin --version
                                    sudo cat /var/log/pmm-agent.log
                                    sudo pmm-agent setup --server-address=\\\$(ip addr show eth0 | grep 'inet ' | awk '{print\\\$2}' | cut -d '/' -f 1):443 --server-insecure-tls --server-username=admin --server-password=admin --trace
                                    sleep 10
                                    sudo cat /var/log/pmm-agent.log
                                    pmm-admin add mysql --use-perfschema --username=root
                                    pmm-admin list
                                fi
                            else
                                sudo pmm-admin config --client-name pmm-client-hostname --server \\\$(ip addr show eth0 | grep 'inet ' | awk '{print\\\$2}' | cut -d '/' -f 1)
                            fi
                            "
                         """
                        }
                    }
                }
            }
        }
        stage('Run Clients') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        export IP=\$(cat IP)
                        [ -z "${CLIENTS}" ] && exit 0 || :
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) "
                            set -o errexit
                            set -o xtrace
                            export PATH=\$PATH:/usr/sbin
                            test -f /usr/lib64/libsasl2.so.2 || sudo ln -s /usr/lib64/libsasl2.so.3.0.0 /usr/lib64/libsasl2.so.2

                            if [[ \$PMM_VERSION == pmm1 ]]; then
                                bash /srv/pmm-qa/pmm-tests/pmm-framework.sh \
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
                            fi

                            if [[ \$CLIENT_VERSION == http* ]]; then
                                export PATH="$PWD/pmm2-client-2.0.0/bin:$PATH"
                            fi

                            if [[ \$PMM_VERSION == pmm2 ]]; then
                                bash /srv/pmm-qa/pmm-tests/pmm-framework.sh \
                                    --ms-version  ${MS_VERSION} \
                                    --mo-version  ${MO_VERSION} \
                                    --ps-version  ${PS_VERSION} \
                                    --modb-version ${MODB_VERSION} \
                                    --pgsql-version ${PGSQL_VERSION} \
                                    --pxc-version ${PXC_VERSION} \
                                    --download \
                                    ${CLIENTS} \
                                    --pmm2 \
                                    --dbdeployer \
                                    --query-source=${QUERY_SOURCE} \
                                    --pmm2-server-ip=\$IP
                            fi
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
