library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, CLIENT_INSTANCE, SERVER_IP) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e ENABLE_DBAAS=1 -e PERCONA_TEST_VERSION_SERVICE_URL=https://check-dev.percona.com/versions/v1'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.ADMIN_PASSWORD = "admin"
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${VM_IP}"
        env.PMM_UI_URL = "http://${VM_IP}/"
    }
}

void runClusterStaging(String PMM_QA_GIT_BRANCH) {
    clusterJob = build job: 'kubernetes-cluster-staging', parameters: [
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'DAYS', value: '1')
    ]
    env.CLUSTER_IP = clusterJob.buildVariables.IP
    env.KUBECONFIG = clusterJob.buildVariables.KUBECONFIG
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: "'@dbaas'",
            description: 'Pass test tags ex. @dbaas',
            name: 'TEST_TAGS')
        choice(
            choices: ['no', 'yes'],
            description: "Use this instance only as a client host",
            name: 'CLIENT_INSTANCE')
        choice(
            choices: ['no', 'yes'],
            description: "Run Tests for OVF supported Features",
            name: 'OVF_TEST')
        string (
            defaultValue: '',
            description: 'Value for Server Public IP, to use this instance just as client',
            name: 'SERVER_IP')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        text(
            defaultValue: '',
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
    }
    options {
        skipDefaultCheckout()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-ui-tests repository
                deleteDir()
                git poll: false,
                    branch: GIT_BRANCH,
                    url: 'https://github.com/percona/pmm-ui-tests.git'

                sh '''
                    docker-compose --version
                    sudo yum -y install mysql
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                        sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                        sudo chmod 755 pmm-tests/install-google-chrome.sh
                    popd
                    /srv/pmm-qa/pmm-tests/install_k8s_tools.sh --kubectl --sudo
                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium
                '''
            }
        }
        stage('Checkout Commit') {
            when {
                expression { env.GIT_COMMIT_HASH.length()>0 }
            }
            steps {
                sh 'git checkout ' + env.GIT_COMMIT_HASH
            }
        }
        stage('Setup PMM Server') {
            parallel {
                stage('Start Server') {
                    steps {
                        runStagingServer(DOCKER_VERSION, CLIENT_VERSION, CLIENT_INSTANCE, '127.0.0.1')
                    }
                }
                stage('Start PMM Cluster Staging Instance') {
                    when {
                        expression { env.OVF_TEST == "no" }
                    }
                    steps {
                        runClusterStaging('main')
                    }
                }
            }
        }
        stage('Setup') {
            stage('Sanity check') {
                steps {
                    sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
                }
            }
        }
        stage('Run UI Tests OVF') {
            options {
                timeout(time: 60, unit: "MINUTES")
            }
            when {
                expression { env.OVF_TEST == "yes" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export CHROMIUM_PATH=/usr/bin/chromium
                        ./node_modules/.bin/codeceptjs run --debug --steps --reporter mocha-multi -c pr.codecept.js --grep ${TEST_TAGS}
                    """
                }
            }
        }
        stage('Run UI Tests Docker') {
            options {
                timeout(time: 150, unit: "MINUTES")
            }
            when {
                expression { env.OVF_TEST == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export CHROMIUM_PATH=/usr/bin/chromium
                        export kubeconfig_minikube="${KUBECONFIG}"
                        echo "${KUBECONFIG}" > kubeconfig
                        export KUBECONFIG=./kubeconfig
                        kubectl get nodes
                        ./node_modules/.bin/codeceptjs run-multiple parallel --debug --steps --reporter mocha-multi -c pr.codecept.js --grep ${TEST_TAGS}
                    """
                }
            }
        }
    }
    post {
        always {
            // stop staging
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                ./node_modules/.bin/mochawesome-merge tests/output/parallel_chunk*/*.json > tests/output/combine_results.json || true
                ./node_modules/.bin/mochawesome-merge tests/output/*.json > tests/output/combine_results.json || true
                ./node_modules/.bin/marge tests/output/combine_results.json --reportDir tests/output/ --inline --cdn --charts || true
                docker-compose down
                docker rm -f $(sudo docker ps -a -q) || true
                docker volume rm $(sudo docker volume ls -q) || true
                sudo chown -R ec2-user:ec2-user . || true
            '''
            script {
                if(env.VM_NAME)
                {
                    destroyStaging(VM_NAME)
                }
                if(env.VM_CLIENT_NAME)
                {
                    destroyStaging(VM_CLIENT_NAME)
                }
                if(env.CLUSTER_IP)
                {
                    destroyStaging(CLUSTER_IP)
                }
            }
            script {
                if (env.OVF_TEST == "no") {
                    env.PATH_TO_REPORT_RESULTS = 'tests/output/parallel_chunk*/*.xml'
                } else {
                    env.PATH_TO_REPORT_RESULTS = 'tests/output/*.xml'
                }
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit env.PATH_TO_REPORT_RESULTS
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'tests/output/', reportFiles: 'combine_results.html', reportName: 'HTML Report', reportTitles: ''])
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    junit env.PATH_TO_REPORT_RESULTS
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'tests/output/', reportFiles: 'combine_results.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'tests/output/combine_results.html'
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'tests/output/parallel_chunk*/*.png'
                    archiveArtifacts artifacts: 'tests/output/*.png'
                }
            }
            allure([
                includeProperties: false,
                jdk: '',
                properties: [],
                reportBuildPolicy: 'ALWAYS',
                results: [[path: 'tests/output/allure']]
            ])
            sh '''
                sudo rm -r node_modules/
                sudo rm -r tests/output
            '''
            deleteDir()
        }
    }
}
