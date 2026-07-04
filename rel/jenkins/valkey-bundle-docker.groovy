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
            defaultValue: '9.1.0',
            description: 'Branch for valkey-packaging repository',
            name: 'PACKAGING_BRANCH')
        string(
            defaultValue: '9.1.0',
            description: 'Valkey bundle version',
            name: 'VALKEY_VERSION')
        choice(
            choices: 'experimental\ntesting\nrelease',
            description: 'Percona repo channel for packages inside the image',
            name: 'REPO_CHANNEL')
        string(
            defaultValue: 'perconalab/valkey-bundle',
            description: 'Docker image name on DockerHub (bundle)',
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
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting Docker image build for Valkey Bundle ${VALKEY_VERSION} - [${BUILD_URL}]")
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
                    # Install docker-buildx only if it is not already usable.
                    # Downloading straight onto the live plugin path fails with
                    # "Text file busy" when a buildx process already has it open,
                    # so fetch to a temp file and install() it atomically.
                    if ! docker buildx version >/dev/null 2>&1; then
                        sudo mkdir -p /usr/libexec/docker/cli-plugins/
                        curl -fL https://github.com/docker/buildx/releases/download/v0.21.2/buildx-v0.21.2.linux-amd64 -o /tmp/docker-buildx
                        sudo install -m 0755 /tmp/docker-buildx /usr/libexec/docker/cli-plugins/docker-buildx
                        rm -f /tmp/docker-buildx
                        sudo systemctl restart docker || true
                    fi
                    sudo apt-get install -y qemu-system binfmt-support qemu-user-static || true
                    sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes

                    # Attestation-capable builder: the default "docker" driver cannot
                    # produce provenance/SBOM attestations. Create it WITHOUT sudo so it
                    # lives in the same docker context the push stage uses (no sudo there).
                    # qemu/binfmt above is host-wide, so this builder still gets arm64 emulation.
                    docker buildx inspect multiarch-builder >/dev/null 2>&1 \
                        || docker buildx create --name multiarch-builder --driver docker-container --bootstrap
                    docker buildx use multiarch-builder
                '''
            }
        }
        stage('Build, test and scan') {
            stages {
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
                                        -f Dockerfile.bundle .
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
                                        -f Dockerfile.bundle .
                                """
                            }
                        }
                        stage('Build Hardened image (amd64)') {
                            when { expression { return params.BUILD_HARDENED } }
                            steps {
                                withCredentials([
                                    usernamePassword(credentialsId: 'hub.docker.com',
                                    passwordVariable: 'PASS',
                                    usernameVariable: 'USER')
                                ]) {
                                    sh '''
                                        echo "${PASS}" | sudo docker login dhi.io -u "${USER}" --password-stdin
                                    '''
                                }
                                sh """
                                    cd valkey-packaging/docker
                                    sudo docker build --no-cache \
                                        --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                        --platform linux/amd64 \
                                        -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                        -f Dockerfile.bundle.hardened .
                                """
                            }
                        }
                        stage('Build Hardened image (arm64)') {
                            when { expression { return params.BUILD_HARDENED } }
                            steps {
                                withCredentials([
                                    usernamePassword(credentialsId: 'hub.docker.com',
                                    passwordVariable: 'PASS',
                                    usernameVariable: 'USER')
                                ]) {
                                    sh '''
                                        echo "${PASS}" | sudo docker login dhi.io -u "${USER}" --password-stdin
                                    '''
                                }
                                sh """
                                    cd valkey-packaging/docker
                                    sudo docker build --no-cache \
                                        --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                        --platform linux/arm64 \
                                        -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64 \
                                        -f Dockerfile.bundle.hardened .
                                """
                            }
                        }
                    }
                }
                stage('Test images') {
                    steps {
                        script {
                            if (params.BUILD_RPM) {
                                sh """
                                    echo "=== Testing RPM image (amd64) ==="
                                    cd valkey-packaging/docker
                                    VALKEY_VERSION=${VALKEY_VERSION} sudo -E ./test-image.sh ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 bundle-rpm
                                """
                            }
                            if (params.BUILD_HARDENED) {
                                sh """
                                    echo "=== Testing Hardened image (amd64) ==="
                                    cd valkey-packaging/docker
                                    VALKEY_VERSION=${VALKEY_VERSION} sudo -E ./test-image.sh ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 bundle-hardened
                                """
                            }
                        }
                    }
                }
                
            }
        }
        stage('Push and create manifests') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'hub.docker.com',
                    passwordVariable: 'PASS',
                    usernameVariable: 'USER')
                ]) {
                    sh """
                        docker login -u '${USER}' -p '${PASS}'
                    """
                    script {
                        if (params.BUILD_RPM) {
                            sh """
                                cd valkey-packaging/docker
                                docker buildx build --push --provenance=mode=max --sbom=true \
                                    --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                    --platform linux/amd64 \
                                    -t ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                    -f Dockerfile.bundle .
                                docker buildx build --push --provenance=mode=max --sbom=true \
                                    --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                    --platform linux/arm64 \
                                    -t ${IMAGE_NAME}:${VALKEY_VERSION}-arm64 \
                                    -f Dockerfile.bundle .

                                VALKEY_MINOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1,2)
                                VALKEY_MAJOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1)

                                docker buildx imagetools create -t ${IMAGE_NAME}:${VALKEY_VERSION} \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-arm64
                                docker buildx imagetools create -t ${IMAGE_NAME}:latest \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-arm64
                                docker buildx imagetools create -t ${IMAGE_NAME}:\${VALKEY_MINOR} \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-arm64
                                docker buildx imagetools create -t ${IMAGE_NAME}:\${VALKEY_MAJOR} \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-arm64
                            """
                        }
                        if (params.BUILD_HARDENED) {
                            // docker-container builder has its own isolated cache and
                            // no access to the local store, so it re-pulls FROM dhi.io.
                            // Log into dhi.io here (push stage only logs into Docker Hub above).
                            sh '''
                                echo "${PASS}" | docker login dhi.io -u "${USER}" --password-stdin
                            '''
                            sh """
                                cd valkey-packaging/docker
                                docker buildx build --push --provenance=mode=max --sbom=true \
                                    --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                    --platform linux/amd64 \
                                    -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                    -f Dockerfile.bundle.hardened .
                                docker buildx build --push --provenance=mode=max --sbom=true \
                                    --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                    --platform linux/arm64 \
                                    -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64 \
                                    -f Dockerfile.bundle.hardened .

                                VALKEY_MINOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1,2)
                                VALKEY_MAJOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1)

                                docker buildx imagetools create -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64
                                docker buildx imagetools create -t ${IMAGE_NAME}:\${VALKEY_MINOR}-hardened \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64
                                docker buildx imagetools create -t ${IMAGE_NAME}:\${VALKEY_MAJOR}-hardened \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                    ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-arm64
                            """
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Docker images built and pushed for Valkey Bundle ${VALKEY_VERSION} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Valkey Bundle ${VALKEY_VERSION} Docker images pushed to ${IMAGE_NAME}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Docker image build failed for Valkey Bundle ${VALKEY_VERSION} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                docker logout || true
                docker logout dhi.io || true
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
