library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

// Read a MOD_* variable (version/source) for a module out of the packaging Makefile,
// so this job tracks whatever valkey-packaging ships without duplicating versions.
String modVar(String prefix, String module) {
    return sh(
        script: "awk -F':= *' '/^${prefix}_${module}[[:space:]]/{print \$2}' valkey-packaging/docker/Makefile | tr -d '[:space:]'",
        returnStdout: true
    ).trim()
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
            defaultValue: '9.1.0-oci',
            description: 'Branch for valkey-packaging repository',
            name: 'PACKAGING_BRANCH')
        string(
            defaultValue: 'json bloom search ldap audit',
            description: 'Space-separated modules to build (must exist in docker/Makefile)',
            name: 'MODULES')
        choice(
            choices: 'experimental\ntesting\nrelease',
            description: 'Percona repo channel the module .so is pulled from',
            name: 'REPO_CHANNEL')
        string(
            defaultValue: 'perconalab/valkey-mod',
            description: 'DockerHub image prefix; each module is pushed as <prefix>-<module>',
            name: 'IMAGE_PREFIX')
        string(
            defaultValue: 'perconalab/valkey:9.1.0',
            description: 'Stock server image used to verify each module loads',
            name: 'SERVER_IMAGE')
        booleanParam(
            defaultValue: true,
            description: 'Inject each module into SERVER_IMAGE and verify it loads (amd64)',
            name: 'RUN_TESTS')
        booleanParam(
            defaultValue: true,
            description: 'Also tag/push <prefix>-<module>:latest',
            name: 'PUSH_LATEST')
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
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting module image build (${MODULES}) - [${BUILD_URL}]")
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

                    # Attestation-capable builder in the same docker context the push stage uses.
                    docker buildx inspect multiarch-builder >/dev/null 2>&1 \
                        || docker buildx create --name multiarch-builder --driver docker-container --bootstrap
                    docker buildx use multiarch-builder
                '''
            }
        }
        stage('Build and test (amd64)') {
            steps {
                script {
                    if (params.RUN_TESTS) {
                        sh "sudo docker pull ${params.SERVER_IMAGE}"
                    }
                    for (m in params.MODULES.trim().split(/\s+/)) {
                        def ver = modVar('MOD_VERSION', m)
                        def src = modVar('MOD_SOURCE', m)
                        if (!ver) {
                            error "No MOD_VERSION_${m} in docker/Makefile — unknown module '${m}'"
                        }
                        def img = "${params.IMAGE_PREFIX}-${m}"
                        sh """
                            cd valkey-packaging/docker
                            sudo docker build --no-cache \
                                --build-arg MODULE=${m} \
                                --build-arg MODULE_VERSION=${ver} \
                                --build-arg MODULE_SOURCE=${src} \
                                --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                --platform linux/amd64 \
                                -t ${img}:${ver}-amd64 \
                                -f Dockerfile.module .
                        """
                        if (params.RUN_TESTS) {
                            sh """
                                cd valkey-packaging/docker
                                sudo ./test-module-inject.sh ${img}:${ver}-amd64 ${m} ${params.SERVER_IMAGE}
                            """
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
                        for (m in params.MODULES.trim().split(/\s+/)) {
                            def ver = modVar('MOD_VERSION', m)
                            def src = modVar('MOD_SOURCE', m)
                            def img = "${params.IMAGE_PREFIX}-${m}"
                            sh """
                                cd valkey-packaging/docker
                                docker buildx build --push --provenance=mode=max --sbom=true \
                                    --build-arg MODULE=${m} \
                                    --build-arg MODULE_VERSION=${ver} \
                                    --build-arg MODULE_SOURCE=${src} \
                                    --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                    --platform linux/amd64 \
                                    -t ${img}:${ver}-amd64 \
                                    -f Dockerfile.module .
                                docker buildx build --push --provenance=mode=max --sbom=true \
                                    --build-arg MODULE=${m} \
                                    --build-arg MODULE_VERSION=${ver} \
                                    --build-arg MODULE_SOURCE=${src} \
                                    --build-arg REPO_CHANNEL=${REPO_CHANNEL} \
                                    --platform linux/arm64 \
                                    -t ${img}:${ver}-arm64 \
                                    -f Dockerfile.module .

                                docker buildx imagetools create -t ${img}:${ver} \
                                    ${img}:${ver}-amd64 \
                                    ${img}:${ver}-arm64
                            """
                            if (params.PUSH_LATEST) {
                                sh """
                                    docker buildx imagetools create -t ${img}:latest \
                                        ${img}:${ver}-amd64 \
                                        ${img}:${ver}-arm64
                                """
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: module images built and pushed (${MODULES}) - [${BUILD_URL}]")
            script {
                currentBuild.description = "Valkey module images pushed to ${IMAGE_PREFIX}-<module>"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: module image build failed (${MODULES}) - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                docker logout || true
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
