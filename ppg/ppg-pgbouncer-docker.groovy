library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        IMAGE_VER = "${params.PGBOUNCER_VERSION.tokenize('-')[0]}-${params.BUILD_NUMBER}"
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
	string(name: 'PGBOUNCER_VERSION', defaultValue: '1.22.1-1', description: 'pgBouncer version')
        string(name: 'BUILD_NUMBER', defaultValue: '1', description: 'Image Build Number')
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
                    currentBuild.displayName = "${params.PPG_REPO}-${params.PPG_VERSION} pgbouncer-${env.IMAGE_VER}"
                }
            }
        }
        stage ('Build image') {
            when {
                not { environment name: 'TARGET_REPO', value: 'DockerHub' }
            }
            steps {
                sh """
                    export DOCKER_CLI_EXPERIMENTAL=enabled
                    sudo mkdir -p /usr/libexec/docker/cli-plugins/
                    sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo systemctl restart docker
                    export DOCKER_BUILDKIT=1

                    git clone https://github.com/percona/percona-docker
                    cd percona-docker/percona-pgbouncer
                    sed -E "s/ARG PG_VERSION=(.+)/ARG PG_VERSION=${params.PPG_VERSION}/" -i Dockerfile
                    sed -E "s/ARG PPG_REPO=(.+)/ARG PPG_REPO=${params.PPG_REPO}/" -i Dockerfile
                    docker build --platform=linux/amd64 --no-cache --provenance=false -t percona-pgbouncer .
                    """
            }
        }
        stage ('Run trivy analyzer') {
            when {
                not { environment name: 'TARGET_REPO', value: 'DockerHub' }
            }
            steps {
                installTrivy(method: 'binary', junitTpl: true)
                sh """
                    if [ ${params.PPG_REPO} = "release" ]; then
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-pgbouncer
                    else
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-pgbouncer
                    fi
               """
            }
            post {
                always {
                    junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
        stage ('Push image to aws ecr') {
            when {
                environment name: 'TARGET_REPO', value: 'AWS_ECR'
            }
            steps {
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                     sh """
                         curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                         if [ -f "/usr/bin/yum" ] ; then sudo yum install -y unzip ; else sudo apt-get update && apt-get -y install unzip ; fi
                         unzip -o awscliv2.zip
                         sudo ./aws/install
                         aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/e7j3v3n0
                         docker tag percona-pgbouncer public.ecr.aws/e7j3v3n0/percona-pgbouncer-build:percona-pgbouncer:${env.IMAGE_VER}
                         docker push public.ecr.aws/e7j3v3n0/percona-pgbouncer-build:percona-pgbouncer:${env.IMAGE_VER}
                         if [ ${params.LATEST} = "yes" ]; then
                            docker tag percona-pgbouncer public.ecr.aws/e7j3v3n0/percona-pgbouncer:latest
                            docker push public.ecr.aws/e7j3v3n0/percona-pgbouncer:latest
                         fi
                     """
                }
            }
        }
        stage ('Push images to perconalab') {
            when {
                environment name: 'TARGET_REPO', value: 'PerconaLab'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         MAJ_VER=\$(echo ${params.PGBOUNCER_VERSION} | cut -f1 -d'-')
                         docker login -u '${USER}' -p '${PASS}'
                         docker tag percona-pgbouncer perconalab/percona-pgbouncer:${env.IMAGE_VER}-amd64
                         docker push perconalab/percona-pgbouncer:${env.IMAGE_VER}-amd64
                         docker tag percona-pgbouncer perconalab/percona-pgbouncer:\$MAJ_VER-amd64
                         docker push perconalab/percona-pgbouncer:\$MAJ_VER-amd64
                         if [ ${params.LATEST} = "yes" ]; then
                            docker tag percona-pgbouncer perconalab/percona-pgbouncer:latest
                            docker push perconalab/percona-pgbouncer:latest
                         fi
                     """
                }
            }
        }
        stage ('Push images to official percona docker registry') {
            when {
                environment name: 'TARGET_REPO', value: 'DockerHub'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         export DOCKER_CLI_EXPERIMENTAL=enabled
                         sudo mkdir -p /usr/libexec/docker/cli-plugins/
                         sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                         sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                         sudo systemctl restart docker
                         MAJ_VER=\$(echo ${params.PGBOUNCER_VERSION} | cut -f1 -d'-')
                         docker login -u '${USER}' -p '${PASS}'
                         docker buildx imagetools create \\
                             -t percona/percona-pgbouncer:${env.IMAGE_VER}-amd64 \\
                             perconalab/percona-pgbouncer:${env.IMAGE_VER}-amd64
                         docker buildx imagetools create \\
                             -t percona/percona-pgbouncer:\$MAJ_VER-amd64 \\
                             perconalab/percona-pgbouncer:\$MAJ_VER-amd64
                         if [ ${params.LATEST} = "yes" ]; then
                             docker buildx imagetools create \\
                                 -t percona/percona-pgbouncer:latest \\
                                 perconalab/percona-pgbouncer:latest
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
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Building of Percona pgBouncer ${env.IMAGE_VER} repo ${PPG_REPO} succeed")
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of Percona pgBouncer ${env.IMAGE_VER} repo ${PPG_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of Percona pgBouncer ${env.IMAGE_VER} repo ${PPG_REPO} failed - [${BUILD_URL}]")
        }
    }
}
