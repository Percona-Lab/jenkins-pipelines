void runTAP(String TYPE, String COUNT) {
    sh """
        export IP=\$(cat IP)
        ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$IP '
            set -o xtrace

            export PATH=\$PATH:/usr/sbin
            export instance_t="${TYPE}"
            export instance_c="${COUNT}"
            export stress="1"
            export table_c="100"
            export tap="1"

            bash /srv/percona-qa/pmm-tests/pmm-testsuite.sh \
                | tee /tmp/result.output

            perl -ane "
                if (m/ok \\d+/) {
                    \\\$i++;
                    s/(.*ok) \\d+ (.*)/\\\$1 \\\$i \\\$2/;
                    print;
                }
                END { print \\"1..\\\$i\\n\\" }
            " /tmp/result.output | tee /tmp/result.tap
        '
        scp -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$IP:/tmp/result.tap ${TYPE}.tap
    """
}

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
            defaultValue: '5.7.17-rel11-27.20.2',
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        string(
            defaultValue: '5.7.17-11',
            description: 'Percona Server for MySQL version',
            name: 'PS_VERSION')
        string(
            defaultValue: '5.7.17',
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        string(
            defaultValue: '10.1.22',
            description: 'MariaDB Server version',
            name: 'MD_VERSION')
        string(
            defaultValue: '3.4.2-1.2',
            description: 'Percona Server for MongoDB version',
            name: 'MO_VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm-docker', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {

                echo """
                    DOCKER_VERSION: ${DOCKER_VERSION}
                    CLIENT_VERSION: ${CLIENT_VERSION}
                    PXC_VERSION:    ${PXC_VERSION}
                    PS_VERSION:     ${PS_VERSION}
                    MS_VERSION:     ${MS_VERSION}
                    MD_VERSION:     ${MD_VERSION}
                    MO_VERSION:     ${MO_VERSION}
                """

                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${env.BUILD_URL}"

                sh """
                    export VM_NAME="${JOB_NAME}-\$(date -u '+%Y%m%d%H%M')"
                    echo \$VM_NAME > VM_NAME

                    export OWNER=\$(
                        curl -s $BUILD_URL/api/json \
                            | python -c "import sys, json; print json.load(sys.stdin)['actions'][1]['causes'][0]['userId']"
                    )
                    echo \$OWNER > OWNER

                    if [ "X$CLIENT_VERSION" = "Xlatest" ]; then
                        CLIENT_VERSION=\$(
                            curl https://www.percona.com/downloads/pmm-client/ \
                                | egrep -o 'pmm-client-[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}' \
                                | sed -e 's/pmm-client-//' \
                                | sort -u -V \
                                | tail -1
                        )
                        echo \$CLIENT_VERSION > CLIENT_VERSION
                    else
                        echo $CLIENT_VERSION > CLIENT_VERSION
                    fi
                """
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

                    if [ "X\$IP" = "X." ]; then
                        echo Error during DHCP configure. exiting
                        exit 1
                    fi
                '''

                // push ssh key
                sh """
                    if [ -n "$SSH_KEY" ]; then
                        echo '$SSH_KEY' | ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$(cat IP) 'cat - >> .ssh/authorized_keys'
                    fi
                """

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
                            DNS1=$DNS
                            ONBOOT=yes
                            TYPE=Ethernet
                        " | sudo tee /etc/sysconfig/network-scripts/ifcfg-$INTERFACE
                        sudo systemctl restart network docker
                    '
                '''
            }
        }

        stage('Run Docker') {
            steps {
                sh """
                    export IP=\$(cat IP)

                    ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$IP "
                        set -o xtrace

                        docker create \
                            -v /opt/prometheus/data \
                            -v /opt/consul-data \
                            -v /var/lib/mysql \
                            -v /var/lib/grafana \
                            --name \$(cat VM_NAME)-data \
                            ${DOCKER_VERSION} /bin/true

                        docker run -d \
                            -p 80:80 \
                            -p 443:443 \
                            --volumes-from \$(cat VM_NAME)-data \
                            --name \$(cat VM_NAME)-server \
                            --restart always \
                            -e METRICS_RESOLUTION=5s \
                            ${DOCKER_VERSION}

                        # it is needed to wait 20 second, it is better to download files instead of sleep command
                        wget --progress=dot:giga \
                            "https://www.percona.com/downloads/Percona-Server-LATEST/Percona-Server-${PS_VERSION}/binary/tarball/Percona-Server-${PS_VERSION}-Linux.x86_64.ssl101.tar.gz" \
                            "http://nyc2.mirrors.digitalocean.com/mariadb//mariadb-${MD_VERSION}/bintar-linux-x86_64/mariadb-${MD_VERSION}-linux-x86_64.tar.gz" \
                            "https://dev.mysql.com/get/Downloads/MySQL-5.7/mysql-${MS_VERSION}-linux-glibc2.5-x86_64.tar.gz" \
                            "https://www.percona.com/downloads/Percona-XtraDB-Cluster-LATEST/Percona-XtraDB-Cluster-\$(echo "$PXC_VERSION" | sed -r 's/-rel[0-9]{1,2}-/-/; s/[.][0-9]\$//')/binary/tarball/Percona-XtraDB-Cluster-${PXC_VERSION}.Linux.x86_64.ssl101.tar.gz" \
                            "https://www.percona.com/downloads/percona-server-mongodb-LATEST/percona-server-mongodb-${MO_VERSION}/binary/tarball/percona-server-mongodb-${MO_VERSION}-centos7-x86_64.tar.gz"

                        docker logs \$(cat VM_NAME)-server

                        pushd /srv/percona-qa
                            sudo git pull
                        popd

                        CLIENT_VERSION=\$(cat CLIENT_VERSION)
                        if [ "X\$CLIENT_VERSION" = "Xdev-latest" ]; then
                            sudo yum -y install pmm-client --enablerepo=percona-experimental-* --enablerepo=percona-testing-*
                        else
                            wget --progress=dot:giga "https://www.percona.com/downloads/pmm-client/pmm-client-\$(cat CLIENT_VERSION)/binary/tarball/pmm-client-\$(cat CLIENT_VERSION).tar.gz"
                            if [ ! -f "pmm-client-\$CLIENT_VERSION.tar.gz" ]; then
                                wget --progress=dot:giga "https://www.percona.com/downloads/TESTING/pmm/pmm-client-\$(cat CLIENT_VERSION).tar.gz"
                            fi
                            tar -zxpf pmm-client-\$CLIENT_VERSION.tar.gz
                            pushd pmm-client-\$CLIENT_VERSION
                                sudo ./install
                            popd
                        fi

                        export PATH=\$PATH:/usr/sbin
                        sudo pmm-admin config --client-name pmm-client-hostname --server \$IP
                    "
                """
            }
        }

        stage('Test: PS') {
            steps {
                runTAP("ps", "2")
            }
        }
        stage('Test: PXC') {
            steps {
                runTAP("pxc", "3")
            }
        }
        stage('Test: PSMDB') {
            steps {
                runTAP("mo", "3")
            }
        }
        stage('Test: MariaDB') {
            steps {
                runTAP("md", "2")
            }
        }
    }

    post {
        always {
            sh '''
                export VM_NAME=\$(cat VM_NAME)
                if [ -n "$VM_NAME" ]; then
                    VBoxManage controlvm $VM_NAME poweroff
                    sleep 10
                    VBoxManage unregistervm --delete $VM_NAME
                fi
            '''
        }
        success {
            archiveArtifacts '*.tap'
            step([$class: "TapPublisher", testResults: '*.tap'])
            script {
                OK = sh (
                    script: 'grep "^ok" *.tap | grep -v "# skip" | wc -l',
                    returnStdout: true
                ).trim()
                SKIP = sh (
                    script: 'grep "# skip" *.tap | wc -l',
                    returnStdout: true
                ).trim()
                FAIL = sh (
                    script: 'grep "^not ok" *.tap | wc -l',
                    returnStdout: true
                ).trim()
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished\nok - ${OK}, skip - ${SKIP}, fail - ${FAIL}"
            }
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
        }
    }
}
