library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

Boolean shouldRun(String stageName) {
    if (params.PLATFORMS == 'ALL') {
        return true
    }
    return params.PLATFORMS.split(',').collect{ it.trim() }.any{ stageName.contains(it) }
}

void testStage(String DOCKER_OS) {
    def CONTAINER_NAME = "valkey-test-${DOCKER_OS.replaceAll('[:/]', '-')}-${BUILD_NUMBER}"
    def TEST_IMAGE = "valkey-test-img-${DOCKER_OS.replaceAll('[:/]', '-')}-${BUILD_NUMBER}"

    try {
        sh """
            set -o xtrace

            # Clone valkey-packaging and checkout the requested branch
            git clone https://github.com/EvgeniyPatlan/valkey-packaging.git valkey-packaging
            cd valkey-packaging
            git checkout ${PACKAGING_BRANCH}
            cd ..

            # Patch the hardcoded repo name in test_packages.sh
            sed -i 's/percona-release enable valkey-9\\.0/percona-release enable ${VALKEY_REPO}/g' \
                valkey-packaging/scripts/test_packages.sh

            # Prepare a systemd-capable Docker image
            cat > Dockerfile.test-${BUILD_NUMBER} <<'DEOF'
FROM ${DOCKER_OS}
RUN if [ -f /etc/debian_version ]; then \\
        apt-get update -qq && \\
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq systemd systemd-sysv wget procps curl gnupg2 lsb-release; \\
    else \\
        yum install -y systemd wget procps-ng curl; \\
    fi
RUN [ -f /sbin/init ] || ln -s /lib/systemd/systemd /sbin/init
CMD ["/sbin/init"]
DEOF
            docker build -t ${TEST_IMAGE} -f Dockerfile.test-${BUILD_NUMBER} .

            # Start privileged container with systemd
            docker run -d --privileged --name ${CONTAINER_NAME} \
                -v /sys/fs/cgroup:/sys/fs/cgroup:rw \
                ${TEST_IMAGE}

            # Wait for systemd to become ready (up to 30s)
            for i in \$(seq 1 30); do
                if docker exec ${CONTAINER_NAME} systemctl is-system-running --wait 2>/dev/null | grep -qE 'running|degraded'; then
                    echo "systemd ready after \${i}s"
                    break
                fi
                sleep 1
            done

            # Copy and execute the test script
            docker cp valkey-packaging/scripts/test_packages.sh ${CONTAINER_NAME}:/test_packages.sh
            docker exec ${CONTAINER_NAME} bash -x /test_packages.sh \
                --repo \
                --repo-channel=${COMPONENT} \
                --version=${VALKEY_VERSION}
        """
    } finally {
        sh """
            docker rm -f ${CONTAINER_NAME} || true
            docker rmi -f ${TEST_IMAGE} || true
            rm -f Dockerfile.test-${BUILD_NUMBER} || true
        """
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for tests',
             name: 'CLOUD' )
        string(
            defaultValue: 'valkey-9.0',
            description: 'Product repo for percona-release enable (e.g. valkey-9.0)',
            name: 'VALKEY_REPO')
        choice(
            choices: 'testing\nrelease\nexperimental',
            description: 'Repo channel to test packages from',
            name: 'COMPONENT')
        string(
            defaultValue: '9.0.3',
            description: 'Expected Valkey version to verify',
            name: 'VALKEY_VERSION')
        string(
            defaultValue: 'main',
            description: 'Branch of valkey-packaging to checkout',
            name: 'PACKAGING_BRANCH')
        string(
            defaultValue: 'ALL',
            description: 'Comma-separated list of platforms to test, or ALL. Available: Oracle Linux 8, Oracle Linux 9, Oracle Linux 10, Amazon Linux 2023, Ubuntu Jammy(22.04), Ubuntu Noble(24.04), Ubuntu Resolute(26.04), Debian Bullseye(11), Debian Bookworm(12), Debian Trixie(13). Append " ARM" for ARM-only (e.g. "Oracle Linux 8 ARM").',
            name: 'PLATFORMS')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Test valkey packages') {
            parallel {
                stage('Oracle Linux 8') {
                    when { expression { shouldRun('Oracle Linux 8') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("oraclelinux:8")
                    }
                }
                stage('Oracle Linux 8 ARM') {
                    when { expression { shouldRun('Oracle Linux 8 ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("oraclelinux:8")
                    }
                }
                stage('Oracle Linux 9') {
                    when { expression { shouldRun('Oracle Linux 9') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("oraclelinux:9")
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    when { expression { shouldRun('Oracle Linux 9 ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("oraclelinux:9")
                    }
                }
                stage('Oracle Linux 10') {
                    when { expression { shouldRun('Oracle Linux 10') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("oraclelinux:10")
                    }
                }
                stage('Oracle Linux 10 ARM') {
                    when { expression { shouldRun('Oracle Linux 10 ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("oraclelinux:10")
                    }
                }
                stage('Amazon Linux 2023') {
                    when { expression { shouldRun('Amazon Linux 2023') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("amazonlinux:2023")
                    }
                }
                stage('Amazon Linux 2023 ARM') {
                    when { expression { shouldRun('Amazon Linux 2023 ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("amazonlinux:2023")
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    when { expression { shouldRun('Ubuntu Jammy(22.04)') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("ubuntu:jammy")
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    when { expression { shouldRun('Ubuntu Jammy(22.04) ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("ubuntu:jammy")
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    when { expression { shouldRun('Ubuntu Noble(24.04)') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("ubuntu:noble")
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    when { expression { shouldRun('Ubuntu Noble(24.04) ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("ubuntu:noble")
                    }
                }
                stage('Ubuntu Resolute(26.04)') {
                    when { expression { shouldRun('Ubuntu Resolute(26.04)') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("ubuntu:resolute")
                    }
                }
                stage('Ubuntu Resolute(26.04) ARM') {
                    when { expression { shouldRun('Ubuntu Resolute(26.04) ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("ubuntu:resolute")
                    }
                }
                stage('Debian Bullseye(11)') {
                    when { expression { shouldRun('Debian Bullseye(11)') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("debian:bullseye")
                    }
                }
                stage('Debian Bullseye(11) ARM') {
                    when { expression { shouldRun('Debian Bullseye(11) ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("debian:bullseye")
                    }
                }
                stage('Debian Bookworm(12)') {
                    when { expression { shouldRun('Debian Bookworm(12)') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("debian:bookworm")
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    when { expression { shouldRun('Debian Bookworm(12) ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("debian:bookworm")
                    }
                }
                stage('Debian Trixie(13)') {
                    when { expression { shouldRun('Debian Trixie(13)') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        testStage("debian:trixie")
                    }
                }
                stage('Debian Trixie(13) ARM') {
                    when { expression { shouldRun('Debian Trixie(13) ARM') } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        testStage("debian:trixie")
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: tests passed for ${VALKEY_REPO} ${VALKEY_VERSION} (${COMPONENT}) - [${BUILD_URL}]")
            script {
                currentBuild.description = "Tested ${VALKEY_REPO} ${VALKEY_VERSION} (${COMPONENT})"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: tests failed for ${VALKEY_REPO} ${VALKEY_VERSION} (${COMPONENT}) - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
