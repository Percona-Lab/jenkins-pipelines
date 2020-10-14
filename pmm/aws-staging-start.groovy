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
            choices: ['yes', 'no'],
            description: 'Enable Testing Repo?',
            name: 'ENABLE_TESTING_REPO')
        choice(
            choices: '1\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n21\n22\n23\n24\n25\n26\n27\n28\n29\n30',
            description: 'Stop the instance after, days ("0" value disables autostop and recreates instance in case of AWS failure)',
            name: 'DAYS')
        choice(
            choices: ['8.0','5.7'],
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        choice(
            choices: ['8.0', '5.7', '5.6'],
            description: "Percona Server for MySQL version",
            name: 'PS_VERSION')
        choice(
            choices: ['5.7', '8.0', '5.6'],
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        choice(
            choices: ['12', '11', '10.8'],
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION')
        choice(
            choices: ['12','11'],
            description: 'Percona Distribution for PostgreSQL',
            name: 'PDPGSQL_VERSION')
        choice(
            choices: ['10.5', '10.4', '10.3', '10.2'],
            description: "MariaDB Server version",
            name: 'MD_VERSION')
        choice(
            choices: ['4.2', '4.4', '4.0', '3.6'],
            description: "Percona Server for MongoDB version",
            name: 'MO_VERSION')
        choice(
            choices: ['4.4', '4.2', '4.0'],
            description: "Official MongoDB version from MongoDB Inc",
            name: 'MODB_VERSION')
        choice(
            choices: ['perfschema', 'slowlog'],
            description: "Query Source for Monitoring",
            name: 'QUERY_SOURCE')
        text(
            defaultValue: '-e METRICS_RETENTION=192h',
            description: '''
            Passing Env Variables to PMM Server Docker Container, supported only for pmm2.x
            An Example: -e PERCONA_TEST_CHECKS_INTERVAL=10s -e PMM_DEBUG=1
            ''',
            name: 'DOCKER_ENV_VARIABLE')
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
            pdpgsql - Percona Distribution for PostgreSQL (ex. --addclient=pdpgsql,1)
            An example: --addclient=ps,1 --addclient=mo,1 --addclient=md,1 --addclient=pgsql,2 --addclient=modb,2
            ''',
            name: 'CLIENTS')
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
        choice(
            choices: ['no', 'yes'],
            description: "Use this instance only as a client host",
            name: 'CLIENT_INSTANCE')
        string (
            defaultValue: '0.0.0.0',
            description: 'Please change the default Value for Server Public IP, When you need to use this instance just as client',
            name: 'SERVER_IP')
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
                        echo "\${BUILD_USER_FIRST_NAME}" | tr [:upper:] [:lower:] > OWNER
                        echo "\${BUILD_USER_EMAIL}" > OWNER_EMAIL
                        echo "\${BUILD_USER_FIRST_NAME}.\${BUILD_USER_LAST_NAME}" | tr [:upper:] [:lower:] > OWNER_FULL
                        echo "pmm-\$(cat OWNER | cut -d . -f 1)-\$(date -u '+%Y%m%d%H%M%S')-${BUILD_NUMBER}" \
                            > VM_NAME
                    """
                }
                script {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')

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
                        PDPGSQL_VERSION: ${PDPGSQL_VERSION}
                        QUERY_SOURCE:   ${QUERY_SOURCE}
                        CLIENTS:        ${CLIENTS}
                        OWNER:          ${OWNER}
                    """
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
                                "InstanceType": "t2.large",
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
                            "SpotPrice": "0.040",
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
                            sudo yum -y install svn docker sysbench mysql57-server git php php-mysql php-pdo
                            sudo service mysqld start
                            sudo yum -y install bats --enablerepo=epel
                            sudo usermod -aG docker ec2-user
                            sudo service docker start
                            sudo mkdir -p /srv/pmm-qa || :
                            pushd /srv/pmm-qa
                                sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                                sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                                sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/get_download_link.sh
                                cd pmm-tests/
                                sudo svn export https://github.com/puneet0191/pmm-workloads.git/trunk/mysql/schema_table_query.php
                                sudo chmod 755 schema_table_query.php
                                cd ../
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
            when {
                expression { env.CLIENT_INSTANCE == "no" }
            }
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
                                        ${DOCKER_ENV_VARIABLE} \
                                        ${DOCKER_VERSION}
                                    sleep 10
                                    docker logs \${VM_NAME}-server
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
                                    sleep 10
                                    docker logs \${VM_NAME}-server
                                fi
                            "
                         """
                        }
                    }
                }
            }
        }
        stage('Enable Testing Repo') {
            when {
                expression { env.ENABLE_TESTING_REPO == "yes" && env.PMM_VERSION == "pmm2" && env.CLIENT_INSTANCE == "no" }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    script {
                        withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                            sh """
                                export IP=\$(cat IP)
                                export VM_NAME=\$(cat VM_NAME)
                                ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) "
                                    set -o errexit
                                    set -o xtrace
                                    docker exec \${VM_NAME}-server sed -i'' -e 's^/release/^/laboratory/^' /etc/yum.repos.d/pmm2-server.repo
                                    docker exec \${VM_NAME}-server percona-release enable original testing
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
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@\$(cat IP) "
                            set -o errexit
                            set -o xtrace
                            export PATH=\$PATH:/usr/sbin
                            test -f /usr/lib64/libsasl2.so.2 || sudo ln -s /usr/lib64/libsasl2.so.3.0.0 /usr/lib64/libsasl2.so.2
                            export CLIENT_IP=\$(curl ifconfig.me);
                            sudo yum -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm
                            if [[ \$CLIENT_VERSION = dev-latest ]]; then
                                sudo percona-release disable all
                                sudo percona-release enable original testing
                                sudo yum clean all
                                sudo yum makecache
                                sudo yum -y install pmm2-client
                                sudo yum -y update
                            elif [[ \$CLIENT_VERSION = pmm2-latest ]]; then
                                sudo yum clean all
                                sudo yum -y install pmm2-client
                                sudo yum -y update
                                sudo percona-release disable all
                                sudo percona-release enable original testing
                            elif [[ \$CLIENT_VERSION = 2* ]]; then
                                sudo yum clean all
                                sudo yum -y install pmm2-client-\$CLIENT_VERSION-6.el6.x86_64
                                sudo percona-release disable all
                                sudo percona-release enable original testing
                                sleep 15
                            elif [[ \$CLIENT_VERSION = pmm1-dev-latest ]]; then
                                sudo percona-release disable all
                                sudo percona-release enable original testing
                                sudo yum clean all
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
                                        wget -O pmm2-client.tar.gz --progress=dot:giga "https://www.percona.com/downloads/pmm2/\${CLIENT_VERSION}/binary/tarball/pmm2-client-\${CLIENT_VERSION}.tar.gz"
                                    fi
                                    export BUILD_ID=dear-jenkins-please-dont-kill-virtualbox
                                    export JENKINS_NODE_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                                    export JENKINS_SERVER_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                                    tar -zxpf pmm2-client.tar.gz
                                    rm -r pmm2-client.tar.gz
                                    mv pmm2-client-* pmm2-client
                                    cd pmm2-client
                                    sudo bash -x ./install_tarball
                                    cd ../
                                    export PMM_CLIENT_BASEDIR=\\\$(ls -1td pmm2-client 2>/dev/null | grep -v ".tar" | head -n1)
                                    export PATH="$PWD/pmm2-client/bin:$PATH"
                                    echo "export PATH=$PWD/pmm2-client/bin:$PATH" >> ~/.bash_profile
                                    source ~/.bash_profile
                                    pmm-admin --version
                                    if [[ \$CLIENT_INSTANCE == yes ]]; then
                                        pmm-agent setup --config-file=$PWD/pmm2-client/config/pmm-agent.yaml --server-address=\$SERVER_IP:443 --server-insecure-tls --server-username=admin --server-password=admin \$IP
                                    else
                                        pmm-agent setup --config-file=$PWD/pmm2-client/config/pmm-agent.yaml --server-address=\$IP:443 --server-insecure-tls --server-username=admin --server-password=admin \$IP
                                    fi
                                    sleep 10
                                    JENKINS_NODE_COOKIE=dontKillMe nohup bash -c 'pmm-agent --config-file=$PWD/pmm2-client/config/pmm-agent.yaml > pmm-agent.log 2>&1 &'
                                    sleep 10
                                    cat pmm-agent.log
                                    pmm-admin status
                                fi
                            fi
                            export PATH=\$PATH:/usr/sbin:/sbin
                            if [[ \$PMM_VERSION == pmm2 ]]; then
                                if [[ \$CLIENT_VERSION == dev-latest ]] || [[ \$CLIENT_VERSION == pmm2-latest ]] || [[ \$CLIENT_VERSION == 2* ]]; then
                                    pmm-admin --version
                                    if [[ \$CLIENT_INSTANCE == yes ]]; then
                                        sudo pmm-agent setup --server-address=\$SERVER_IP:443 --server-insecure-tls --server-username=admin --server-password=admin \$IP
                                    else
                                        sudo pmm-agent setup --server-address=\$IP:443 --server-insecure-tls --server-username=admin --server-password=admin \$IP
                                    fi
                                    sleep 10
                                    sudo cat /var/log/pmm-agent.log
                                    pmm-admin list
                                fi
                            else
                                sudo pmm-admin config --client-name pmm-client-hostname --server \\\$(ip addr show eth0 | grep 'inet ' | awk '{print\\\$2}' | cut -d '/' -f 1)
                            fi
                            [ -z "${CLIENTS}" ] && exit 0 || :
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

                            if [[ \$PMM_VERSION == pmm2 ]]; then

                                if [[ \$CLIENT_VERSION != dev-latest ]]; then
                                    export PATH="$PWD/pmm2-client/bin:$PATH"
                                fi
                                if [[ \$CLIENT_INSTANCE == no ]]; then
                                    export SERVER_IP=\$IP;
                                fi
                                bash /srv/pmm-qa/pmm-tests/pmm-framework.sh \
                                    --ms-version  ${MS_VERSION} \
                                    --mo-version  ${MO_VERSION} \
                                    --ps-version  ${PS_VERSION} \
                                    --modb-version ${MODB_VERSION} \
                                    --md-version  ${MD_VERSION} \
                                    --pgsql-version ${PGSQL_VERSION} \
                                    --pxc-version ${PXC_VERSION} \
                                    --pdpgsql-version ${PDPGSQL_VERSION} \
                                    --download \
                                    ${CLIENTS} \
                                    --pmm2 \
                                    --dbdeployer \
                                    --run-load-pmm2 \
                                    --query-source=${QUERY_SOURCE} \
                                    --pmm2-server-ip=\$SERVER_IP
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
                    def OWNER_FULL = sh(returnStdout: true, script: "cat OWNER_FULL").trim()
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')

                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, owner: @${OWNER_FULL}, link: https://${PUBLIC_IP}"
                    slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${PUBLIC_IP}"
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
