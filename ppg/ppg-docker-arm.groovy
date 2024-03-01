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
        string(name: 'PPG_VERSION', defaultValue: '14.11', description: 'PPG version')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab','AWS_ECR','DockerHub'], description: 'Target repo for docker image, use DockerHub for release only')
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
                    MAJ_VER=14
                    echo \$MAJ_VER
                    git clone https://github.com/EvgeniyPatlan/percona-docker
                    cd percona-docker/percona-distribution-postgresql-14
                    docker build . -t percona-distribution-postgresql -f Dockerfile.aarch64
                    """
            }
        }
        stage ('Push images to percona') {
            when {
                environment name: 'TARGET_REPO', value: 'DockerHub'
                //environment name: 'TARGET_REPO', value: 'PerconaLab'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub1.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=14
                         docker tag percona-distribution-postgresql percona/percona-distribution-postgresql:14.11-arm64
                         docker tag percona-distribution-postgresql percona/percona-distribution-postgresql:14-arm64
                         docker push percona/percona-distribution-postgresql:14.11-arm64
                         docker push percona/percona-distribution-postgresql:14-arm64

                         docker manifest create --amend percona/percona-distribution-postgresql:14.11-multi \
                            percona/percona-distribution-postgresql:14.11 \
                            percona/percona-distribution-postgresql:14.11-arm64
                         docker manifest annotate percona/percona-distribution-postgresql:14.11-multi \
                            percona/percona-distribution-postgresql:14.11-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-distribution-postgresql:14.11-multi \
                            percona/percona-distribution-postgresql:14.11 --os linux --arch amd64
                         docker manifest inspect percona/percona-distribution-postgresql:14.11-multi
                         docker manifest push percona/percona-distribution-postgresql:14.11-multi

                         docker manifest create --amend percona/percona-distribution-postgresql:14-multi \
                            percona/percona-distribution-postgresql:14 \
                            percona/percona-distribution-postgresql:14-arm64
                         docker manifest annotate percona/percona-distribution-postgresql:14-multi \
                            percona/percona-distribution-postgresql:14-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-distribution-postgresql:14-multi \
                            percona/percona-distribution-postgresql:14 --os linux --arch amd64
                         docker manifest inspect percona/percona-distribution-postgresql:14-multi
                         docker manifest push percona/percona-distribution-postgresql:14-multi

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
