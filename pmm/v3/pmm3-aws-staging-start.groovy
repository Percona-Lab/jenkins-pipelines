import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag, ex: perconalab/pmm-server:3-dev-latest)',
            name: 'DOCKER_VERSION'
        )
        string(
            defaultValue: 'perconalab/watchtower:dev-latest',
            description: 'WatchTower docker container version (image-name:version-tag, ex: perconalab/watchtower:dev-latest)',
            name: 'WATCHTOWER_VERSION'
        )
        string(
            defaultValue: '3-dev-latest',
            description: 'PMM Client version ("3-dev-latest" for main branch, "latest" or "X.X.X" for released version, "pmm3-rc" for Release Candidate, "http://..." for feature build)',
            name: 'CLIENT_VERSION'
        )
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
            name: 'SSH_KEY'
        )
        string(
            defaultValue: 'pmm3admin!',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD'
        )
        choice(
            choices: ['experimental', 'testing', 'release'],
            description: 'PMM Client repo to enable',
            name: 'PMM_CLIENT_REPO'
        )
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Pull Mode, if you are using this instance as Client Node',
            name: 'ENABLE_PULL_MODE'
        )
        choice(
            choices: '1\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n21\n22\n23\n24\n25\n26\n27\n28\n29\n30',
            description: 'Stop the instance in X days ("0" value disables the automated instance removal)',
            name: 'DAYS'
        )
        text(
            defaultValue: '-e PMM_DEBUG=1 -e PMM_ENABLE_TELEMETRY=0',
            description: '''
            Pass environment variables to PMM Server Docker container.
            Example: -e PMM_DEV_TELEMETRY_DISABLE_START_DELAY=1 -e PMM_DEBUG=1
            ''',
            name: 'DOCKER_ENV_VARIABLE'
        )
        choice(
            choices: ['8.4', '8.0','5.7'],
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        choice(
            choices: ['8.0', '8.4', '5.7', '5.7.30', '5.6'],
            description: "Percona Server for MySQL version",
            name: 'PS_VERSION')
        choice(
            choices: ['8.0', '8.4', '5.7', '5.6'],
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        choice(
            choices: ['16', '17', '15','14', '13'],
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION')
        choice(
            choices: ['16', '17', '15', '14', '13'],
            description: 'Percona Distribution for PostgreSQL',
            name: 'PDPGSQL_VERSION')
        choice(
            choices: ['8.0', '7.0', '6.0', '5.0', '4.4'],
            description: "Percona Server for MongoDB version",
            name: 'PSMDB_VERSION')
        choice(
            choices: ['8.0', '7.0', '6.0', '5.0', '4.4'],
            description: "Official MongoDB version",
            name: 'MODB_VERSION')
        text(
            defaultValue: '--database ps=5.7,QUERY_SOURCE=perfschema',
            description: '''
            Configure PMM Clients:
            --database ps - Percona Server for MySQL (ex: --database ps=5.7,QUERY_SOURCE=perfschema)
            Additional options:
                QUERY_SOURCE=perfschema|slowlog
                SETUP_TYPE=replica(Replication)|gr(Group Replication)|(single node if no option passed)
            --database mysql - Official MySQL (ex: --database mysql,QUERY_SOURCE=perfschema)
            Additional options:
                QUERY_SOURCE=perfschema|slowlog
            --database psmdb - Percona Server for MongoDB (ex: --database psmdb=latest,SETUP_TYPE=pss)
            --database modb - Official MongoDB
            Additional options:
                SETUP_TYPE=pss(Primary-Secondary-Secondary)|psa(Primary-Secondary-Arbiter)|shards(Sharded cluster)
            --database pdpgsql - Percona Distribution for PostgreSQL (ex: --database pdpgsql=16)
            --database pgsql - Official PostgreSQL Distribution (ex: --database pgsql=16)
            --database pxc - Percona XtraDB Cluster, (to be used with proxysql only, ex: --database pxc)
            -----
            Example: --database ps=5.7,QUERY_SOURCE=perfschema --database psmdb,SETUP_TYPE=pss
            ''',
            name: 'CLIENTS'
        )
        choice(
            choices: ['true', 'false'],
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY'
        )
        choice(
            choices: ['no', 'yes'],
            description: "Use this instance only as a client host",
            name: 'CLIENT_INSTANCE'
        )
        string (
            defaultValue: '0.0.0.0',
            description: 'Please change the default value for Server Public IP, when you need to use this instance just as a client',
            name: 'SERVER_IP'
        )
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for qa-integration repository',
            name: 'PMM_QA_GIT_BRANCH'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                script {
                    // getPMMBuildParams sets envvars: VM_NAME, OWNER, OWNER_SLACK
                    getPMMBuildParams('pmm-')
                    echo """
                        DOCKER_VERSION:  ${DOCKER_VERSION}
                        WATCHTOWER_VERSION:${WATCHTOWER_VERSION}
                        CLIENT_VERSION:  ${CLIENT_VERSION}
                        CLIENTS:         ${CLIENTS}
                        PXC_VERSION:     ${PXC_VERSION}
                        PS_VERSION:      ${PS_VERSION}
                        MS_VERSION:      ${MS_VERSION}
                        MO_VERSION:      ${PSMDB_VERSION}
                        MODB_VERSION:    ${MODB_VERSION}
                        PGSQL_VERSION:   ${PGSQL_VERSION}
                        PDPGSQL_VERSION: ${PDPGSQL_VERSION}
                        OWNER:           ${OWNER}
                        VM_NAME:         ${VM_NAME}
                    """
                    env.ADMIN_PASSWORD = params.ADMIN_PASSWORD
                    env.DEFAULT_SSH_KEYS = getSSHKeys()
                    if (params.NOTIFY == "true") {
                        slackSend botUser: true, channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: build started, owner: @${OWNER}, URL: ${BUILD_URL}"
                        if (env.OWNER_SLACK) {
                            slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#0000FF', message: "[${JOB_NAME}]: build started, owner: @${OWNER}, URL: ${BUILD_URL}"
                        }
                    }
                }
            }
        }

        stage('Run VM') {
            steps {
                // This sets envvars: SPOT_PRICE, REQUEST_ID, IP, AMI_ID
                runSpotInstance('t3.large')

                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        until ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${USER}@${IP} ; do
                            sleep 5
                        done
                    '''
                }
                script {
                    SSHLauncher ssh_connection = new SSHLauncher(env.IP, 22, 'aws-jenkins')
                    DumbSlave node = new DumbSlave(env.VM_NAME, "aws-staging-start", "/home/ec2-user/", "1", Mode.EXCLUSIVE, "", ssh_connection, RetentionStrategy.INSTANCE)

                    currentBuild.description = "IP: ${env.IP} NAME: ${env.VM_NAME} PRICE: ${env.SPOT_PRICE}"
                    Jenkins.instance.addNode(node)
                }
                node(env.VM_NAME){
                    sh '''
                        set -o errexit
                        set -o xtrace

                        echo "${DEFAULT_SSH_KEYS}" >> /home/ec2-user/.ssh/authorized_keys
                        if [ -n "${SSH_KEY}" ]; then
                            echo "${SSH_KEY}" >> /home/ec2-user/.ssh/authorized_keys
                        fi

                        curl -O https://repo.percona.com/yum/percona-release-latest.noarch.rpm
                        sudo dnf -y install ./percona-release-latest.noarch.rpm
                        sudo rpm --import /etc/pki/rpm-gpg/PERCONA-PACKAGING-KEY
                        sudo dnf install ansible sysbench mysql -y
                    '''
                }
            }
        }
        stage('Run Docker') {
            when {
                expression { env.CLIENT_INSTANCE == "no" }
            }
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                                sh '''
                                    set -o errexit
                                    set -o xtrace

                                    aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                                    docker network create pmm-qa || true
                                    docker volume create pmm-data

                                    docker run --detach --restart always \
                                        --network pmm-qa \
                                        --name watchtower \
                                        --volume /var/run/docker.sock:/var/run/docker.sock \
                                        --restart always \
                                        -e WATCHTOWER_DEBUG=1 \
                                        -e WATCHTOWER_HTTP_API_TOKEN=testToken \
                                        -e WATCHTOWER_HTTP_API_UPDATE=1 \
                                        ${WATCHTOWER_VERSION}

                                    docker run -d \
                                        -p 443:8443 \
                                        -p 4647:4647 \
                                        --name pmm-server \
                                        --hostname pmm-server \
                                        --restart always \
                                        --network pmm-qa \
                                        --volume pmm-data:/srv \
                                        -e PMM_WATCHTOWER_HOST=http://watchtower:8080 \
                                        -e PMM_WATCHTOWER_TOKEN=testToken \
                                        ${DOCKER_ENV_VARIABLE} \
                                        ${DOCKER_VERSION}

                                    sleep 10
                                    docker logs pmm-server

                                    if [ ${ADMIN_PASSWORD} != admin ]; then
                                        docker exec pmm-server change-admin-password ${ADMIN_PASSWORD}
                                    fi
                                '''
                            }
                        }
                    }
                }
            }
        }
        stage('Run Clients') {
            steps {
                node(env.VM_NAME){
                    setupPMM3Client(SERVER_IP, CLIENT_VERSION.trim(), DOCKER_VERSION, ENABLE_PULL_MODE, 'no', CLIENT_INSTANCE, 'aws-staging', ADMIN_PASSWORD, 'no')
                    script {
                        env.PMM_REPO = params.CLIENT_VERSION == "pmm3-rc" ? "testing" : "experimental"
                    }

                    sh '''
                        set -o errexit
                        set -o xtrace
                        # Exit if no CLIENTS are provided
                        [ -z "${CLIENTS// }" ] && exit 0

                        export PATH=$PATH:/usr/sbin
                        export PMM_CLIENT_VERSION=${CLIENT_VERSION}
                        mkdir -m 777 -p /tmp/backup_data
                        if [ "${CLIENT_VERSION}" = 3-dev-latest ]; then
                            export PMM_CLIENT_VERSION="latest"
                        fi

                        if [[ "${CLIENT_INSTANCE}" = yes ]]; then
                            export EXTERNAL_PMM_SERVER_FLAG="--pmm-server-ip=${SERVER_IP}"
                        fi

                        docker network create pmm-qa || true

                        sudo mkdir -p /srv/qa-integration || :
                        pushd /srv/qa-integration
                            sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/Percona-Lab/qa-integration.git .
                        popd

                        sudo chown ec2-user -R /srv/qa-integration

                        pushd /srv/qa-integration/pmm_qa
                            echo "Setting docker based PMM clients"
                            python3 -m venv virtenv
                            . virtenv/bin/activate
                            pip install --upgrade pip
                            pip install -r requirements.txt

                            python pmm-framework.py --v \
                                --pmm-server-password=${ADMIN_PASSWORD} \
                                --client-version=${PMM_CLIENT_VERSION} \
                                ${EXTERNAL_PMM_SERVER_FLAG} ${CLIENTS}
                        popd
                    '''
                }
            }
        }
    }

    post {
        always {
            script {
                def node = Jenkins.instance.getNode(env.VM_NAME)
                if (node) {
                    echo "Removing the node from Jenkins: " + env.VM_NAME
                    Jenkins.instance.removeNode(node)
                }
            }
        }
        success {
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build finished, owner: @${OWNER}, URL: https://${env.IP}"
                    if (env.OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build finished, owner: @${OWNER}, URL: https://${env.IP}"
                    }
                }
            }
        }
        failure {
            withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                sh '''
                    set -o xtrace
                    if [ -n "${REQUEST_ID}" ]; then
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids ${REQUEST_ID}
                        aws ec2 --region us-east-2 terminate-instances --instance-ids ${AMI_ID}
                    fi
                '''
            }
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build failed, owner: @${OWNER}\nURL: ${BUILD_URL}"
                    if (env.OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build failed, owner: @${OWNER}\nURL: ${BUILD_URL}"
                    }
                }
            }
        }
        cleanup {
            deleteDir()
        }
    }
}
