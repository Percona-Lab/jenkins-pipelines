library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        IMAGE_VER = "${params.PPG_VERSION.tokenize('-')[0]}-${params.BUILD_NUMBER}"
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        choice(name: 'PPG_REPO', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PPG_VERSION', defaultValue: '12.19-1', description: 'PPG version')
        string(name: 'BUILD_NUMBER', defaultValue: '1', description: 'Image Build Number')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab','AWS_ECR','DockerHub'], description: 'Target repo for docker image, use DockerHub for release only')
        choice(name: 'LATEST', choices: ['no','yes'], description: 'Tag image as latest')
        booleanParam(name: 'BUILD_UBI9', defaultValue: true, description: 'Build UBI9 image')
        booleanParam(name: 'BUILD_UBI8', defaultValue: true, description: 'Build UBI8 image')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PPG_REPO}-${env.IMAGE_VER}"
                }
            }
        }
        stage ('Build image') {
            when {
                not { environment name: 'TARGET_REPO', value: 'DockerHub' }
            }
            steps {
                sh """
                    sudo rm -f /usr/libexec/docker/cli-plugins/docker-buildx
                    export DOCKER_CLI_EXPERIMENTAL=enabled
                    sudo mkdir -p /usr/libexec/docker/cli-plugins/
                    sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-arm64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo systemctl restart docker
                    export DOCKER_BUILDKIT=1
                """
                script {
                    if (params.BUILD_UBI9) {
                        sh """
                            MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                            echo \$MAJ_VER
                            MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                            echo \$MIN_VER
                            git clone https://github.com/surbhat1595/percona-docker
                            cd percona-docker/percona-distribution-postgresql-\$MAJ_VER
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile.aarch64
                            sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile.aarch64
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis.aarch64
                            sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile-postgis.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis.aarch64
                            docker build --platform=linux/arm64 --provenance=false --no-cache -t percona-distribution-postgresql -f Dockerfile.aarch64 .
                            docker build --platform=linux/arm64 --provenance=false --no-cache -t percona-distribution-postgresql-with-postgis -f Dockerfile-postgis.aarch64 .
                        """
                    }
                    if (params.BUILD_UBI8) {
                        sh """
                            MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                            echo \$MAJ_VER
                            MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                            echo \$MIN_VER
                            git clone https://github.com/surbhat1595/percona-docker percona-docker-ubi8
                            cd percona-docker-ubi8/percona-distribution-postgresql-\$MAJ_VER
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-ubi8.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-ubi8.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-ubi8.aarch64
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis-ubi8.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis-ubi8.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis-ubi8.aarch64
                            docker build --platform=linux/arm64 --provenance=false --no-cache \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql:ubi8 -f Dockerfile-ubi8.aarch64 .
                            docker build --platform=linux/arm64 --provenance=false --no-cache \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql-with-postgis:ubi8 -f Dockerfile-postgis-ubi8.aarch64 .
                        """
                    }
                }
            }
        }
        stage ('Push images to perconalab') {
            when {
                environment name: 'TARGET_REPO', value: 'PerconaLab'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    script {
                        sh "docker login -u '${USER}' -p '${PASS}'"
                        if (params.BUILD_UBI9) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker tag percona-distribution-postgresql perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-arm64
                                docker tag percona-distribution-postgresql perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-arm64
                                docker tag percona-distribution-postgresql perconalab/percona-distribution-postgresql:\$MAJ_VER-arm64
                                docker push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER-arm64

                                docker tag percona-distribution-postgresql-with-postgis perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-arm64
                                docker tag percona-distribution-postgresql-with-postgis perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-arm64
                                docker tag percona-distribution-postgresql-with-postgis perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-arm64

                                docker manifest create --amend perconalab/percona-distribution-postgresql:${env.IMAGE_VER} \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-amd64 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER} \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER} \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:${env.IMAGE_VER}
                                docker manifest push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER} \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER} \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER} \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker manifest create --amend perconalab/percona-distribution-postgresql:latest \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER-amd64 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql:latest
                                   docker manifest push perconalab/percona-distribution-postgresql:latest

                                   docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:latest \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-amd64 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:latest
                                   docker manifest push perconalab/percona-distribution-postgresql-with-postgis:latest
                                fi
                            """
                        }
                        if (params.BUILD_UBI8) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker tag percona-distribution-postgresql:ubi8 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8-arm64
                                docker tag percona-distribution-postgresql:ubi8 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8-arm64
                                docker tag percona-distribution-postgresql:ubi8 perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-arm64

                                docker tag percona-distribution-postgresql-with-postgis:ubi8 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8-arm64
                                docker tag percona-distribution-postgresql-with-postgis:ubi8 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8-arm64
                                docker tag percona-distribution-postgresql-with-postgis:ubi8 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-arm64

                                docker manifest create --amend perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker manifest create --amend perconalab/percona-distribution-postgresql:latest-ubi8 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-amd64 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest-ubi8 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest-ubi8 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql:latest-ubi8
                                   docker manifest push perconalab/percona-distribution-postgresql:latest-ubi8

                                   docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:latest-ubi8 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-amd64 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest-ubi8 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest-ubi8 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:latest-ubi8
                                   docker manifest push perconalab/percona-distribution-postgresql-with-postgis:latest-ubi8
                                fi
                            """
                        }
                    }
                }
            }
        }
        stage ('Push images to percona') {
            when {
                environment name: 'TARGET_REPO', value: 'DockerHub'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    script {
                        sh """
                            sudo rm -f /usr/libexec/docker/cli-plugins/docker-buildx
                            export DOCKER_CLI_EXPERIMENTAL=enabled
                            sudo mkdir -p /usr/libexec/docker/cli-plugins/
                            sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-arm64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            docker login -u '${USER}' -p '${PASS}'
                        """
                        if (params.BUILD_UBI9) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}-arm64 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER} perconalab/percona-distribution-postgresql:${env.IMAGE_VER}
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER perconalab/percona-distribution-postgresql:\$MAJ_VER

                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-arm64 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER} perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql:latest perconalab/percona-distribution-postgresql:latest
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:latest perconalab/percona-distribution-postgresql-with-postgis:latest
                                fi
                            """
                        }
                        if (params.BUILD_UBI8) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8-arm64 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER-ubi8-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}-ubi8
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER-ubi8
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER-ubi8 perconalab/percona-distribution-postgresql:\$MAJ_VER-ubi8

                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8-arm64 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}-ubi8
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER-ubi8
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql:latest-ubi8 perconalab/percona-distribution-postgresql:latest-ubi8
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:latest-ubi8 perconalab/percona-distribution-postgresql-with-postgis:latest-ubi8
                                fi
                            """
                        }
                    }
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
    }
}
