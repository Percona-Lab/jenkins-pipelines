library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker-32gb-aarch64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'PPG_REPO', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PPG_VERSION', defaultValue: '16.1', description: 'PPG version')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab','DockerHub'], description: 'Target repo for docker image, use DockerHub for release only')
        choice(name: 'LATEST', choices: ['no','yes'], description: 'Tag image as latest')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PPG_REPO}-${params.PPG_VERSION}"
                }
            }
        }
        stage ('Build image') {
            steps {
                sh """
                    MAJ_VER=16
                    echo \$MAJ_VER
                    git clone https://github.com/EvgeniyPatlan/percona-docker
                    cd percona-docker/percona-distribution-postgresql-16
                    docker build . -t percona-distribution-postgresql -f Dockerfile.aarch64
                    """
            }
        }
        stage ('Push images to perconalab') {
            when {
                environment name: 'TARGET_REPO', value: 'PerconaLab'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=16
                         docker tag percona-distribution-postgresql perconalab/percona-distribution-postgresql:16.1-arm64
                         docker tag percona-distribution-postgresql perconalab/percona-distribution-postgresql:16-arm64
                         docker push perconalab/percona-distribution-postgresql:16.1-arm64
                         docker push perconalab/percona-distribution-postgresql:16-arm64

                         docker manifest create perconalab/percona-distribution-postgresql:16.1-multi \
                            perconalab/percona-distribution-postgresql:16.1 \
                            perconalab/percona-distribution-postgresql:16.1-arm64
                         docker manifest annotate perconalab/percona-distribution-postgresql:16.1-multi \
                            perconalab/percona-distribution-postgresql:16.1-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-distribution-postgresql:16.1-multi \
                            perconalab/percona-distribution-postgresql:16.1 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-distribution-postgresql:16.1-multi
                         docker manifest push perconalab/percona-distribution-postgresql:16.1-multi

                         docker manifest create perconalab/percona-distribution-postgresql:16-multi \
                            perconalab/percona-distribution-postgresql:16 \
                            perconalab/percona-distribution-postgresql:16-arm64
                         docker manifest annotate perconalab/percona-distribution-postgresql:16-multi \
                            perconalab/percona-distribution-postgresql:16-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-distribution-postgresql:16-multi \
                            perconalab/percona-distribution-postgresql:16 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-distribution-postgresql:16-multi
                         docker manifest push perconalab/percona-distribution-postgresql:16-multi

                     """
                }
            }
        }
    }
    post {
        always {
            sh """
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            """
            deleteDir()
        }
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} for ARM, repo ${PPG_REPO} succeed")
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} fro ARM, repo ${PPG_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} for ARM, repo ${PPG_REPO} failed - [${BUILD_URL}]")
        }
    }
}
