library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, VERSION_SERVICE_VERSION, ADMIN_PASSWORD) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'VERSION_SERVICE_VERSION', value: VERSION_SERVICE_VERSION),
        string(name: 'DOCKER_ENV_VARIABLE', value: ' -e ENABLE_DBAAS=1 -e PERCONA_TEST_VERSION_SERVICE_URL=https://check-dev.percona.com/versions/v1 -e PERCONA_TEST_DBAAS_PMM_CLIENT=perconalab/pmm-client:dev-latest'),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${VM_IP}"
    env.PMM_UI_URL = "http://${VM_IP}/"
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

def latestVersion = pmmVersion()
def versionsList = pmmVersion('dbaas');

pipeline {
    agent {
        label 'agent-amd64-ol9'
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
        choice(
            choices: versionsList,
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_VERSION')
        choice(
            choices: versionsList,
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: latestVersion,
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        choice(
            choices: ['UI', 'Docker'],
            description: "Select type of upgrade either UI or Docker way upgrade.",
            name: 'PMM_UPGRADE_TYPE')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server Tag to be Upgraded to via Docker way Upgrade',
            name: 'PMM_SERVER_TAG')
        string(
            defaultValue: 'pmm2023fortesting!',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD')
        choice(
            choices: ['dev','prod'],
            description: 'Prod or Dev version service',
            name: 'VERSION_SERVICE_VERSION')
        string(
            defaultValue: '',
            description: 'Custom build description',
            name: 'TEST_TAGS')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['Experimental', 'Testing', 'Release'],
            description: "Select Testing (RC Tesing), Experimental (dev-latest testing) or Release (latest released)",
            name: 'PMM_REPOSITORY')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    if(env.TEST_TAGS != "") {
                        currentBuild.description = env.TEST_TAGS
                    }
                }
                // clean up workspace and fetch pmm-ui-tests repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-ui-tests.git'
                sh '''
                    docker-compose --version
                    sudo yum -y install mysql
                    sudo amazon-linux-extras install epel -y
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                        sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                    popd
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
                        runStagingServer('percona/pmm-server:' + DOCKER_VERSION, CLIENT_VERSION, VERSION_SERVICE_VERSION, ADMIN_PASSWORD)
                    }
                }
                stage('Start PMM Cluster Staging Instance') {
                    steps {
                        runClusterStaging('main')
                    }
                }
            }
        }
        stage('Setup') {
            parallel {
                stage('Sanity check') {

                    steps {
                        sleep 120
                        sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
                    }
                }
                stage('Setup Node') {
                    steps {
                        sh """
                            envsubst < env.list > env.generated.list
                            npm ci
                        """
                    }
                }
            }
        }
        stage('Disable All Repos') {
            when {
                expression { env.PMM_REPOSITORY == "Release"}
            }
            steps{
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                            docker exec ${VM_NAME}-server sed -i'' -e 's^/experimental/^/release/^' /etc/yum.repos.d/pmm2-server.repo
                            docker exec ${VM_NAME}-server percona-release disable all
                            docker exec ${VM_NAME}-server yum clean all
                        '
                    """
                }
            }
        }
        stage('Enable Testing Repo') {
            when {
                expression { env.PMM_REPOSITORY == "Testing"}
            }
            steps{
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                            docker exec ${VM_NAME}-server sed -i'' -e 's^/release/^/testing/^' /etc/yum.repos.d/pmm2-server.repo
                            docker exec ${VM_NAME}-server percona-release enable pmm2-client testing
                            docker exec ${VM_NAME}-server yum clean all
                        '
                    """
                }
            }
        }
        stage('Enable Experimental Repo') {
            when {
                expression { env.PMM_REPOSITORY == "Experimental"}
            }
            steps{
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                            docker exec ${VM_NAME}-server yum update -y percona-release
                            docker exec ${VM_NAME}-server sed -i'' -e 's^/release/^/experimental/^' /etc/yum.repos.d/pmm2-server.repo
                            docker exec ${VM_NAME}-server percona-release enable pmm2-client experimental
                            docker exec ${VM_NAME}-server yum clean all
                        '
                    """
                }
            }
        }
        stage('Run UI Tests Before Upgrade') {
            options {
                timeout(time: 30, unit: "MINUTES")
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
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --grep '@upgrade-dbaas-before'
                        kubectl get pods
                    """
                }
            }
        }
        stage('Run UI Upgrade') {
            when {
                expression { env.PMM_UPGRADE_TYPE == "UI"}
            }
            options {
                timeout(time: 30, unit: "MINUTES")
            }
            steps {
                sh """
                    sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                    export PWD=\$(pwd);
                    export CHROMIUM_PATH=/usr/bin/chromium
                    ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --grep '@upgrade-dbaas-ui'
                """
            }
        }
        stage('Run Docker way Upgrade') {
            when {
                expression { env.PMM_UPGRADE_TYPE == "Docker"}
            }
            options {
                timeout(time: 30, unit: "MINUTES")
            }
            steps{
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                            docker stop ${VM_NAME}-server
                            docker rename ${VM_NAME}-server ${VM_NAME}-server-old
                            docker run -d -p 80:80 -p 443:443  --volumes-from ${VM_NAME}-data --name pmm-server-upgraded --restart always -e ENABLE_DBAAS=1 -e PERCONA_TEST_VERSION_SERVICE_URL=https://check-dev.percona.com/versions/v1 -e PERCONA_TEST_DBAAS_PMM_CLIENT=perconalab/pmm-client:dev-latest ${PMM_SERVER_TAG}
                        '
                    """
                }
            }
        }
        // stage('Unregister/Register Kubernetes Cluster') {
        //     options {
        //         timeout(time: 30, unit: "MINUTES")
        //     }
        //     steps {
        //         withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        //             sh """
        //                 sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
        //                 export PWD=\$(pwd);
        //                 export CHROMIUM_PATH=/usr/bin/chromium
        //                 export kubeconfig_minikube="${KUBECONFIG}"
        //                 echo "${KUBECONFIG}" > kubeconfig
        //                 export KUBECONFIG=./kubeconfig
        //                 ./node_modules/.bin/codeceptjs run-multiple parallel --steps --reporter mocha-multi -c pr.codecept.js --grep '@upgrade-dbaas-force-unregister'
        //                 sleep 300
        //             """
        //         }
        //     }
        // }
        // stage('Run DBaaS Migration Script') {
        //     options {
        //         timeout(time: 30, unit: "MINUTES")
        //     }
        //     steps {
        //         withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        //             sh """
        //             export kubeconfig_minikube="${KUBECONFIG}"
        //             echo "${KUBECONFIG}" > kubeconfig
        //             export KUBECONFIG=./kubeconfig
        //             kubectl get pods
        //             sudo yum install -y wget
        //             sudo wget https://raw.githubusercontent.com/percona/pmm/main/migrate-dbaas.py
        //             sudo chmod 755 migrate-dbaas.py
        //             for i in {1..5} ; do python3 migrate-dbaas.py && break & sleep 3; done
        //             sleep 120
        //         """
        //         }
        //     }
        // }
        stage('Run UI Tests After Upgrade') {
            options {
                timeout(time: 150, unit: "MINUTES")
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sleep 60
                    sh """
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export CHROMIUM_PATH=/usr/bin/chromium
                        export kubeconfig_minikube="${KUBECONFIG}"
                        echo "${KUBECONFIG}" > kubeconfig
                        export KUBECONFIG=./kubeconfig
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --grep '@upgrade-dbaas-after'
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
            '''
            script {
                if(env.VM_NAME)
                {
                    destroyStaging(VM_IP)
                }
                if(env.CLUSTER_IP)
                {
                    destroyStaging(CLUSTER_IP)
                }
            }
            script {
                env.PATH_TO_REPORT_RESULTS = 'tests/output/parallel_chunk*/*.xml'
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit env.PATH_TO_REPORT_RESULTS
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    junit env.PATH_TO_REPORT_RESULTS
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'tests/output/*.png'
                    archiveArtifacts artifacts: 'tests/output/parallel_chunk*/*.png'
                }
            }
            /*
            allure([
                includeProperties: false,
                jdk: '',
                properties: [],
                reportBuildPolicy: 'ALWAYS',
                results: [[path: 'tests/output/allure']]
            ])
            */
            sh '''
                sudo rm -r node_modules/
                sudo rm -r tests/output
            '''
            deleteDir()
        }
    }
}
