library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    parameters {
        choice(
            choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/valkey-packaging.git',
            description: 'URL for valkey-packaging repository',
            name: 'PACKAGING_REPO')
        string(
            defaultValue: 'main',
            description: 'Branch for valkey-packaging repository',
            name: 'PACKAGING_BRANCH')
        string(
            defaultValue: '9.0.3',
            description: 'Valkey version',
            name: 'VALKEY_VERSION')
        choice(
            choices: 'experimental\ntesting\nrelease',
            description: 'Percona repo channel for packages inside the image',
            name: 'REPO_CHANNEL')
        string(
            defaultValue: 'perconalab/valkey',
            description: 'Docker image name on DockerHub',
            name: 'IMAGE_NAME')
        booleanParam(
            defaultValue: true,
            description: 'Build RPM-based (UBI9) image',
            name: 'BUILD_RPM')
        booleanParam(
            defaultValue: true,
            description: 'Build Hardened (DHI) image',
            name: 'BUILD_HARDENED')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Checkout and prepare') {
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting Docker image build for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]")
                cleanUpWS()
                sh """
                    git clone ${PACKAGING_REPO} valkey-packaging
                    cd valkey-packaging
                    git checkout ${PACKAGING_BRANCH}
                """
            }
        }
        stage('Setup multiarch') {
            steps {
                sh '''
                    sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common || true
                    sudo apt-get install -y docker-ce docker-ce-cli containerd.io || true
                    export DOCKER_CLI_EXPERIMENTAL=enabled
                    sudo mkdir -p /usr/libexec/docker/cli-plugins/
                    sudo curl -L https://github.com/docker/buildx/releases/download/v0.21.2/buildx-v0.21.2.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo systemctl restart docker
                    sudo apt-get install -y qemu-system binfmt-support qemu-user-static || true
                    sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                '''
            }
        }
        stage('Build images') {
            parallel {
                stage('Build RPM image (amd64)') {
                    when { expression { return params.BUILD_RPM } }
                    steps {
                        sh """
                            cd valkey-packaging/docker
                            sudo docker build --no-cache \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                --platform linux/amd64 \
                                -t ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                -f Dockerfile .
                        """
                    }
                }
                stage('Build RPM image (arm64)') {
                    when { expression { return params.BUILD_RPM } }
                    steps {
                        sh """
                            cd valkey-packaging/docker
                            sudo docker build --no-cache \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                --platform linux/arm64 \
                                -t ${IMAGE_NAME}:${VALKEY_VERSION}-arm64 \
                                -f Dockerfile .
                        """
                    }
                }
                stage('Build Hardened image (amd64)') {
                    when { expression { return params.BUILD_HARDENED } }
                    steps {
                        withCredentials([
                            usernamePassword(credentialsId: 'dhi.io',
                            passwordVariable: 'DHI_PASS',
                            usernameVariable: 'DHI_USER')
                        ]) {
                            sh '''
                                echo "${DHI_PASS}" | sudo docker login dhi.io -u "${DHI_USER}" --password-stdin
                            '''
                        }
                        sh """
                            cd valkey-packaging/docker
                            sudo docker build --no-cache \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                --platform linux/amd64 \
                                -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                -f Dockerfile.hardened .
                        """
                    }
                }
                stage('Build Hardened image (arm64)') {
                    when { expression { return params.BUILD_HARDENED } }
                    steps {
                        withCredentials([
                            usernamePassword(credentialsId: 'dhi.io',
                            passwordVariable: 'DHI_PASS',
                            usernameVariable: 'DHI_USER')
                        ]) {
                            sh '''
                                echo "${DHI_PASS}" | sudo docker login dhi.io -u "${DHI_USER}" --password-stdin
                            '''
                        }
                        sh """
                            cd valkey-packaging/docker
                            sudo docker build --no-cache \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                --platform linux/arm64 \
                                -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64 \
                                -f Dockerfile.hardened .
                        """
                    }
                }
            }
        }
        stage('Trivy CVE scan') {
            steps {
                sh '''
                    sudo apt-get install -y wget || true
                    wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | gpg --dearmor | sudo tee /usr/share/keyrings/trivy.gpg > /dev/null
                    echo "deb [signed-by=/usr/share/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main" | sudo tee /etc/apt/sources.list.d/trivy.list
                    sudo apt-get update
                    sudo apt-get install -y trivy
                '''
                script {
                    if (params.BUILD_RPM) {
                        sh """
                            echo "=== Trivy scan: RPM image (amd64) ==="
                            sudo trivy image --severity HIGH,CRITICAL \
                                ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 | tee trivy-rpm-amd64.txt
                            echo "=== Trivy scan: RPM image (arm64) ==="
                            sudo trivy image --severity HIGH,CRITICAL \
                                ${IMAGE_NAME}:${VALKEY_VERSION}-arm64 | tee trivy-rpm-arm64.txt
                        """
                    }
                    if (params.BUILD_HARDENED) {
                        sh """
                            echo "=== Trivy scan: Hardened image (amd64) ==="
                            sudo trivy image --severity HIGH,CRITICAL \
                                ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 | tee trivy-hardened-amd64.txt
                            echo "=== Trivy scan: Hardened image (arm64) ==="
                            sudo trivy image --severity HIGH,CRITICAL \
                                ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64 | tee trivy-hardened-arm64.txt
                        """
                    }
                }
                archiveArtifacts artifacts: 'trivy-*.txt', allowEmptyArchive: true
            }
        }
        stage('Push and create manifests') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'hub.docker.com',
                    passwordVariable: 'PASS',
                    usernameVariable: 'USER')
                ]) {
                    sh '''
                        echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                    '''
                    script {
                        if (params.BUILD_RPM) {
                            sh """
                                sudo docker push ${IMAGE_NAME}:${VALKEY_VERSION}-amd64
                                sudo docker push ${IMAGE_NAME}:${VALKEY_VERSION}-arm64

                                sudo docker manifest create ${IMAGE_NAME}:${VALKEY_VERSION} \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-arm64
                                sudo docker manifest annotate ${IMAGE_NAME}:${VALKEY_VERSION} \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-arm64 --os linux --arch arm64 --variant v8
                                sudo docker manifest annotate ${IMAGE_NAME}:${VALKEY_VERSION} \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 --os linux --arch amd64
                                sudo docker manifest inspect ${IMAGE_NAME}:${VALKEY_VERSION}
                                sudo docker manifest push ${IMAGE_NAME}:${VALKEY_VERSION}

                                VALKEY_MINOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1,2)
                                VALKEY_MAJOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1)

                                sudo docker buildx imagetools create -t ${IMAGE_NAME}:latest \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}
                                sudo docker buildx imagetools create -t ${IMAGE_NAME}:\${VALKEY_MINOR} \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}
                                sudo docker buildx imagetools create -t ${IMAGE_NAME}:\${VALKEY_MAJOR} \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}
                            """
                        }
                        if (params.BUILD_HARDENED) {
                            sh """
                                sudo docker push ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64
                                sudo docker push ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64

                                sudo docker manifest create ${IMAGE_NAME}:${VALKEY_VERSION}-hardened \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64
                                sudo docker manifest annotate ${IMAGE_NAME}:${VALKEY_VERSION}-hardened \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64 --os linux --arch arm64 --variant v8
                                sudo docker manifest annotate ${IMAGE_NAME}:${VALKEY_VERSION}-hardened \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 --os linux --arch amd64
                                sudo docker manifest inspect ${IMAGE_NAME}:${VALKEY_VERSION}-hardened
                                sudo docker manifest push ${IMAGE_NAME}:${VALKEY_VERSION}-hardened

                                VALKEY_MINOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1,2)
                                VALKEY_MAJOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1)

                                sudo docker buildx imagetools create -t ${IMAGE_NAME}:\${VALKEY_MINOR}-hardened \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened
                                sudo docker buildx imagetools create -t ${IMAGE_NAME}:\${VALKEY_MAJOR}-hardened \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened
                            """
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Docker images built and pushed for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Valkey ${VALKEY_VERSION} Docker images pushed to ${IMAGE_NAME}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Docker image build failed for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo docker logout || true
                sudo docker logout dhi.io || true
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
