pipeline {
    agent {
        label 'virtualbox'
    }
    parameters {
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: 'PMM-Server-dev-latest.ova',
            description: 'OVA Image version',
            name: 'OVA_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'Repositry to fetch Latest Image from',
            name: 'REPO')
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
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
            defaultValue: '10.3',
            description: 'MariaDB Server version',
            name: 'MD_VERSION')
        string(
            defaultValue: '4.0',
            description: 'Percona Server for MongoDB version',
            name: 'MO_VERSION')
        string(
            defaultValue: '10.6',
            description: 'Postgre SQL Server version',
            name: 'PGSQL_VERSION')
        string(
            defaultValue: '--addclient=ps,1 --addclient=mo,1',
            description: 'Configure PMM Clients. ps - Percona Server for MySQL, pxc - Percona XtraDB Cluster, ms - MySQL Community Server, md - MariaDB Server, MO - Percona Server for MongoDB, pgsql - Postgre SQL Server',
            name: 'CLIENTS')
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
        string(
            defaultValue: 'true',
            description: 'Use this OVA Setup as PMM-client',
            name: 'SETUP_CLIENT')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-qa repository',
            name: 'GIT_BRANCH')
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
                        slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        slackSend botUser: true, channel: "@${OWNER}", color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                    }
                }
            }
        }

        stage('Run VM') {
            steps {
                unstash 'VM_NAME'
                sh '''
                    wget -O \$(cat VM_NAME).ova http://percona-vm.s3-website-us-east-1.amazonaws.com/\${OVA_VERSION} > /dev/null
                '''
                sh '''
                    export VM_NAME=\$(cat VM_NAME)
                    export OWNER=\$(cat OWNER)

                    export BUILD_ID=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_NODE_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_SERVER_COOKIE=dear-jenkins-please-dont-kill-virtualbox

                    tar xvf \$VM_NAME.ova
                    export ovf_name=$(find -type f -name '*.ovf');
                    VBoxManage import \$ovf_name --vsys 0 --memory 2048 --vmname \$VM_NAME > /dev/null
                    VBoxManage modifyvm \$VM_NAME \
                        --memory 2048 \
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
                withCredentials([usernamePassword(credentialsId: 'Jenkins API', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        set +x
                        export VM_NAME=\$(cat VM_NAME)

                        mkdir -p /tmp/\$VM_NAME
                        rm -rf /tmp/\$VM_NAME/sshkey
                        touch /tmp/\$VM_NAME/sshkey

                        yes y | ssh-keygen -f "/tmp/\$VM_NAME/sshkey" -N "" > /dev/null
                        cat "/tmp/\$VM_NAME/sshkey.pub" > PUB_KEY
                        chmod 600 /tmp/\$VM_NAME/sshkey
                        set -x
                    """
                }
                stash includes: 'PUB_KEY', name: 'PUB_ACCESS'
                script {
                    env.IP      = sh(returnStdout: true, script: "cat IP | cut -f1 -d' '").trim()
                    env.VM_NAME = sh(returnStdout: true, script: "cat VM_NAME").trim()
                    env.PUB_KEY = sh(returnStdout: true, script: "cat PUB_KEY").trim()
                    env.OWNER   = sh(returnStdout: true, script: "cat OWNER | cut -d . -f 1").trim()
                }
                archiveArtifacts 'PUBLIC_IP'
            }
        }
        stage('Configure VM'){
            steps{
                unstash 'VM_NAME'
                unstash 'PUB_ACCESS'
                sh """
                    set +x
                    curl -s -S --header "Content-Type: application/json" \
                        --request POST --insecure \
                        --data '{ "key": "'"${env.PUB_KEY}"'"}' \
                        -i http://${env.IP}/configurator/v1/sshkey > /dev/null
                    if [ -n "$SSH_KEY" ]; then
                        echo '$SSH_KEY' | ssh -i "/tmp/${env.VM_NAME}/sshkey" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${env.IP} 'cat - >> .ssh/authorized_keys'
                    fi

                    ssh -i "/tmp/${env.VM_NAME}/sshkey" -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null admin@${env.IP} "
                        set -o errexit
                        set -o xtrace
                        sudo pmm-admin config --server ${env.IP}
                        sudo service mysqld start
                        sleep 10
                        sudo service mysqld status
                        sudo pmm-admin add mysql ms1 --host localhost
                        sudo yum -y install wget svn cyrus-sasl-devel
                        sudo mv /usr/lib64/libsasl2.so /usr/lib64/libsasl2.so.2
                        sudo mkdir -p /srv/percona-qa || :
                        pushd /srv/percona-qa
                            sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/pmm-tests
                            sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/get_download_link.sh
                            sudo chmod 755 get_download_link.sh
                        popd
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
                    set -x
                """
            }
        }
    }

    post {
        success {
            script {
                if ("${NOTIFY}" == "true") {
                    def PUBLIC_IP = sh(returnStdout: true, script: "cat PUBLIC_IP").trim()
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${PUBLIC_IP}"
                    slackSend botUser: true, channel: "@${OWNER}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${PUBLIC_IP}"
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
                    rm -r /tmp/$VM_NAME
                fi
            '''
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                    slackSend botUser: true, channel: "@${OWNER}", color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                }
            }
        }
    }
}
