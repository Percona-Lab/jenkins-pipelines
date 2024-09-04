import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def changeUserPasswordUtility(dockerImage) {
    tag = dockerImage.split(":")[1]

    if (tag.startsWith("PR") || tag.startsWith("dev"))
        return "yes"

    minorVersion = tag.split("\\.")[1].toInteger()

    if (minorVersion < 27)
        return "no"
    else
        return "yes"
}

def DEFAULT_SSH_KEYS = getSHHKeysPMM()

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag, ex: perconalab/pmm-server:dev-latest)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version ("dev-latest" for main branch, "latest" or "X.X.X" for released version, "pmm2-rc" for Release Candidate, "http://..." for feature build)',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
            name: 'SSH_KEY')
        string(
            defaultValue: 'pmm2023fortesting!',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD')
        choice(
            choices: ['pmm2', 'pmm1'],
            description: 'Which Version of PMM-Server',
            name: 'PMM_VERSION')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo, for RC testing',
            name: 'ENABLE_TESTING_REPO')
        choice(
            choices: ['yes', 'no'],
            description: 'Enable Experimental Repo, for dev-latest',
            name: 'ENABLE_EXPERIMENTAL_REPO')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Pull Mode, if you are using this instance as Client Node',
            name: 'ENABLE_PULL_MODE')
        choice(
            choices: '1\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n21\n22\n23\n24\n25\n26\n27\n28\n29\n30',
            description: 'Stop the instance after, days ("0" value disables autostop and recreates instance in case of AWS failure)',
            name: 'DAYS')
        choice(
            choices: ['8.0','5.7'],
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        choice(
            choices: ['8.0', '5.7', '5.7.30', '5.6'],
            description: "Percona Server for MySQL version",
            name: 'PS_VERSION')
        choice(
            choices: ['8.0', '5.7', '5.6'],
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        choice(
            choices: ['15','14', '13', '12', '11'],
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION')
        choice(
            choices: ['16.1','15.5', '14.10', '13.13', '12.17', '11.22'],
            description: 'Percona Distribution for PostgreSQL',
            name: 'PDPGSQL_VERSION')
        choice(
            choices: ['10.6', '10.5', '10.4', '10.3', '10.2'],
            description: "MariaDB Server version",
            name: 'MD_VERSION')
        choice(
            choices: ['7.0', '6.0', '5.0', '4.4'],
            description: "Percona Server for MongoDB version",
            name: 'MO_VERSION')
        choice(
            choices: ['4.4', '4.2', '4.0', '7.0', '6.0', '5.0.2'],
            description: "Official MongoDB version from MongoDB Inc",
            name: 'MODB_VERSION')
        choice(
            choices: ['perfschema', 'slowlog'],
            description: "Query Source for Monitoring",
            name: 'QUERY_SOURCE')
        choice(
            choices: ['dev','prod'],
            description: 'Prod or Dev version service',
            name: 'VERSION_SERVICE_VERSION')
        string(
            defaultValue: '',
            description: '''
            Docker image for version service, use it if you want to run your own version service.
            ''',
            name: 'VERSION_SERVICE_IMAGE')
        text(
            defaultValue: '-e PMM_DEBUG=1 -e PERCONA_TEST_TELEMETRY_INTERVAL=10s  -e PERCONA_TEST_PLATFORM_PUBLIC_KEY=RWTkF7Snv08FCboTne4djQfN5qbrLfAjb8SY3/wwEP+X5nUrkxCEvUDJ -e PERCONA_PORTAL_URL=https://portal-dev.percona.com  -e PERCONA_TEST_PLATFORM_ADDRESS=https://check-dev.percona.com:443',
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
        choice(
            choices: ['true', 'false'],
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
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for pmm-qa branch',
            name: 'PMM_QA_GIT_COMMIT_HASH')
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
                        slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        if (env.OWNER_SLACK) {
                            slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
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
                    sh """
                        set -o errexit
                        set -o xtrace

                        echo "${DEFAULT_SSH_KEYS}" >> /home/ec2-user/.ssh/authorized_keys
                        if [ -n "${SSH_KEY}" ]; then
                            echo '${SSH_KEY}' >> /home/ec2-user/.ssh/authorized_keys
                        fi

                        sudo yum -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm
                        sudo rpm --import /etc/pki/rpm-gpg/PERCONA-PACKAGING-KEY
                        sudo yum repolist

                        sudo yum install sysbench mysql -y
                        sudo mkdir -p /srv/pmm-qa || :
                        pushd /srv/pmm-qa
                            sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                            sudo git checkout ${PMM_QA_GIT_COMMIT_HASH}
                            sudo wget https://raw.githubusercontent.com/Percona-QA/percona-qa/master/get_download_link.sh
                            sudo chmod 755 get_download_link.sh
                        popd
                    """
                }
                script {
                    def node = Jenkins.instance.getNode(env.VM_NAME)
                    Jenkins.instance.removeNode(node)
                    Jenkins.instance.addNode(node)
                }
            }
        }
        stage('Run Docker') {
            when {
                expression { env.CLIENT_INSTANCE == "no" }
            }
            steps {
                script {
                    env.CHANGE_USER_PASSWORD_UTILITY = changeUserPasswordUtility(DOCKER_VERSION)
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                                sh '''
                                    set -o errexit
                                    set -o xtrace
                                    if [[ ${PMM_VERSION} == pmm2 ]]; then
                                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                                        docker create \
                                            -v /srv \
                                            --name ${VM_NAME}-data \
                                            ${DOCKER_VERSION} /bin/true

                                        if [ ${VERSION_SERVICE_VERSION} == dev ]; then
                                            export ENV_VARIABLE="${DOCKER_ENV_VARIABLE} -e PERCONA_TEST_VERSION_SERVICE_URL=https://check-dev.percona.com/versions/v1"
                                        else
                                            export ENV_VARIABLE="${DOCKER_ENV_VARIABLE} -e PERCONA_TEST_VERSION_SERVICE_URL=https://check.percona.com/versions/v1"
                                        fi

                                        if [ -n "${VERSION_SERVICE_IMAGE}" ]; then
                                            export ENV_VARIABLE="${DOCKER_ENV_VARIABLE} -e PERCONA_TEST_VERSION_SERVICE_URL=http://${VM_NAME}-version-service/versions/v1"
                                        else
                                            export ENV_VARIABLE="${DOCKER_ENV_VARIABLE}"
                                        fi
                                        docker network create pmm-qa || true

                                        docker run -d \
                                            -p 80:80 \
                                            -p 443:443 \
                                            -p 9000:9000 \
                                            --volumes-from ${VM_NAME}-data \
                                            --name ${VM_NAME}-server \
                                            --network pmm-qa \
                                            --restart always \
                                            $ENV_VARIABLE \
                                            ${DOCKER_VERSION}

                                        sleep 10
                                        docker logs ${VM_NAME}-server

                                        if [ ${ADMIN_PASSWORD} != admin ]; then
                                            if [ ${CHANGE_USER_PASSWORD_UTILITY} == yes ]; then
                                                docker exec ${VM_NAME}-server change-admin-password ${ADMIN_PASSWORD}
                                            else
                                                docker exec ${VM_NAME}-server grafana-cli --homepath /usr/share/grafana --configOverrides cfg:default.paths.data=/srv/grafana admin reset-admin-password ${ADMIN_PASSWORD}
                                            fi
                                        fi
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
                            sh """
                                set -o errexit
                                set -o xtrace
                                docker run --name ${VM_NAME}-version-service -d --hostname=${VM_NAME}-version-service -e SERVE_HTTP=true -e GW_PORT=80 ${VERSION_SERVICE_IMAGE}
                                docker network create ${VM_NAME}-network
                                docker network connect ${VM_NAME}-network ${VM_NAME}-version-service
                                docker network connect ${VM_NAME}-network ${VM_NAME}-server
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
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace

                                # exclude unavailable mirrors
                                docker exec ${VM_NAME}-server bash -c "echo exclude=mirror.es.its.nyu.edu | tee -a /etc/yum/pluginconf.d/fastestmirror.conf"
                                docker exec ${VM_NAME}-server yum update -y percona-release
                                docker exec ${VM_NAME}-server sed -i'' -e 's^/release/^/testing/^' /etc/yum.repos.d/pmm2-server.repo
                                docker exec ${VM_NAME}-server percona-release enable pmm2-client testing
                                docker exec ${VM_NAME}-server yum clean all
                                docker exec ${VM_NAME}-server yum clean metadata
                            """
                        }
                    }
                }
            }
        }
        stage('Enable Experimental Repo') {
            when {
                expression { env.PMM_VERSION == "pmm2" && env.CLIENT_INSTANCE == "no" && env.ENABLE_EXPERIMENTAL_REPO == "yes" && env.ENABLE_TESTING_REPO == "no" }
            }
            steps {
                script {
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe']) {
                        node(env.VM_NAME){
                            sh """
                                set -o errexit
                                set -o xtrace
                                docker exec ${VM_NAME}-server bash -c "echo exclude=mirror.es.its.nyu.edu | tee -a /etc/yum/pluginconf.d/fastestmirror.conf"
                                docker exec ${VM_NAME}-server yum update -y percona-release
                                docker exec ${VM_NAME}-server sed -i'' -e 's^/release/^/experimental/^' /etc/yum.repos.d/pmm2-server.repo
                                docker exec ${VM_NAME}-server percona-release enable pmm2-client experimental
                                docker exec ${VM_NAME}-server yum clean all
                                docker exec ${VM_NAME}-server yum clean metadata
                            """
                        }
                    }
                }
            }
        }
        stage('Run Clients') {
            steps {
                node(env.VM_NAME){
                    setupPMMClient(SERVER_IP, CLIENT_VERSION.trim(), PMM_VERSION, ENABLE_PULL_MODE, ENABLE_TESTING_REPO, CLIENT_INSTANCE, 'aws-staging', ADMIN_PASSWORD)
                    script {
                        env.PMM_REPO="experimental"
                        if(env.CLIENT_VERSION == "pmm2-rc") {
                            env.PMM_REPO="testing"
                        }

                    }
                    sh '''
                        set -o errexit
                        set -o xtrace
                        export PATH=$PATH:/usr/sbin
                        export PMM_CLIENT_VERSION=${CLIENT_VERSION}
                        if [[ ${CLIENT_VERSION} == dev-latest ]]; then
                                export PMM_CLIENT_VERSION="latest"
                        fi
                        [ -z "${CLIENTS}" ] && exit 0 || :

                        if [[ ${PMM_VERSION} == pmm2 ]]; then

                            export PMM_SERVER_IP=${SERVER_IP}

                            if [[ ${CLIENT_VERSION} != dev-latest ]]; then
                                export PATH="`pwd`/pmm2-client/bin:$PATH"
                            fi
                            if [[ ${CLIENT_INSTANCE} == no ]]; then
                                export PMM_SERVER_IP=${IP}
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
                                --pmm2-server-ip=$PMM_SERVER_IP
                        fi
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
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${env.IP}"
                    }
                }
            }
        }
        failure {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                    set -o xtrace
                    export REQUEST_ID=$(cat REQUEST_ID)
                    if [ -n "$REQUEST_ID" ]; then
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids $REQUEST_ID
                        aws ec2 --region us-east-2 terminate-instances --instance-ids $(cat ID)
                    fi
                '''
            }
            script {
                if (params.NOTIFY == "true") {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed, owner: @${OWNER},\nURL: ${BUILD_URL}"
                    if (env.OWNER_SLACK) {
                        slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build failed,\nURL: ${BUILD_URL}"
                    }
                }
            }
        }
        cleanup {
            deleteDir()
        }
    }
}
