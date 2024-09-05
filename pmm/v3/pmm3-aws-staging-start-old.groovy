import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag, ex: perconalab/pmm-server:3-dev-latest)',
            name: 'DOCKER_VERSION'
        )
        string(
            defaultValue: 'https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-latest.tar.gz',
            description: 'PMM Client version ("3-dev-latest" for main branch, "latest" or "X.X.X" for released version, "pmm3-rc" for Release Candidate, "http://..." for feature build)',
            name: 'CLIENT_VERSION'
        )
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
            name: 'SSH_KEY'
        )
        string(
            defaultValue: 'pmm2023fortesting!',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD'
        )
        choice(
            choices: ['pmm'],
            description: 'Which Version of PMM Server: pmm stands for PMM v3 and up',
            name: 'PMM_VERSION'
        )
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo, for RC testing',
            name: 'ENABLE_TESTING_REPO'
        )
        choice(
            choices: ['yes', 'no'],
            description: 'Enable Experimental Repo, for 3-dev-latest',
            name: 'ENABLE_EXPERIMENTAL_REPO'
        )
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Pull Mode, if you are using this instance as Client Node',
            name: 'ENABLE_PULL_MODE'
        )
        choice(
            choices: '1\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n21\n22\n23\n24\n25\n26\n27\n28\n29\n30',
            description: 'Stop the instance after, days ("0" value disables autostop and recreates instance in case of AWS failure)',
            name: 'DAYS'
        )
        choice(
            choices: ['8.0','5.7'],
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION'
        )
        choice(
            choices: ['8.0', '5.7', '5.7.30', '5.6'],
            description: "Percona Server for MySQL version",
            name: 'PS_VERSION'
        )
        choice(
            choices: ['8.0', '5.7', '5.6'],
            description: 'MySQL Community Server version',
            name: 'MS_VERSION'
        )
        choice(
            choices: ['15','14', '13', '12', '11'],
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION'
        )
        choice(
            choices: ['16.0','15.4', '14.9', '13.12', '12.16', '11.21'],
            description: 'Percona Distribution for PostgreSQL',
            name: 'PDPGSQL_VERSION'
        )
        choice(
            choices: ['10.6', '10.5', '10.4', '10.3', '10.2'],
            description: "MariaDB Server version",
            name: 'MD_VERSION'
        )
        choice(
            choices: ['6.0', '5.0', '4.4', '4.2', '4.0', '3.6'],
            description: "Percona Server for MongoDB version",
            name: 'MO_VERSION'
        )
        choice(
            choices: ['4.4', '4.2', '4.0', '6.0', '5.0.2'],
            description: "Official MongoDB version from MongoDB Inc",
            name: 'MODB_VERSION'
        )
        choice(
            choices: ['perfschema', 'slowlog'],
            description: "Query Source for Monitoring",
            name: 'QUERY_SOURCE'
        )
        choice(
            choices: ['dev','prod'],
            description: 'Prod or Dev version service',
            name: 'VERSION_SERVICE_VERSION'
        )
        string(
            defaultValue: '',
            description: 'Docker image for version service, use it if you want to run your own version service.',
            name: 'VERSION_SERVICE_IMAGE'
        )
        text(
            defaultValue: '-e PMM_DEBUG=1 -e PMM_ENABLE_TELEMETRY=0 -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com',
            description: '''
            Passing environment variables to PMM Server Docker container is supported for PMM v2 and up.
            Example: -e PMM_DEV_TELEMETRY_DISABLE_START_DELAY=1 -e PMM_DEBUG=1
            ''',
            name: 'DOCKER_ENV_VARIABLE'
        )
        text(
            defaultValue: '--addclient=ps,1',
            description: '''
            Configure PMM Clients:
            ms - MySQL (ex: --addclient=ms,1)
            ps - Percona Server for MySQL (ex: --addclient=ps,1)
            pxc - Percona XtraDB Cluster, --with-proxysql (to be used with proxysql only, ex: --addclient=pxc,1 --with-proxysql)
            md - MariaDB Server (ex: --addclient=md,1)
            mo - Percona Server for MongoDB (ex: --addclient=mo,1)
            modb - Official MongoDB version (ex: --addclient=modb,1)
            pgsql - PostgreSQL Server (ex: --addclient=pgsql,1)
            pdpgsql - Percona Distribution for PostgreSQL (ex: --addclient=pdpgsql,1)
            -----
            Example: --addclient=ps,1 --addclient=mo,2 --addclient=md,1 --addclient=pgsql,1 --addclient=modb,1
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
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH'
        )
        string(
            defaultValue: '',
            description: 'Commit hash for pmm-qa branch',
            name: 'PMM_QA_GIT_COMMIT_HASH'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
    }
    environment {
        DEFAULT_SSH_KEYS = getSHHKeysPMM()
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
                        CLIENT_VERSION:  ${CLIENT_VERSION}
                        PMM_VERSION:     ${PMM_VERSION}
                        PXC_VERSION:     ${PXC_VERSION}
                        PS_VERSION:      ${PS_VERSION}
                        MS_VERSION:      ${MS_VERSION}
                        MD_VERSION:      ${MD_VERSION}
                        MO_VERSION:      ${MO_VERSION}
                        MODB_VERSION:    ${MODB_VERSION}
                        PGSQL_VERSION:   ${PGSQL_VERSION}
                        PDPGSQL_VERSION: ${PDPGSQL_VERSION}
                        QUERY_SOURCE:    ${QUERY_SOURCE}
                        CLIENTS:         ${CLIENTS}
                        OWNER:           ${OWNER}
                        VM_NAME:         ${VM_NAME}
                        VERSION_SERVICE: ${VERSION_SERVICE_IMAGE}
                    """
                    env.ADMIN_PASSWORD = params.ADMIN_PASSWORD
                    if (params.NOTIFY == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started, owner: @${OWNER}, URL: ${BUILD_URL}"
                        if (env.OWNER_SLACK) {
                            slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#0000FF', message: "[${JOB_NAME}]: build started, owner: @${OWNER}, URL: ${BUILD_URL}"
                        }
                    }
                }
            }
        }

        stage('Run VM') {
            steps {
                // This sets envvars: SPOT_PRICE, REQUEST_ID, IP, ID (AMI_ID)
                launchSpotInstance('t3.large', 'FAIR', 30)

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

                        sudo yum -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm
                        sudo rpm --import /etc/pki/rpm-gpg/PERCONA-PACKAGING-KEY
                        sudo yum repolist
                        sudo yum install sysbench mysql -y
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

                                    if [ ${VERSION_SERVICE_VERSION} == dev ]; then
                                        ENV_VARIABLE="${DOCKER_ENV_VARIABLE} -e PMM_DEV_VERSION_SERVICE_URL=https://check-dev.percona.com/versions/v1"
                                    else
                                        ENV_VARIABLE="${DOCKER_ENV_VARIABLE} -e PMM_DEV_VERSION_SERVICE_URL=https://check.percona.com/versions/v1"
                                    fi

                                    if [ -n "${VERSION_SERVICE_IMAGE}" ]; then
                                        ENV_VARIABLE="${DOCKER_ENV_VARIABLE} -e PMM_DEV_VERSION_SERVICE_URL=http://version-service/versions/v1"
                                    else
                                        ENV_VARIABLE="${DOCKER_ENV_VARIABLE}"
                                    fi

                                    docker network create pmm-qa || true
                                    docker volume create pmm-data

                                    docker run -d \
                                        -p 80:8080 \
                                        -p 443:8443 \
                                        -p 9000:9000 \
                                        --volume pmm-data:/srv \
                                        --name pmm-server \
                                        --hostname pmm-server \
                                        --network pmm-qa \
                                        --restart always \
                                        $ENV_VARIABLE \
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
        stage('Run version service') {
            when {
                expression { env.VERSION_SERVICE_IMAGE != "" }
            }
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            sh '''
                                set -o errexit
                                set -o xtrace
                                docker run -d --name version-service --hostname=version-service -e SERVE_HTTP=true -e GW_PORT=80 ${VERSION_SERVICE_IMAGE}
                                docker network create vs-network
                                docker network connect vs-network version-service
                                docker network connect vs-network pmm-server
                            '''
                        }
                    }
                }
            }
        }
        stage('Run Clients') {
            steps {
                node(env.VM_NAME){
                    // Download the client, install it outside of PMM server and configure it to connect to PMM
                    setupPMM3Client(SERVER_IP, CLIENT_VERSION.trim(), PMM_VERSION, ENABLE_PULL_MODE, ENABLE_TESTING_REPO, CLIENT_INSTANCE, 'aws-staging', ADMIN_PASSWORD)

                    script {
                        env.PMM_REPO = params.CLIENT_VERSION == "pmm-rc" ? "testing" : "experimental"
                    }

                    sh '''
                        set -o errexit
                        set -o xtrace
                        # Exit if no CLIENTS are provided
                        [ -z "${CLIENTS// }" ] && exit 0

                        export PATH=$PATH:/usr/sbin
                        export PMM_CLIENT_VERSION=${CLIENT_VERSION}
                        if [ "${CLIENT_VERSION}" = 3-dev-latest ]; then
                            export PMM_CLIENT_VERSION="latest"
                        fi

                        PMM_SERVER_IP=${SERVER_IP}
                        if [[ "${CLIENT_INSTANCE}" = no ]]; then
                            PMM_SERVER_IP=${IP}
                        fi

                        pmm-admin --version

                        sudo mkdir -p /srv/pmm-qa || :
                        pushd /srv/pmm-qa
                            sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} --depth=1 https://github.com/percona/pmm-qa.git .
                            sudo git checkout ${PMM_QA_GIT_COMMIT_HASH}
                            sudo curl -O https://raw.githubusercontent.com/Percona-QA/percona-qa/master/get_download_link.sh
                            sudo chmod 755 get_download_link.sh
                        popd

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
                            --pmm2-server-ip=$PMM_SERVER_IP
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
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, owner: @${OWNER}, URL: https://${env.IP}"
                    if (env.OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build finished, owner: @${OWNER}, URL: https://${env.IP}"
                    }
                }
            }
        }
        failure {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                    set -o xtrace
                    REQUEST_ID=$(cat REQUEST_ID)
                    if [ -n "$REQUEST_ID" ]; then
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids $REQUEST_ID
                        aws ec2 --region us-east-2 terminate-instances --instance-ids $(cat ID)
                    fi
                '''
            }
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed, owner: @${OWNER}\nURL: ${BUILD_URL}"
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
