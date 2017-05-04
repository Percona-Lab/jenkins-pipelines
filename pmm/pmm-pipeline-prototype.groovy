void runStaging(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS) {
    stagingJob = build job: 'pmm-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'false')
    ]
    env.VM_IP   = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://${VM_IP}"
}

void destroyStaging(IP) {
    build job: 'pmm-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

pipeline {
    agent {
        label 'virtualbox'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker version',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
    }

    stages {
        stage('Preparation') {
            steps {
                slackSend channel: '#pmm-jenkins', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${env.BUILD_URL}"

                // clean up workspace and fetch pmm-qa repository
                cleanWs deleteDirs: true, notFailBuild: true
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-QA/pmm-qa.git'

                // run any preparation steps on pmm-qa repo
                sh 'ls -la'
            }
        }
        stage('Start staging') {
            steps {
                runStaging(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1')
            }
        }
        stage('Sanity check') {
            steps {
                sh "curl --silent --insecure '${PMM_URL}/prometheus/targets' | grep localhost:9090"
            }
        }
        stage('Run Test') {
            steps {
                // run some command on jenkins node
                sh """
                    echo bash on jenkins node

                    # generete fake test result
                    echo "ok 1\nok 2\n1..2" > result.tap
                """

                // run some command inside VM
                sh """
                    ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@${VM_IP} "
                        set -o xtrace

                        echo bash inside VM
                        sudo pmm-admin list
                    "
                """

                // run some command inside Docker
                sh """
                    ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@${VM_IP} "
                        docker exec -i ${VM_NAME}-server sh -c '
                            set -o xtrace

                            echo bash inside docker
                            supervisorctl restart consul
                        '
                    "
                """
            }
        }
        stage('Stop staging') {
            steps {
                destroyStaging(VM_IP)
            }
        }
    }
    post {
        success {
            slackSend channel: '#pmm-jenkins', color: '#00FF00', message: "[${JOB_NAME}]: build finished"

            // proccess test result
            archiveArtifacts '*.tap'
            step([$class: "TapPublisher", testResults: '*.tap'])
        }
        failure {
            slackSend channel: '#pmm-jenkins', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
        }
    }
}
