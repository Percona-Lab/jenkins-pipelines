library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _


void cleanUpWS() {
    sh '''
        sudo rm -rf ./*
    '''
}


pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }

    parameters {
        choice(
            choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD'
        )

        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/valkey-packaging.git',
            description: 'URL for valkey-packaging repository',
            name: 'PACKAGING_REPO'
        )

        string(
            defaultValue: 'main',
            description: 'Branch for valkey-packaging repository',
            name: 'PACKAGING_BRANCH'
        )

        string(
            defaultValue: '9.1.2',
            description: 'Valkey version',
            name: 'VALKEY_VERSION'
        )

        choice(
            choices: 'experimental\ntesting\nrelease',
            description: 'Percona repo channel for packages inside the image',
            name: 'REPO_CHANNEL'
        )

        string(
            defaultValue: 'perconalab/valkey',
            description: 'Docker image name on DockerHub',
            name: 'IMAGE_NAME'
        )

        booleanParam(
            defaultValue: true,
            description: 'Build RPM-based (UBI9) image',
            name: 'BUILD_RPM'
        )

        booleanParam(
            defaultValue: true,
            description: 'Build Hardened (DHI) image',
            name: 'BUILD_HARDENED'
        )

        booleanParam(
            defaultValue: false,
            description: 'Push images despite a failed Trivy check (report-only). Use with caution.',
            name: 'IGNORE_TRIVY'
        )

        string(
            defaultValue: 'HIGH,CRITICAL',
            description: 'Trivy severities that fail the build',
            name: 'TRIVY_SEVERITY'
        )
    }

    environment {
        BUILDX_BUILDER = 'multiarch-builder'
    }

    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(
            logRotator(
                numToKeepStr: '10',
                artifactNumToKeepStr: '10'
            )
        )
        timestamps()
    }

    stages {

        stage('Checkout and prepare') {
            steps {
                slackNotify(
                    "#releases-ci",
                    "#00FF00",
                    "[${JOB_NAME}]: starting Docker image build for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]"
                )

                cleanUpWS()

                sh """
                    set -eux

                    git clone ${PACKAGING_REPO} valkey-packaging

                    cd valkey-packaging

                    git checkout ${PACKAGING_BRANCH}

                    echo "=== Commit ==="
                    git rev-parse HEAD
                """
            }
        }


        stage('Setup multiarch') {
            steps {
                sh '''
                    set -eux

                    echo "=== Host ==="
                    uname -a
                    uname -m

                    echo "=== Docker ==="
                    docker version

                    if ! docker buildx version >/dev/null 2>&1; then
                        sudo mkdir -p /usr/libexec/docker/cli-plugins/

                        curl -fL \
                            https://github.com/docker/buildx/releases/download/v0.21.2/buildx-v0.21.2.linux-amd64 \
                            -o /tmp/docker-buildx

                        sudo install -m 0755 \
                            /tmp/docker-buildx \
                            /usr/libexec/docker/cli-plugins/docker-buildx

                        rm -f /tmp/docker-buildx
                    fi

                    echo "=== Buildx ==="
                    docker buildx version

                    sudo docker run --privileged --rm \
                        tonistiigi/binfmt \
                        --install all

                    echo "=== binfmt handlers ==="
                    ls -la /proc/sys/fs/binfmt_misc/

                    if [ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
                        cat /proc/sys/fs/binfmt_misc/qemu-aarch64
                    fi

                    docker buildx rm "${BUILDX_BUILDER}" >/dev/null 2>&1 || true

                    docker buildx create \
                        --name "${BUILDX_BUILDER}" \
                        --driver docker-container \
                        --use

                    docker buildx inspect \
                        "${BUILDX_BUILDER}" \
                        --bootstrap

                    echo "=== Builders ==="
                    docker buildx ls
                '''
            }
        }


        stage('Validate multiarch') {
            steps {
                sh '''
                    set -eux

                    echo "=== Validate native amd64 execution ==="

                    docker run --rm \
                        --platform linux/amd64 \
                        redhat/ubi9-minimal:latest \
                        uname -m

                    echo "=== Validate emulated arm64 execution ==="

                    docker run --rm \
                        --platform linux/arm64 \
                        redhat/ubi9-minimal:latest \
                        uname -m

                    echo "=== Validate exact pinned RPM base image ==="

                    cd valkey-packaging/docker

                    BASE_IMAGE=$(awk -F= \
                        '/^ARG BASE_IMAGE=/{print $2; exit}' \
                        Dockerfile)

                    BASE_TAG=$(awk -F= \
                        '/^ARG BASE_TAG=/{print $2; exit}' \
                        Dockerfile)

                    BASE_DIGEST=$(awk -F= \
                        '/^ARG BASE_DIGEST=/{print $2; exit}' \
                        Dockerfile)

                    echo "BASE_IMAGE=${BASE_IMAGE}"
                    echo "BASE_TAG=${BASE_TAG}"
                    echo "BASE_DIGEST=${BASE_DIGEST}"

                    if [ -z "${BASE_IMAGE}" ] || \
                       [ -z "${BASE_TAG}" ] || \
                       [ -z "${BASE_DIGEST}" ]; then
                        echo "Unable to determine pinned base image from Dockerfile"
                        exit 1
                    fi

                    echo "=== Inspect pinned image/index ==="

                    docker buildx imagetools inspect \
                        "${BASE_IMAGE}:${BASE_TAG}@${BASE_DIGEST}"

                    echo "=== Test pinned image as amd64 ==="

                    docker run --rm \
                        --platform linux/amd64 \
                        "${BASE_IMAGE}:${BASE_TAG}@${BASE_DIGEST}" \
                        uname -m

                    echo "=== Test pinned image as arm64 ==="

                    docker run --rm \
                        --platform linux/arm64 \
                        "${BASE_IMAGE}:${BASE_TAG}@${BASE_DIGEST}" \
                        uname -m
                '''
            }
        }


        stage('Build local test images') {
            parallel {

                stage('Build RPM amd64') {
                    when {
                        expression {
                            return params.BUILD_RPM
                        }
                    }

                    steps {
                        sh """
                            set -eux

                            cd valkey-packaging/docker

                            docker buildx build \
                                --builder ${BUILDX_BUILDER} \
                                --platform linux/amd64 \
                                --load \
                                --no-cache \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                -t ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                -f Dockerfile \
                                .
                        """
                    }
                }


                stage('Build Hardened amd64') {
                    when {
                        expression {
                            return params.BUILD_HARDENED
                        }
                    }

                    steps {
                        withCredentials([
                            usernamePassword(
                                credentialsId: 'hub.docker.com',
                                passwordVariable: 'PASS',
                                usernameVariable: 'USER'
                            )
                        ]) {
                            sh '''
                                set -eu

                                echo "${PASS}" | docker login \
                                    dhi.io \
                                    -u "${USER}" \
                                    --password-stdin
                            '''
                        }

                        sh """
                            set -eux

                            cd valkey-packaging/docker

                            docker buildx build \
                                --builder ${BUILDX_BUILDER} \
                                --platform linux/amd64 \
                                --load \
                                --no-cache \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                -f Dockerfile.hardened \
                                .
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
                            set -eux

                            echo "=== Testing RPM image (amd64) ==="

                            cd valkey-packaging/docker

                            VALKEY_VERSION=${VALKEY_VERSION} \
                                ./test-image.sh \
                                ${IMAGE_NAME}:${VALKEY_VERSION}-amd64 \
                                rpm
                        """
                    }

                    if (params.BUILD_HARDENED) {
                        sh """
                            set -eux

                            echo "=== Testing Hardened image (amd64) ==="

                            cd valkey-packaging/docker

                            VALKEY_VERSION=${VALKEY_VERSION} \
                                ./test-image.sh \
                                ${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64 \
                                hardened
                        """
                    }
                }
            }
        }


        stage('Scan images (Trivy)') {
            steps {
                script {

                    retry(3) {
                        try {

                            installTrivy(method: 'binary')

                            def exitCode = params.IGNORE_TRIVY ? '0' : '1'

                            def imgs = []

                            if (params.BUILD_RPM) {
                                imgs << "${IMAGE_NAME}:${VALKEY_VERSION}-amd64"
                            }

                            if (params.BUILD_HARDENED) {
                                imgs << "${IMAGE_NAME}:${VALKEY_VERSION}-hardened-amd64"
                            }

                            for (img in imgs) {
                                sh """
                                    /usr/local/bin/trivy \
                                        -q image \
                                        --timeout 10m0s \
                                        --ignore-unfixed \
                                        --exit-code ${exitCode} \
                                        --severity ${params.TRIVY_SEVERITY} \
                                        ${img}
                                """
                            }

                        } catch (Exception e) {

                            echo "Attempt failed: ${e.message}"

                            sleep 15

                            throw e
                        }
                    }
                }
            }
        }


        stage('Login to registries') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS',
                        usernameVariable: 'USER'
                    )
                ]) {
                    sh '''
                        set -eu

                        echo "${PASS}" | docker login \
                            -u "${USER}" \
                            --password-stdin

                        if [ "${BUILD_HARDENED}" = "true" ]; then
                            echo "${PASS}" | docker login \
                                dhi.io \
                                -u "${USER}" \
                                --password-stdin
                        fi
                    '''
                }
            }
        }


        stage('Build and push multiarch images') {
            parallel {

                stage('Push RPM multiarch') {
                    when {
                        expression {
                            return params.BUILD_RPM
                        }
                    }

                    steps {
                        sh """
                            set -eux

                            cd valkey-packaging/docker

                            VALKEY_MINOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1,2)
                            VALKEY_MAJOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1)

                            docker buildx build \
                                --builder ${BUILDX_BUILDER} \
                                --platform linux/amd64,linux/arm64 \
                                --push \
                                --provenance=mode=max \
                                --sbom=true \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                -t ${IMAGE_NAME}:${VALKEY_VERSION} \
                                -t ${IMAGE_NAME}:\${VALKEY_MINOR} \
                                -t ${IMAGE_NAME}:\${VALKEY_MAJOR} \
                                -t ${IMAGE_NAME}:latest \
                                -f Dockerfile \
                                .
                        """
                    }
                }


                stage('Push Hardened multiarch') {
                    when {
                        expression {
                            return params.BUILD_HARDENED
                        }
                    }

                    steps {
                        sh """
                            set -eux

                            cd valkey-packaging/docker

                            VALKEY_MINOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1,2)
                            VALKEY_MAJOR=\$(echo ${VALKEY_VERSION} | cut -d. -f1)

                            docker buildx build \
                                --builder ${BUILDX_BUILDER} \
                                --platform linux/amd64,linux/arm64 \
                                --push \
                                --provenance=mode=max \
                                --sbom=true \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                -t ${IMAGE_NAME}:${VALKEY_VERSION}-hardened \
                                -t ${IMAGE_NAME}:\${VALKEY_MINOR}-hardened \
                                -t ${IMAGE_NAME}:\${VALKEY_MAJOR}-hardened \
                                -f Dockerfile.hardened \
                                .
                        """
                    }
                }
            }
        }


        stage('Verify published manifests') {
            steps {
                script {

                    if (params.BUILD_RPM) {
                        sh """
                            set -eux

                            echo "=== RPM multiarch manifest ==="

                            docker buildx imagetools inspect \
                                ${IMAGE_NAME}:${VALKEY_VERSION}
                        """
                    }

                    if (params.BUILD_HARDENED) {
                        sh """
                            set -eux

                            echo "=== Hardened multiarch manifest ==="

                            docker buildx imagetools inspect \
                                ${IMAGE_NAME}:${VALKEY_VERSION}-hardened
                        """
                    }
                }
            }
        }
    }


    post {

        success {
            slackNotify(
                "#releases-ci",
                "#00FF00",
                "[${JOB_NAME}]: Docker images built and pushed for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]"
            )

            script {
                currentBuild.description =
                    "Valkey ${VALKEY_VERSION} Docker images pushed to ${IMAGE_NAME}"
            }
        }


        failure {
            slackNotify(
                "#releases-ci",
                "#FF0000",
                "[${JOB_NAME}]: Docker image build failed for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]"
            )
        }


        always {
            sh '''
                docker logout || true
                docker logout dhi.io || true

                docker buildx rm "${BUILDX_BUILDER}" || true

                sudo rm -rf ./*
            '''

            deleteDir()
        }
    }
}
