void runTest(String TEST_SCRIPT, String MONGO_VERSION, String BCP_TYPE) {
    sh """
        sudo chmod 777 -R e2e-tests/docker/backups
        export MONGODB_VERSION=${MONGO_VERSION}
        export TESTS_BCP_TYPE=${BCP_TYPE}
        ./e2e-tests/${TEST_SCRIPT}
    """
}

void prepareCluster(String TEST_TYPE) {
    sh """
        docker kill \$(docker ps -a -q) || true
        docker rm \$(docker ps -a -q) || true
        docker rmi -f \$(docker images -q | uniq) || true
        sudo rm -rf ./*
    """

    sh """
        sudo curl -L "https://github.com/docker/compose/releases/download/v2.24.7/docker-compose-linux-x86_64" -o /usr/local/bin/docker-compose
        sudo chmod +x /usr/local/bin/docker-compose

        wget https://download.docker.com/linux/static/stable/x86_64/docker-25.0.4.tgz
        tar -xvf docker-25.0.4.tgz
        sudo systemctl stop docker containerd
        sudo cp docker/* /usr/bin/
        sudo systemctl start docker containerd
    """

    git poll: false, branch: params.PBM_BRANCH, url: 'https://github.com/percona/percona-backup-mongodb.git'

    withCredentials([file(credentialsId: 'PBM-AWS-S3', variable: 'PBM_AWS_S3_YML'), file(credentialsId: 'PBM-GCS-S3', variable: 'PBM_GCS_S3_YML'), file(credentialsId: 'PBM-AZURE', variable: 'PBM_AZURE_YML')]) {
    sh """
        cp $PBM_AWS_S3_YML ./e2e-tests/docker/conf/aws.yaml
        cp $PBM_GCS_S3_YML ./e2e-tests/docker/conf/gcs.yaml
        cp $PBM_AZURE_YML ./e2e-tests/docker/conf/azure.yaml
        sed -i s:pbme2etest:pbme2etest-${TEST_TYPE}:g ./e2e-tests/docker/conf/aws.yaml
        sed -i s:pbme2etest:pbme2etest-${TEST_TYPE}:g ./e2e-tests/docker/conf/gcs.yaml
        sed -i s:pbme2etest:pbme2etest-${TEST_TYPE}:g ./e2e-tests/docker/conf/azure.yaml

        chmod 664 ./e2e-tests/docker/conf/aws.yaml
        chmod 664 ./e2e-tests/docker/conf/gcs.yaml
        chmod 664 ./e2e-tests/docker/conf/azure.yaml


        openssl rand -base64 756 > ./e2e-tests/docker/keyFile
    """
    }
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
        string(name: 'PBM_BRANCH', defaultValue: 'dev', description: 'PBM branch')
    }
    triggers {
        cron('0 3 * * 1')
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
                stage('New cluster 4.4 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('44-newc-logic')
                        runTest('run-new-cluster', '4.4', 'logical')
                    }
                }
                stage('New cluster 5.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('50-newc-logic')
                        runTest('run-new-cluster', '5.0', 'logical')
                    }
                }
                stage('New cluster 6.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('60-newc-logic')
                        runTest('run-new-cluster', '6.0', 'logical')
                    }
                }
                stage('New cluster 7.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('70-newc-logic')
			runTest('run-new-cluster', '7.0', 'logical')
                    }
                }
                stage('Sharded 4.4 logical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster('44-shrd-logic')
                        runTest('run-sharded', '4.4', 'logical')
                    }
                }
                stage('Sharded 5.0 logical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster('50-shrd-logic')
                        runTest('run-sharded', '5.0', 'logical')
                    }
                }
                stage('Sharded 6.0 logical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster('60-shrd-logic')
                        runTest('run-sharded', '6.0', 'logical')
                    }
                }
                stage('Sharded 7.0 logical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster('70-shrd-logic')
			runTest('run-sharded', '7.0', 'logical')
                    }
                }
                stage('Non-sharded 4.4 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('44-rs-logic')
                        runTest('run-rs', '4.4', 'logical')
                    }
                }
                stage('Non-sharded 5.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('50-rs-logic')
                        runTest('run-rs', '5.0', 'logical')
                    }
                }
                stage('Non-sharded 6.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('60-rs-logic')
                        runTest('run-rs', '6.0', 'logical')
                    }
                }
                stage('Non-sharded 7.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('70-rs-logic')
			runTest('run-rs', '7.0', 'logical')
                    }
                }
                stage('Single-node 4.4 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('44-single-logic')
                        runTest('run-single', '4.4', 'logical')
                    }
                }
                stage('Single-node 5.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('50-single-logic')
                        runTest('run-single', '5.0', 'logical')
                    }
                }
                stage('Single-node 6.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('60-single-logic')
                        runTest('run-single', '6.0', 'logical')
                    }
                }
                stage('Single-node 7.0 logical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('70-single-logic')
			runTest('run-single', '7.0', 'logical')
                    }
                }
                stage('Sharded 4.4 physical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster('44-shrd-phys')
                        runTest('run-sharded', '4.4', 'physical')
                    }
                }
                stage('Sharded 5.0 physical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster('50-shrd-phys')
                        runTest('run-sharded', '5.0', 'physical')
                    }
                }
                stage('Sharded 6.0 physical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster('60-shrd-phys')
                        runTest('run-sharded', '6.0', 'physical')
                    }
                }
                stage('Sharded 7.0 physical') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
			prepareCluster('70-shrd-phys')
			runTest('run-sharded', '7.0', 'physical')
                    }
                }
                stage('Non-sharded 4.4 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('44-rs-phys')
                        runTest('run-rs', '4.4', 'physical')
                    }
                }
                stage('Non-sharded 5.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('50-rs-phys')
                        runTest('run-rs', '5.0', 'physical')
                    }
                }
                stage('Non-sharded 6.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('60-rs-phys')
                        runTest('run-rs', '6.0', 'physical')
                    }
                }
                stage('Non-sharded 7.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('70-rs-phys')
			runTest('run-rs', '7.0', 'physical')
                    }
                }
                stage('Single-node 4.4 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('44-single-phys')
                        runTest('run-single', '4.4', 'physical')
                    }
                }
                stage('Single-node 5.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('50-single-phys')
                        runTest('run-single', '5.0', 'physical')
                    }
                }
                stage('Single-node 6.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('60-single-phys')
                        runTest('run-single', '6.0', 'physical')
                    }
                }
                stage('Single-node 7.0 physical') {
                    agent {
                        label 'docker'
                    }
                    steps {
			prepareCluster('70-single-phys')
			runTest('run-single', '7.0', 'physical')
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
