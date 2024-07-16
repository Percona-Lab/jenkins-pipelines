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
        string(name: 'PGBOUNCER_VERSION', defaultValue: '1.22.1-1', description: 'pgBouncer version')
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
                    MAJ_VER=\$(echo ${params.PGBOUNCER_VERSION} | cut -f1 -d'-')
                    echo \$MAJ_VER
                    git clone https://github.com/percona/percona-docker
                    cd percona-docker/percona-pgbouncer
                    sed -E "s/ENV PG_VERSION (.+)/ENV PG_VERSION ${params.PPG_VERSION}/" -i Dockerfile
                    sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile
                    docker build . -t percona-pgbouncer -f Dockerfile
                    """
            }
        }
        stage ('Push images to perconalab') {
            when {
                environment name: 'TARGET_REPO', value: 'PerconaLab'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub1.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=\$(echo ${params.PGBOUNCER_VERSION})
                         docker tag percona-pgbouncer perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-arm64
                         docker tag percona-pgbouncer perconalab/percona-pgbouncer:\$MAJ_VER-arm64
                         docker push perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-arm64
                         docker push perconalab/percona-pgbouncer:\$MAJ_VER-arm64

                         docker manifest create --amend perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi \
                            perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION} \
                            perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-arm64
                         docker manifest annotate perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi \
                            perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi \
                            perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION} --os linux --arch amd64
                         docker manifest inspect perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi
                         docker manifest push perconalab/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi

                         docker manifest create --amend perconalab/percona-pgbouncer:\$MAJ_VER-multi \
                            perconalab/percona-pgbouncer:\$MAJ_VER \
                            perconalab/percona-pgbouncer:\$MAJ_VER-arm64
                         docker manifest annotate perconalab/percona-pgbouncer:\$MAJ_VER-multi \
                            perconalab/percona-pgbouncer:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-pgbouncer:\$MAJ_VER-multi \
                            perconalab/percona-pgbouncer:\$MAJ_VER --os linux --arch amd64
                         docker manifest inspect perconalab/percona-pgbouncer:\$MAJ_VER-multi
                         docker manifest push perconalab/percona-pgbouncer:\$MAJ_VER-multi

                         if [ ${params.LATEST} = "yes" ]; then
                            docker manifest create --amend perconalab/percona-pgbouncer:latest \
                               perconalab/percona-pgbouncer:\$MAJ_VER \
                               perconalab/percona-pgbouncer:\$MAJ_VER-arm64
                            docker manifest annotate perconalab/percona-pgbouncer:latest \
                               perconalab/percona-pgbouncer:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate perconalab/percona-pgbouncer:latest \
                               perconalab/percona-pgbouncer:\$MAJ_VER --os linux --arch amd64
                            docker manifest inspect perconalab/percona-pgbouncer:latest
                            docker manifest push perconalab/percona-pgbouncer:latest
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
                withCredentials([usernamePassword(credentialsId: 'hub1.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=\$(echo ${params.PGBOUNCER_VERSION} | cut -f1 -d'-')
                         docker tag percona-pgbouncer percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-arm64
                         docker tag percona-pgbouncer percona/percona-pgbouncer:\$MAJ_VER-arm64
                         docker push percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-arm64
                         docker push percona/percona-pgbouncer:\$MAJ_VER-arm64

                         docker manifest create --amend percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi \
                            percona/percona-pgbouncer:${params.PGBOUNCER_VERSION} \
                            percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-arm64
                         docker manifest annotate percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi \
                            percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi \
                            percona/percona-pgbouncer:${params.PGBOUNCER_VERSION} --os linux --arch amd64
                         docker manifest inspect percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi
                         docker manifest push percona/percona-pgbouncer:${params.PGBOUNCER_VERSION}-multi

                         docker manifest create --amend percona/percona-pgbouncer:\$MAJ_VER-multi \
                            percona/percona-pgbouncer:\$MAJ_VER \
                            percona/percona-pgbouncer:\$MAJ_VER-arm64
                         docker manifest annotate percona/percona-pgbouncer:\$MAJ_VER-multi \
                            percona/percona-pgbouncer:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-pgbouncer:\$MAJ_VER-multi \
                            percona/percona-pgbouncer:\$MAJ_VER --os linux --arch amd64
                         docker manifest inspect percona/percona-pgbouncer:\$MAJ_VER-multi
                         docker manifest push percona/percona-pgbouncer:\$MAJ_VER-multi

                         if [ ${params.LATEST} = "yes" ]; then
                            docker manifest create --amend percona/percona-pgbouncer:latest \
                               percona/percona-pgbouncer:\$MAJ_VER \
                               percona/percona-pgbouncer:\$MAJ_VER-arm64
                            docker manifest annotate percona/percona-pgbouncer:latest \
                               percona/percona-pgbouncer:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate percona/percona-pgbouncer:latest \
                               percona/percona-pgbouncer:\$MAJ_VER --os linux --arch amd64
                            docker manifest inspect percona/percona-pgbouncer:latest
                            docker manifest push percona/percona-pgbouncer:latest
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
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Building of Percona pgBouncer ${PGBOUNCER_VERSION} for ARM, repo ${PPG_REPO} succeed")
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of Percona pgBouncer ${PGBOUNCER_VERSION} fro ARM, repo ${PPG_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of Percona pgBouncer ${PGBOUNCER_VERSION} for ARM, repo ${PPG_REPO} failed - [${BUILD_URL}]")
        }
    }
}
