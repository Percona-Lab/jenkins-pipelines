void runUITests(CLIENT_VERSION, CLIENT_INSTANCE, SERVER_IP) {
    stagingJob = build job: 'pmm2-ui-tests', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'SERVER_IP', value: SERVER_IP)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://admin:admin@${SERVER_IP}"
    env.PMM_UI_URL = "https://${SERVER_IP}"
}

pipeline {
    agent {
        label 'ovf-do'
    }
    parameters {
        string(
            defaultValue: 'dev-latest',
            description: 'PMM2 Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: 'PMM2-Server-dev-latest.ova',
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
            defaultValue: '10.7',
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
    triggers {
        upstream upstreamProjects: 'pmm2-ovf', threshold: hudson.model.Result.SUCCESS
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
                        slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        slackSend channel: "@${OWNER}", color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                    }
                }
            }
        }
        stage('Run PMM-Server') {
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
                        --natpf1 "guestssh,tcp,,80,,80" \
                        --uart1 0x3F8 4 --uartmode1 file /tmp/\$VM_NAME-console.log \
                        --groups "/\$OWNER,/${JOB_NAME}"
                    VBoxManage modifyvm \$VM_NAME --natpf1 "guesthttps,tcp,,443,,443"
                    for p in $(seq 0 10); do
                        VBoxManage modifyvm \$VM_NAME --natpf1 "guestexporters\$p,tcp,,4200\$p,,4200\$p"
                    done
                    VBoxManage startvm --type headless \$VM_NAME
                    sleep 180
                    cat /tmp/\$VM_NAME-console.log
                    for I in $(seq 1 6); do
                        IP=\$(grep eth0 /tmp/\$VM_NAME-console.log | cut -d '|' -f 4 | sed -e 's/ //g' | head -n 1)
                        if [ -n "\$IP" ]; then
                            break
                        fi
                        sleep 10
                    done
                    echo \$IP > IP
                    PUBIP=\$(curl ifconfig.me)
                    echo \$PUBIP > PUBLIC_IP
                    cat PUBLIC_IP
                    
                    if [ "X\$IP" = "X." ]; then
                        echo Error during DHCP configure. exiting
                        exit 1
                    fi
                    sleep 120
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
                    env.PUBLIC_IP = sh(returnStdout: true, script: "cat PUBLIC_IP").trim()
                    env.VM_NAME = sh(returnStdout: true, script: "cat VM_NAME").trim()
                    env.PUB_KEY = sh(returnStdout: true, script: "cat PUB_KEY").trim()
                    env.OWNER   = sh(returnStdout: true, script: "cat OWNER | cut -d . -f 1").trim()
                }
                archiveArtifacts 'PUBLIC_IP'
                archiveArtifacts 'VM_NAME'
            }
        }
        stage('Start UI Tests') {
            steps {
                runUITests(CLIENT_VERSION, 'yes', "${env.PUBLIC_IP}")
            }
        }
    }

    post {
        success {
            script {
                sh '''
                    export VM_NAME=\$(cat VM_NAME)
                    if [ -n "$VM_NAME" ]; then
                        VBoxManage controlvm $VM_NAME poweroff
                        sleep 10
                        VBoxManage unregistervm --delete $VM_NAME
                        rm -r /tmp/$VM_NAME
                    fi
                '''
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
                    rm -r /tmp/$VM_NAME
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
