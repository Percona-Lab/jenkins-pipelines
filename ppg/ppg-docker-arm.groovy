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
        booleanParam(name: 'BUILD_UBI10', defaultValue: true, description: 'Build UBI10 image')
        booleanParam(name: 'BUILD_UBI9', defaultValue: true, description: 'Build UBI9 image')
        booleanParam(name: 'BUILD_UBI8', defaultValue: true, description: 'Build UBI8 image')
        booleanParam(name: 'BUILD_PSP', defaultValue: false, description: 'Build PSP image (PG 16 only): replaces ppg repo with psp repo and adds -psp tag suffix')
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
                    def pspSuffix = (params.BUILD_PSP && params.PPG_VERSION.split('-')[0].split('\\.')[0] == '16') ? '-psp' : ''
                    if (params.BUILD_UBI10) {
                        sh """
                            MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                            echo \$MAJ_VER
                            MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                            echo \$MIN_VER
                            git clone https://github.com/percona/percona-docker percona-docker-ubi10
                            cd percona-docker-ubi10/percona-distribution-postgresql-\$MAJ_VER
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-ubi10.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-ubi10.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-ubi10.aarch64
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis-ubi10.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis-ubi10.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis-ubi10.aarch64
                            if [ "${params.BUILD_PSP}" = "true" ] && [ "\$MAJ_VER" = "16" ]; then
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-ubi10.aarch64
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-postgis-ubi10.aarch64
                            fi
                            docker build --platform=linux/arm64 --provenance=false --no-cache \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql:ubi10${pspSuffix} -f Dockerfile-ubi10.aarch64 .
                            docker build --platform=linux/arm64 --provenance=false --no-cache \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql-with-postgis:ubi10${pspSuffix} -f Dockerfile-postgis-ubi10.aarch64 .
                        """
                    }
                    if (params.BUILD_UBI9) {
                        sh """
                            MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                            echo \$MAJ_VER
                            MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                            echo \$MIN_VER
                            git clone https://github.com/percona/percona-docker
                            cd percona-docker/percona-distribution-postgresql-\$MAJ_VER
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile.aarch64
                            sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile.aarch64
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis.aarch64
                            sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile-postgis.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis.aarch64
                            if [ "${params.BUILD_PSP}" = "true" ] && [ "\$MAJ_VER" = "16" ]; then
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile.aarch64
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-postgis.aarch64
                            fi
                            docker build --platform=linux/arm64 --provenance=false --no-cache -t percona-distribution-postgresql${pspSuffix} -f Dockerfile.aarch64 .
                            docker build --platform=linux/arm64 --provenance=false --no-cache -t percona-distribution-postgresql-with-postgis${pspSuffix} -f Dockerfile-postgis.aarch64 .
                        """
                    }
                    if (params.BUILD_UBI8) {
                        sh """
                            MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                            echo \$MAJ_VER
                            MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                            echo \$MIN_VER
                            git clone https://github.com/percona/percona-docker percona-docker-ubi8
                            cd percona-docker-ubi8/percona-distribution-postgresql-\$MAJ_VER
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-ubi8.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-ubi8.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-ubi8.aarch64
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis-ubi8.aarch64
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis-ubi8.aarch64
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis-ubi8.aarch64
                            if [ "${params.BUILD_PSP}" = "true" ] && [ "\$MAJ_VER" = "16" ]; then
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-ubi8.aarch64
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-postgis-ubi8.aarch64
                            fi
                            docker build --platform=linux/arm64 --provenance=false --no-cache \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql:ubi8${pspSuffix} -f Dockerfile-ubi8.aarch64 .
                            docker build --platform=linux/arm64 --provenance=false --no-cache \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql-with-postgis:ubi8${pspSuffix} -f Dockerfile-postgis-ubi8.aarch64 .
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
                        def pspSuffix = (params.BUILD_PSP && params.PPG_VERSION.split('-')[0].split('\\.')[0] == '16') ? '-psp' : ''
                        sh "docker login -u '${USER}' -p '${PASS}'"
                        if (params.BUILD_UBI10) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker tag percona-distribution-postgresql:ubi10${pspSuffix} perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64
                                docker tag percona-distribution-postgresql:ubi10${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64
                                docker tag percona-distribution-postgresql:ubi10${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-arm64
                                docker push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-arm64

                                docker tag percona-distribution-postgresql-with-postgis:ubi10${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64
                                docker tag percona-distribution-postgresql-with-postgis:ubi10${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64
                                docker tag percona-distribution-postgresql-with-postgis:ubi10${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-arm64

                                docker manifest create --amend perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10
                                docker manifest push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker manifest create --amend perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi10 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-amd64 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi10 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi10 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi10
                                   docker manifest push perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi10

                                   docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-amd64 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10
                                   docker manifest push perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10
                                fi
                            """
                        }
                        if (params.BUILD_UBI9) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker tag percona-distribution-postgresql${pspSuffix} perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-arm64
                                docker tag percona-distribution-postgresql${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64
                                docker tag percona-distribution-postgresql${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-arm64
                                docker push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-arm64

                                docker tag percona-distribution-postgresql-with-postgis${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-arm64
                                docker tag percona-distribution-postgresql-with-postgis${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64
                                docker tag percona-distribution-postgresql-with-postgis${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-arm64

                                docker manifest create --amend perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-amd64 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}
                                docker manifest push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker manifest create --amend perconalab/percona-distribution-postgresql:latest${pspSuffix} \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-amd64 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest${pspSuffix} \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest${pspSuffix} \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql:latest${pspSuffix}
                                   docker manifest push perconalab/percona-distribution-postgresql:latest${pspSuffix}

                                   docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix} \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-amd64 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix} \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix} \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}
                                   docker manifest push perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}
                                fi
                            """
                        }
                        if (params.BUILD_UBI8) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker tag percona-distribution-postgresql:ubi8${pspSuffix} perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64
                                docker tag percona-distribution-postgresql:ubi8${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64
                                docker tag percona-distribution-postgresql:ubi8${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-arm64

                                docker tag percona-distribution-postgresql-with-postgis:ubi8${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64
                                docker tag percona-distribution-postgresql-with-postgis:ubi8${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64
                                docker tag percona-distribution-postgresql-with-postgis:ubi8${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-arm64

                                docker manifest create --amend perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-amd64 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8 \
                                   perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker manifest create --amend perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi8 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-amd64 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi8 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi8 \
                                      perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi8
                                   docker manifest push perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi8

                                   docker manifest create --amend perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-amd64 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-arm64
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                   docker manifest annotate perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8 \
                                      perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-amd64 --os linux --arch amd64
                                   docker manifest inspect perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8
                                   docker manifest push perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8
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
                        def pspSuffix = (params.BUILD_PSP && params.PPG_VERSION.split('-')[0].split('\\.')[0] == '16') ? '-psp' : ''
                        sh """
                            sudo rm -f /usr/libexec/docker/cli-plugins/docker-buildx
                            export DOCKER_CLI_EXPERIMENTAL=enabled
                            sudo mkdir -p /usr/libexec/docker/cli-plugins/
                            sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-arm64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            docker login -u '${USER}' -p '${PASS}'
                        """
                        if (params.BUILD_UBI10) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10 perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10

                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql:latest${pspSuffix}-ubi10 perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi10
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10 perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10
                                fi
                            """
                        }
                        if (params.BUILD_UBI9) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-arm64 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix} perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}

                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-arm64 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql:latest${pspSuffix} perconalab/percona-distribution-postgresql:latest${pspSuffix}
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:latest${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}
                                fi
                            """
                        }
                        if (params.BUILD_UBI8) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-arm64 perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8 perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8

                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-arm64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-arm64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8

                                if [ ${params.LATEST} = "yes" ]; then
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql:latest${pspSuffix}-ubi8 perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi8
                                   docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8 perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8
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
