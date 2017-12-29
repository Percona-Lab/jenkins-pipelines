pipeline {
    agent {
        label 'virtualbox'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '',
            description: 'public ssh key for "vagrant" user, please set if you need ssh access',
            name: 'SSH_KEY')
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
            defaultValue: '10.2',
            description: 'MariaDB Server version',
            name: 'MD_VERSION')
        string(
            defaultValue: '3.4',
            description: 'Percona Server for MongoDB version',
            name: 'MO_VERSION')
        string(
            defaultValue: '--addclient=ps,1 --addclient=mo,1',
            description: 'Configure PMM Clients. ps - Percona Server for MySQL, pxc - Percona XtraDB Cluster, ms - MySQL Community Server, md - MariaDB Server, MO - Percona Server for MongoDB',
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
                stash includes: 'OWNER,VM_NAME', name: 'VM_NAME'
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
                sh '''
                    export VM_NAME=\$(cat VM_NAME)
                    export OWNER=\$(cat OWNER)

                    export BUILD_ID=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_NODE_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_SERVER_COOKIE=dear-jenkins-please-dont-kill-virtualbox

                    VBoxManage import --vsys 0 --vmname \$VM_NAME \$(ls /mnt/images/Docker-Server-*.ovf | sort  | tail -1)
                    VBoxManage modifyvm \$VM_NAME \
                        --memory 8192 \
                        --audio none \
                        --nic1 bridged --bridgeadapter1 bond0 \
                        --uart1 0x3F8 4 --uartmode1 file /tmp/\$VM_NAME-console.log \
                        --groups "/\$OWNER,/${JOB_NAME}"
                    VBoxManage startvm --type headless \$VM_NAME

                    for I in $(seq 1 6); do
                        IP=\$(grep eth0: /tmp/\$VM_NAME-console.log | cut -d '|' -f 4 | sed -e 's/ //g')
                        if [ -n "\$IP" ]; then
                            break
                        fi
                        sleep 10
                    done
                    echo \$IP > IP
                    echo \$IP > PUBLIC_IP

                    if [ "X\$IP" = "X." ]; then
                        echo Error during DHCP configure. exiting
                        exit 1
                    fi
                '''

                // push ssh key
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        export IP=\$(cat IP)
                        ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$IP '
                            sudo adduser -m -U -G vagrant,docker ec2-user
                            sudo install -d -o ec2-user -g ec2-user /home/ec2-user/.ssh
                        '
                        ssh-keygen -y -f $KEY_PATH | ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$(cat IP) 'cat - | sudo -u ec2-user tee -a /home/ec2-user/.ssh/authorized_keys'
                        if [ -n "$SSH_KEY" ]; then
                            echo '$SSH_KEY' | ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$(cat IP) 'cat - | sudo -u ec2-user tee -a /home/ec2-user/.ssh/authorized_keys'
                        fi
                    """
                }

                // Reconfigure DHCP
                sh '''
                    export IP=\$(cat IP)
                    ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$IP '
                        export PATH=$PATH:/usr/sbin
                        ROUTE_DEV=$(ip route get 8.8.8.8 | grep "dev ")
                        GATEWAY=$(echo $ROUTE_DEV | awk "{print\\\$3}")
                        INTERFACE=$(echo $ROUTE_DEV | awk "{print\\\$5}")
                        IP_NETMASK=$(ip address show dev $INTERFACE | grep "inet " | awk "{print\\\$2}")
                        IP=$(echo $IP_NETMASK | cut -d '/' -f 1)
                        NETMASK=$(echo $IP_NETMASK | cut -d '/' -f 2)
                        DNS=$(grep nameserver /etc/resolv.conf | awk "{print\\\$2}" | head -1)
                        echo "
                            DEVICE=$INTERFACE
                            BOOTPROTO=static
                            IPADDR=$IP
                            PREFIX=$NETMASK
                            GATEWAY=$GATEWAY
                            DNS1=8.8.4.4
                            DNS2=8.8.8.8
                            PEERDNS=yes
                            ONBOOT=yes
                            TYPE=Ethernet
                        " | sudo tee /etc/sysconfig/network-scripts/ifcfg-$INTERFACE
                        sudo systemctl stop NetworkManager
                        sudo systemctl restart network docker

                        sudo yum -y install svn
                        sudo rm -rf /srv/percona-qa || :
                        sudo mkdir -p /srv/percona-qa || :
                        pushd /srv/percona-qa
                            sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/pmm-tests
                            sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/get_download_link.sh
                            sudo chmod 755 get_download_link.sh
                        popd
                    '
                '''
                script {
                    env.IP      = sh(returnStdout: true, script: "cat IP").trim()
                    env.VM_NAME = sh(returnStdout: true, script: "cat VM_NAME").trim()
                }
                archiveArtifacts 'PUBLIC_IP'
            }
        }

        stage('Run Docker') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        export IP=\$(cat IP)
                        export VM_NAME=\$(cat VM_NAME)

                        export CLIENT_VERSION=${CLIENT_VERSION}
                        if [ "X\$CLIENT_VERSION" = "Xlatest" ]; then
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

                            if [ "X\$CLIENT_VERSION" = "Xdev-latest" ]; then
                                sudo yum -y install pmm-client --enablerepo=percona-testing-*
                            else
                                wget --progress=dot:giga "https://www.percona.com/downloads/pmm-client/pmm-client-\${CLIENT_VERSION}/binary/tarball/pmm-client-\${CLIENT_VERSION}.tar.gz"
                                tar -zxpf pmm-client-\${CLIENT_VERSION}.tar.gz
                                pushd pmm-client-\${CLIENT_VERSION}
                                    sudo ./install
                                popd
                            fi

                            sleep 10
                            docker logs \${VM_NAME}-server

                            export PATH=\$PATH:/usr/sbin
                            sudo pmm-admin config --client-name pmm-client-hostname --server \$IP
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
                    def PUBLIC_IP = sh(returnStdout: true, script: "cat PUBLIC_IP").trim()
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${PUBLIC_IP}"
                    slackSend channel: "@${OWNER}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${PUBLIC_IP}"
                }
            }
        }
        failure {
            sh '''
                export VM_NAME=\$(cat VM_NAME)
                if [ -n "$VM_NAME" ]; then
                    VBoxManage controlvm $VM_NAME poweroff
                    sleep 10
                    VBoxManage unregistervm --delete $VM_NAME
                fi
            '''
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                    slackSend channel: "@${OWNER}", color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                }
            }
        }
    }
}
