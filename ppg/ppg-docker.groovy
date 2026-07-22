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
        IMAGE_VER = "${params.PPG_VERSION.tokenize('-')[0]}-${params.BUILD_NUMBER}"
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        choice(name: 'PPG_REPO', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PPG_VERSION', defaultValue: '16.3-1', description: 'PPG version')
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
                    export DOCKER_CLI_EXPERIMENTAL=enabled
                    sudo mkdir -p /usr/libexec/docker/cli-plugins/
                    sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo systemctl restart docker
                    sudo apt-get install -y qemu-system binfmt-support qemu-user-static
                    sudo qemu-system-x86_64 --version
                    sudo lscpu | grep -q 'sse4_2' && grep -q 'popcnt' /proc/cpuinfo && echo "Supports x86-64-v2" || echo "Does NOT support x86-64-v2"
                    sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
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
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-ubi10
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-ubi10
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-ubi10
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis-ubi10
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis-ubi10
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis-ubi10
                            if [ "${params.BUILD_PSP}" = "true" ] && [ "\$MAJ_VER" = "16" ]; then
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-ubi10
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-postgis-ubi10
                            fi
                            docker build --platform=linux/amd64 --no-cache --provenance=false \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql:\$MAJ_VER-ubi10${pspSuffix} -f Dockerfile-ubi10 .
                            docker build --platform=linux/amd64 --no-cache --provenance=false \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10${pspSuffix} -f Dockerfile-postgis-ubi10 .
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
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile
                            sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis
                            sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile-postgis
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis
                            if [ "${params.BUILD_PSP}" = "true" ] && [ "\$MAJ_VER" = "16" ]; then
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-postgis
                            fi
                            docker build --platform=linux/amd64 --no-cache --provenance=false -t percona-distribution-postgresql:\$MAJ_VER${pspSuffix} .
                            docker build --platform=linux/amd64 --no-cache --provenance=false -t percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} -f Dockerfile-postgis .
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
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-ubi8
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-ubi8
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-ubi8
                            sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis-ubi8
                            sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis-ubi8
                            sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis-ubi8
                            if [ "${params.BUILD_PSP}" = "true" ] && [ "\$MAJ_VER" = "16" ]; then
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-ubi8
                                sed -i 's|percona-release enable ppg-\${PPG_REPO_VERSION} \${PPG_REPO};|percona-release enable psp-\${PPG_MAJOR_VERSION} \${PPG_REPO};|g' Dockerfile-postgis-ubi8
                            fi
                            docker build --platform=linux/amd64 --no-cache --provenance=false \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql:\$MAJ_VER-ubi8${pspSuffix} -f Dockerfile-ubi8 .
                            docker build --platform=linux/amd64 --no-cache --provenance=false \
                                --build-arg PPG_REPO=${params.PPG_REPO} \
                                -t percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8${pspSuffix} -f Dockerfile-postgis-ubi8 .
                        """
                    }
                }
            }
        }
        stage ('Run trivy analyzer') {
            when {
                not { environment name: 'TARGET_REPO', value: 'DockerHub' }
            }
            steps {
                installTrivy(method: 'binary', junitTpl: true)
                script {
                    def pspSuffix = (params.BUILD_PSP && params.PPG_VERSION.split('-')[0].split('\\.')[0] == '16') ? '-psp' : ''
                    if (params.BUILD_UBI10) {
                        sh """
                            MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                            if [ ${params.PPG_REPO} = "release" ]; then
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-distribution-postgresql:\$MAJ_VER-ubi10${pspSuffix}
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10${pspSuffix}
                            else
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-distribution-postgresql:\$MAJ_VER-ubi10${pspSuffix}
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10${pspSuffix}
                            fi
                        """
                    }
                    if (params.BUILD_UBI9) {
                        sh """
                            MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                            MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                            if [ ${params.PPG_REPO} = "release" ]; then
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-distribution-postgresql:\$MAJ_VER${pspSuffix}
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}
                            else
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-distribution-postgresql:\$MAJ_VER${pspSuffix}
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}
                            fi
                        """
                    }
                    if (params.BUILD_UBI8) {
                        sh """
                            MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                            if [ ${params.PPG_REPO} = "release" ]; then
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-distribution-postgresql:\$MAJ_VER-ubi8${pspSuffix}
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8${pspSuffix}
                            else
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-distribution-postgresql:\$MAJ_VER-ubi8${pspSuffix}
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                                 --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8${pspSuffix}
                            fi
                        """
                    }
                }
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
                    script {
                        sh """
                            curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                            if [ -f "/usr/bin/yum" ] ; then sudo yum install -y unzip ; else sudo apt-get update && apt-get -y install unzip ; fi
                            unzip -o awscliv2.zip
                            sudo ./aws/install
                            aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/e7j3v3n0
                        """
                        if (params.BUILD_UBI10) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                docker tag percona-distribution-postgresql:\$MAJ_VER-ubi10 public.ecr.aws/e7j3v3n0/ppg-build:ppg-${env.IMAGE_VER}-ubi10
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10 public.ecr.aws/e7j3v3n0/ppg-build-postgis:ppg-${env.IMAGE_VER}-ubi10
                                docker push public.ecr.aws/e7j3v3n0/ppg-build:ppg-${env.IMAGE_VER}-ubi10
                                docker push public.ecr.aws/e7j3v3n0/ppg-build-postgis:ppg-${env.IMAGE_VER}-ubi10
                                if [ ${params.LATEST} = "yes" ]; then
                                   docker tag percona-distribution-postgresql:\$MAJ_VER-ubi10 public.ecr.aws/e7j3v3n0/ppg-build:latest-ubi10
                                   docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10 public.ecr.aws/e7j3v3n0/ppg-build-postgis:latest-ubi10
                                   docker push public.ecr.aws/e7j3v3n0/ppg-build:latest-ubi10
                                   docker push public.ecr.aws/e7j3v3n0/ppg-build-postgis:latest-ubi10
                                fi
                            """
                        }
                        if (params.BUILD_UBI9) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                docker tag percona-distribution-postgresql:\$MAJ_VER public.ecr.aws/e7j3v3n0/ppg-build:ppg-${env.IMAGE_VER}
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER public.ecr.aws/e7j3v3n0/ppg-build-postgis:ppg-${env.IMAGE_VER}
                                docker push public.ecr.aws/e7j3v3n0/ppg-build:ppg-${env.IMAGE_VER}
                                docker push public.ecr.aws/e7j3v3n0/ppg-build-postgis:ppg-${env.IMAGE_VER}
                                if [ ${params.LATEST} = "yes" ]; then
                                   docker tag percona-distribution-postgresql:\$MAJ_VER public.ecr.aws/e7j3v3n0/ppg-build:latest
                                   docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER public.ecr.aws/e7j3v3n0/ppg-build-postgis:latest
                                   docker push public.ecr.aws/e7j3v3n0/ppg-build:latest
                                   docker push public.ecr.aws/e7j3v3n0/ppg-build-postgis:latest
                                fi
                            """
                        }
                        if (params.BUILD_UBI8) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                docker tag percona-distribution-postgresql:\$MAJ_VER-ubi8 public.ecr.aws/e7j3v3n0/ppg-build:ppg-${env.IMAGE_VER}-ubi8
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8 public.ecr.aws/e7j3v3n0/ppg-build-postgis:ppg-${env.IMAGE_VER}-ubi8
                                docker push public.ecr.aws/e7j3v3n0/ppg-build:ppg-${env.IMAGE_VER}-ubi8
                                docker push public.ecr.aws/e7j3v3n0/ppg-build-postgis:ppg-${env.IMAGE_VER}-ubi8
                                if [ ${params.LATEST} = "yes" ]; then
                                   docker tag percona-distribution-postgresql:\$MAJ_VER-ubi8 public.ecr.aws/e7j3v3n0/ppg-build:latest-ubi8
                                   docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8 public.ecr.aws/e7j3v3n0/ppg-build-postgis:latest-ubi8
                                   docker push public.ecr.aws/e7j3v3n0/ppg-build:latest-ubi8
                                   docker push public.ecr.aws/e7j3v3n0/ppg-build-postgis:latest-ubi8
                                fi
                            """
                        }
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
                                docker tag percona-distribution-postgresql:\$MAJ_VER-ubi10${pspSuffix} perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64
                                docker push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64
                                docker tag percona-distribution-postgresql:\$MAJ_VER-ubi10${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64
                                docker tag percona-distribution-postgresql:\$MAJ_VER-ubi10${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-amd64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-amd64
                                if [ ${params.LATEST} = "yes" ]; then
                                   docker tag percona-distribution-postgresql:\$MAJ_VER-ubi10${pspSuffix} perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi10
                                   docker push perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi10
                                   docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi10${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10
                                   docker push perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi10
                                fi
                            """
                        }
                        if (params.BUILD_UBI9) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker tag percona-distribution-postgresql:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-amd64
                                docker push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-amd64
                                docker tag percona-distribution-postgresql:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64
                                docker tag percona-distribution-postgresql:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-amd64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-amd64
                                if [ ${params.LATEST} = "yes" ]; then
                                   docker tag percona-distribution-postgresql:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql:latest${pspSuffix}
                                   docker push perconalab/percona-distribution-postgresql:latest${pspSuffix}
                                   docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}
                                   docker push perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}
                                fi
                            """
                        }
                        if (params.BUILD_UBI8) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker tag percona-distribution-postgresql:\$MAJ_VER-ubi8${pspSuffix} perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64
                                docker push perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64
                                docker tag percona-distribution-postgresql:\$MAJ_VER-ubi8${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64
                                docker tag percona-distribution-postgresql:\$MAJ_VER-ubi8${pspSuffix} perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-amd64
                                docker push perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64
                                docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-amd64
                                docker push perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-amd64
                                if [ ${params.LATEST} = "yes" ]; then
                                   docker tag percona-distribution-postgresql:\$MAJ_VER-ubi8${pspSuffix} perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi8
                                   docker push perconalab/percona-distribution-postgresql:latest${pspSuffix}-ubi8
                                   docker tag percona-distribution-postgresql-with-postgis:\$MAJ_VER-ubi8${pspSuffix} perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8
                                   docker push perconalab/percona-distribution-postgresql-with-postgis:latest${pspSuffix}-ubi8
                                fi
                            """
                        }
                    }
                }
            }
        }
        stage ('Push images to official percona docker registry') {
            when {
                environment name: 'TARGET_REPO', value: 'DockerHub'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    script {
                        def pspSuffix = (params.BUILD_PSP && params.PPG_VERSION.split('-')[0].split('\\.')[0] == '16') ? '-psp' : ''
                        sh """
                            export DOCKER_CLI_EXPERIMENTAL=enabled
                            sudo mkdir -p /usr/libexec/docker/cli-plugins/
                            sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            docker login -u '${USER}' -p '${PASS}'
                        """
                        if (params.BUILD_UBI10) {
                            sh """
                                MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                                MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-amd64 perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi10-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi10-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi10-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-amd64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi10-amd64
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
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-amd64 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-amd64 perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-amd64 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-amd64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-amd64
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
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64 perconalab/percona-distribution-postgresql:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64 perconalab/percona-distribution-postgresql:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-amd64 perconalab/percona-distribution-postgresql:\$MAJ_VER${pspSuffix}-ubi8-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64 perconalab/percona-distribution-postgresql-with-postgis:${env.IMAGE_VER}${pspSuffix}-ubi8-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER.\$MIN_VER${pspSuffix}-ubi8-amd64
                                docker buildx imagetools create -t percona/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-amd64 perconalab/percona-distribution-postgresql-with-postgis:\$MAJ_VER${pspSuffix}-ubi8-amd64
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
