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
        string(name: 'PPG_VERSION', defaultValue: '12.19-1', description: 'PPG version')
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
                    MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                    echo \$MAJ_VER
                    MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                    echo \$MIN_VER
                    git clone https://github.com/Percona-Lab/postgresql-docker-ivee
                    cd postgresql-docker-ivee
                    sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile
                    sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile
                    sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile
                    docker build . -t postgresql-ivee -f Dockerfile
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
                         MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                         MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                         docker tag postgresql-ivee perconalab/postgresql-ivee:${params.PPG_VERSION}-arm64
                         docker tag postgresql-ivee perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER-arm64
                         docker tag postgresql-ivee perconalab/postgresql-ivee:\$MAJ_VER-arm64
                         docker push perconalab/postgresql-ivee:${params.PPG_VERSION}-arm64
                         docker push perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER-arm64
                         docker push perconalab/postgresql-ivee:\$MAJ_VER-arm64

                         docker manifest create --amend perconalab/postgresql-ivee:${params.PPG_VERSION} \
                            perconalab/postgresql-ivee:${params.PPG_VERSION}-amd64 \
                            perconalab/postgresql-ivee:${params.PPG_VERSION}-arm64
                         docker manifest annotate perconalab/postgresql-ivee:${params.PPG_VERSION} \
                            perconalab/postgresql-ivee:${params.PPG_VERSION}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/postgresql-ivee:${params.PPG_VERSION} \
                            perconalab/postgresql-ivee:${params.PPG_VERSION}-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/postgresql-ivee:${params.PPG_VERSION}
                         docker manifest push perconalab/postgresql-ivee:${params.PPG_VERSION}

                         docker manifest create --amend perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER \
                            perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER-amd64 \
                            perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER-arm64
                         docker manifest annotate perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER \
                            perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER \
                            perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER
                         docker manifest push perconalab/postgresql-ivee:\$MAJ_VER.\$MIN_VER

                         docker manifest create --amend perconalab/postgresql-ivee:\$MAJ_VER \
                            perconalab/postgresql-ivee:\$MAJ_VER-amd64 \
                            perconalab/postgresql-ivee:\$MAJ_VER-arm64
                         docker manifest annotate perconalab/postgresql-ivee:\$MAJ_VER \
                            perconalab/postgresql-ivee:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/postgresql-ivee:\$MAJ_VER \
                            perconalab/postgresql-ivee:\$MAJ_VER-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/postgresql-ivee:\$MAJ_VER
                         docker manifest push perconalab/postgresql-ivee:\$MAJ_VER

                         if [ ${params.LATEST} = "yes" ]; then
                            docker manifest create --amend perconalab/postgresql-ivee:latest \
                               perconalab/postgresql-ivee:\$MAJ_VER-amd64 \
                               perconalab/postgresql-ivee:\$MAJ_VER-arm64
                            docker manifest annotate perconalab/postgresql-ivee:latest \
                               perconalab/postgresql-ivee:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate perconalab/postgresql-ivee:latest \
                               perconalab/postgresql-ivee:\$MAJ_VER-amd64 --os linux --arch amd64
                            docker manifest inspect perconalab/postgresql-ivee:latest
                            docker manifest push perconalab/postgresql-ivee:latest
                         fi

                     """
                }
            }
        }
        stage ('Push images to percona') {
            when {
                environment name: 'TARGET_REPO', value: 'DockerHub'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                         MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                         docker tag postgresql-ivee percona/postgresql-ivee:${params.PPG_VERSION}-arm64
                         docker tag postgresql-ivee percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER-arm64
                         docker tag postgresql-ivee percona/postgresql-ivee:\$MAJ_VER-arm64
                         docker push percona/postgresql-ivee:${params.PPG_VERSION}-arm64
                         docker push percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER-arm64
                         docker push percona/postgresql-ivee:\$MAJ_VER-arm64

                         docker manifest create --amend percona/postgresql-ivee:${params.PPG_VERSION} \
                            percona/postgresql-ivee:${params.PPG_VERSION}-amd64 \
                            percona/postgresql-ivee:${params.PPG_VERSION}-arm64
                         docker manifest annotate percona/postgresql-ivee:${params.PPG_VERSION} \
                            percona/postgresql-ivee:${params.PPG_VERSION}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/postgresql-ivee:${params.PPG_VERSION} \
                            percona/postgresql-ivee:${params.PPG_VERSION}-amd64 --os linux --arch amd64
                         docker manifest inspect percona/postgresql-ivee:${params.PPG_VERSION}
                         docker manifest push percona/postgresql-ivee:${params.PPG_VERSION}

                         docker manifest create --amend percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER \
                            percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER-amd64 \
                            percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER-arm64
                         docker manifest annotate percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER \
                            percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER \
                            percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER-amd64 --os linux --arch amd64
                         docker manifest inspect percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER
                         docker manifest push percona/postgresql-ivee:\$MAJ_VER.\$MIN_VER

                         docker manifest create --amend percona/postgresql-ivee:\$MAJ_VER \
                            percona/postgresql-ivee:\$MAJ_VER-amd64 \
                            percona/postgresql-ivee:\$MAJ_VER-arm64
                         docker manifest annotate percona/postgresql-ivee:\$MAJ_VER \
                            percona/postgresql-ivee:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/postgresql-ivee:\$MAJ_VER \
                            percona/postgresql-ivee:\$MAJ_VER-amd64 --os linux --arch amd64
                         docker manifest inspect percona/postgresql-ivee:\$MAJ_VER
                         docker manifest push percona/postgresql-ivee:\$MAJ_VER

                         if [ ${params.LATEST} = "yes" ]; then
                            docker manifest create --amend percona/postgresql-ivee:latest \
                               percona/postgresql-ivee:\$MAJ_VER-amd64 \
                               percona/postgresql-ivee:\$MAJ_VER-arm64
                            docker manifest annotate percona/postgresql-ivee:latest \
                               percona/postgresql-ivee:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate percona/postgresql-ivee:latest \
                               percona/postgresql-ivee:\$MAJ_VER-amd64 --os linux --arch amd64
                            docker manifest inspect percona/postgresql-ivee:latest
                            docker manifest push percona/postgresql-ivee:latest
                         fi
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
