void runTest(String TEST_SCRIPT, String MONGO_VERSION, String BCP_TYPE) {
    sh """
        sudo chmod 777 -R e2e-tests/docker/backups
        export MONGODB_VERSION=${MONGO_VERSION}
        export TESTS_BCP_TYPE=${BCP_TYPE}
        ./e2e-tests/${TEST_SCRIPT}
    """
}

void prepareCluster() {
    sh """
        docker kill \$(docker ps -a -q) || true
        docker rm \$(docker ps -a -q) || true
        docker rmi -f \$(docker images -q | uniq) || true
        sudo rm -rf ./*
    """

    git poll: false, branch: params.PBM_BRANCH, url: 'https://github.com/percona/percona-backup-mongodb.git'

    sh """
        sudo curl -L "https://github.com/docker/compose/releases/download/v2.16.0/docker-compose-linux-x86_64" -o /usr/local/bin/docker-compose
        sudo chmod +x /usr/local/bin/docker-compose
        openssl rand -base64 756 > ./e2e-tests/docker/keyFile
    """
}

library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])


pipeline {
    agent {
        label 'micro-amazon'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        string(name: 'PBM_BRANCH', defaultValue: 'main', description: 'PBM branch')
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PBM_BRANCH}"
                }
            }
        }
        stage('Run tests for PBM') {
            parallel {
                stage('New cluster 4.2 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-new-cluster', '4.2', 'logical')
                    }
                }
                stage('New cluster 4.4 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-new-cluster', '4.4', 'logical')
                    }
                }
                stage('New cluster 5.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-new-cluster', '5.0', 'logical')
                    }
                }
                stage('New cluster 6.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-new-cluster', '6.0', 'logical')
                    }
                }

                stage('Sharded 4.2 logical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster()
                        runTest('run-sharded', '4.2', 'logical')
                    }
                }
                stage('Sharded 4.4 logical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster()
                        runTest('run-sharded', '4.4', 'logical')
                    }
                }
                stage('Sharded 5.0 logical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster()
                        runTest('run-sharded', '5.0', 'logical')
                    }
                }
                stage('Sharded 6.0 logical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster()
                        runTest('run-sharded', '6.0', 'logical')
                    }
                }

                stage('Non-sharded 4.2 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-rs', '4.2', 'logical')
                    }
                }
                stage('Non-sharded 4.4 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-rs', '4.4', 'logical')
                    }
                }
                stage('Non-sharded 5.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-rs', '5.0', 'logical')
                    }
                }
                stage('Non-sharded 6.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-rs', '6.0', 'logical')
                    }
                }

                stage('Single-node 4.2 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-single', '4.2', 'logical')
                    }
                }
                stage('Single-node 4.4 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-single', '4.4', 'logical')
                    }
                }
                stage('Single-node 5.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-single', '5.0', 'logical')
                    }
                }
                stage('Single-node 6.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-single', '6.0', 'logical')
                    }
                }

                stage('Sharded 4.2 physical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster()
                        runTest('run-sharded', '4.2', 'physical')
                    }
                }
                stage('Sharded 4.4 physical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster()
                        runTest('run-sharded', '4.4', 'physical')
                    }
                }
                stage('Sharded 5.0 physical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster()
                        runTest('run-sharded', '5.0', 'physical')
                    }
                }
                stage('Sharded 6.0 physical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster()
                        runTest('run-sharded', '6.0', 'physical')
                    }
                }

                stage('Non-sharded 4.2 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-rs', '4.2', 'physical')
                    }
                }
                stage('Non-sharded 4.4 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-rs', '4.4', 'physical')
                    }
                }
                stage('Non-sharded 5.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-rs', '5.0', 'physical')
                    }
                }
                stage('Non-sharded 6.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-rs', '6.0', 'physical')
                    }
                }

                stage('Single-node 4.2 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-single', '4.2', 'physical')
                    }
                }
                stage('Single-node 4.4 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-single', '4.4', 'physical')
                    }
                }
                stage('Single-node 5.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-single', '5.0', 'physical')
                    }
                }
                stage('Single-node 6.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster()
                        runTest('run-single', '6.0', 'physical')
                    }
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}
