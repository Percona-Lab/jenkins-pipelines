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
        string(name: 'PGBACKREST_VERSION', defaultValue: '2.51-1', description: 'pgBackrest version')
        choice(name: 'PPG_REPO', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PPG_VERSION', defaultValue: '17.0', description: 'PPG version')
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
                    MAJ_VER=\$(echo ${params.PGBACKREST_VERSION} | cut -f1 -d'-')
                    echo \$MAJ_VER
                    git clone https://github.com/percona/percona-docker
                    cd percona-docker/percona-pgbackrest
                    sed -E "s/ENV PG_VERSION (.+)/ENV PG_VERSION ${params.PPG_VERSION}/" -i Dockerfile
                    sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile
                    docker build . -t percona-pgbackrest -f Dockerfile
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
                         MAJ_VER=\$(echo ${params.PGBACKREST_VERSION})
                         docker tag percona-pgbackrest perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION}-arm64
                         docker tag percona-pgbackrest perconalab/percona-pgbackrest:\$MAJ_VER-arm64
                         docker push perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION}-arm64
                         docker push perconalab/percona-pgbackrest:\$MAJ_VER-arm64

                         docker manifest create --amend perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION} \
                            perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION}-amd64 \
                            perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION}-arm64
                         docker manifest annotate perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION} \
                            perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION} \
                            perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION}-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION}
                         docker manifest push perconalab/percona-pgbackrest:${params.PGBACKREST_VERSION}

                         docker manifest create --amend perconalab/percona-pgbackrest:\$MAJ_VER \
                            perconalab/percona-pgbackrest:\$MAJ_VER-amd64 \
                            perconalab/percona-pgbackrest:\$MAJ_VER-arm64
                         docker manifest annotate perconalab/percona-pgbackrest:\$MAJ_VER \
                            perconalab/percona-pgbackrest:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-pgbackrest:\$MAJ_VER \
                            perconalab/percona-pgbackrest:\$MAJ_VER-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-pgbackrest:\$MAJ_VER
                         docker manifest push perconalab/percona-pgbackrest:\$MAJ_VER

                         if [ ${params.LATEST} = "yes" ]; then
                            docker manifest create --amend perconalab/percona-pgbackrest:latest \
                               perconalab/percona-pgbackrest:\$MAJ_VER-amd64 \
                               perconalab/percona-pgbackrest:\$MAJ_VER-arm64
                            docker manifest annotate perconalab/percona-pgbackrest:latest \
                               perconalab/percona-pgbackrest:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate perconalab/percona-pgbackrest:latest \
                               perconalab/percona-pgbackrest:\$MAJ_VER-amd64 --os linux --arch amd64
                            docker manifest inspect perconalab/percona-pgbackrest:latest
                            docker manifest push perconalab/percona-pgbackrest:latest
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
                         MAJ_VER=\$(echo ${params.PGBACKREST_VERSION} | cut -f1 -d'-')
                         docker tag percona-pgbackrest percona/percona-pgbackrest:${params.PGBACKREST_VERSION}-arm64
                         docker tag percona-pgbackrest percona/percona-pgbackrest:\$MAJ_VER-arm64
                         docker push percona/percona-pgbackrest:${params.PGBACKREST_VERSION}-arm64
                         docker push percona/percona-pgbackrest:\$MAJ_VER-arm64

                         docker manifest create --amend percona/percona-pgbackrest:${params.PGBACKREST_VERSION} \
                            percona/percona-pgbackrest:${params.PGBACKREST_VERSION}-amd64 \
                            percona/percona-pgbackrest:${params.PGBACKREST_VERSION}-arm64
                         docker manifest annotate percona/percona-pgbackrest:${params.PGBACKREST_VERSION} \
                            percona/percona-pgbackrest:${params.PGBACKREST_VERSION}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-pgbackrest:${params.PGBACKREST_VERSION} \
                            percona/percona-pgbackrest:${params.PGBACKREST_VERSION}-amd64 --os linux --arch amd64
                         docker manifest inspect percona/percona-pgbackrest:${params.PGBACKREST_VERSION}
                         docker manifest push percona/percona-pgbackrest:${params.PGBACKREST_VERSION}

                         docker manifest create --amend percona/percona-pgbackrest:\$MAJ_VER \
                            percona/percona-pgbackrest:\$MAJ_VER-amd64 \
                            percona/percona-pgbackrest:\$MAJ_VER-arm64
                         docker manifest annotate percona/percona-pgbackrest:\$MAJ_VER \
                            percona/percona-pgbackrest:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-pgbackrest:\$MAJ_VER \
                            percona/percona-pgbackrest:\$MAJ_VER-amd64 --os linux --arch amd64
                         docker manifest inspect percona/percona-pgbackrest:\$MAJ_VER
                         docker manifest push percona/percona-pgbackrest:\$MAJ_VER

                         if [ ${params.LATEST} = "yes" ]; then
                            docker manifest create --amend percona/percona-pgbackrest:latest \
                               percona/percona-pgbackrest:\$MAJ_VER-amd64 \
                               percona/percona-pgbackrest:\$MAJ_VER-arm64
                            docker manifest annotate percona/percona-pgbackrest:latest \
                               percona/percona-pgbackrest:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate percona/percona-pgbackrest:latest \
                               percona/percona-pgbackrest:\$MAJ_VER-amd64 --os linux --arch amd64
                            docker manifest inspect percona/percona-pgbackrest:latest
                            docker manifest push percona/percona-pgbackrest:latest
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
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Building of Percona pgBackrest ${PGBACKREST_VERSION} for ARM, repo ${PPG_REPO} succeed")
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of Percona pgBackrest ${PGBACKREST_VERSION} fro ARM, repo ${PPG_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of Percona pgBackrest ${PGBACKREST_VERSION} for ARM, repo ${PPG_REPO} failed - [${BUILD_URL}]")
        }
    }
}
