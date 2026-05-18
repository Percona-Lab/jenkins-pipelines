pipeline_timeout = 10

pipeline {
    agent { label 'docker' }
    parameters {
        choice(name: 'DOCKER_ACC', choices: ['percona','perconalab'], description: 'Docker Hub account for the PXC image')
        string(name: 'DOCKER_PRODUCT', defaultValue: 'percona-xtradb-cluster', description: 'Product to test')
        string(name: 'DOCKER_TAG', defaultValue: '8.0.22-13.1', description: 'Docker tag to test')
        string(name: 'PXC_VERSION', defaultValue: '8.0.22-13.1', description: 'Full PXC version')
        string(name: 'PXC_REVISION', defaultValue: '428f061', description: 'Short git hash for release')
        string(name: 'PXC_WSREP_VERSION', defaultValue: '26.4.3', description: 'Galera WSREP version')
        string(name: 'PXC_PXB_VERSION', defaultValue: '2.4.22', description: 'PXB version installed (only for PXC 5.7)')
        string(name: 'PXC57_PKG_VERSION', defaultValue: '5.7.33-rel36-49.1', description: 'PXC package version (only for PXC 5.7)')
        string(
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: 'Package Testing Repository URL',
            name: 'PACKAGE_TESTING_REPO_URL',
            trim: true)
        string(
            defaultValue: 'master',
            description: 'Package Testing Repository Branch',
            name: 'PACKAGE_TESTING_REPO_BRANCH',
            trim: true)
    }
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Cleanup workspace') {
            steps {
                sh '''
                    sudo rm -rf package-testing package-testing-master master.zip
                    sudo rm -f report.xml *-junit-arm.xml *-junit-amd.xml
                    sudo rm -rf docker-image-tests
                '''
            }
        }
        stage('Prepare') {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${PXC_VERSION}-${PXC_REVISION}"
                    currentBuild.description = "${DOCKER_ACC}"
                }
            }
        }
        stage('Verify Docker image exists') {
            steps {
                script {
                    def pxcImage = "${DOCKER_ACC}/${DOCKER_PRODUCT}:${DOCKER_TAG}"
                    sh """
                        set -e
                        echo "Checking PXC image: ${pxcImage}"
                        sudo docker manifest inspect ${pxcImage} > /dev/null \
                            || { echo "ERROR: PXC image ${pxcImage} not found on registry"; exit 1; }
                        echo "PXC image is available."
                    """
                }
            }
        }
        stage("Run parallel") {
            parallel {
                stage('Run all tests on ARM') {
                    agent { label 'docker-32gb-aarch64' }
                    stages {
                        stage('Run trivy analyzer ARM') {
                            steps {
                                sh """
                                    sudo yum install -y wget git
                                    TRIVY_VERSION="0.69.3"
                                    ARCH=\$(uname -m)
                                    if [[ "\$ARCH" == "aarch64" ]]; then
                                        ARCH_NAME="ARM64"
                                        TRIVY_CHECKSUM="7e3924a974e912e57b4a99f65ece7931f8079584dae12eb7845024f97087bdfd"
                                    elif [[ "\$ARCH" == "x86_64" ]]; then
                                        ARCH_NAME="64bit"
                                        TRIVY_CHECKSUM="1816b632dfe529869c740c0913e36bd1629cb7688bd5634f4a858c1d57c88b75"
                                    else
                                        echo "Unsupported architecture: \$ARCH"
                                        exit 1
                                    fi
                                    echo "Detected architecture: \$ARCH, using Trivy for Linux-\$ARCH_NAME"
                                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz
                                    echo "\${TRIVY_CHECKSUM}  trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz" | sha256sum -c -
                                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz -C /usr/local/bin/
                                    wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                                    /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit-arm.xml \
                                    --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/${DOCKER_PRODUCT}:${DOCKER_TAG} || true
                                """
                            }
                            post {
                                always {
                                    junit testResults: "*-junit-arm.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                                }
                            }
                        }
                        stage('Run docker tests ARM') {
                            steps {
                                sh """
                                    sudo rm -rf package-testing
                                    git clone ${PACKAGE_TESTING_REPO_URL} --depth 1 -b ${PACKAGE_TESTING_REPO_BRANCH} package-testing
                                """
                                sh """
                                    export PATH=\${PATH}:~/.local/bin
                                    sudo yum install -y python3 python3-pip
                                    cd package-testing/docker-image-tests/pxc-arm
                                    pip3 install --user -r requirements.txt
                                    export DOCKER_ACC="${DOCKER_ACC}"
                                    export DOCKER_PRODUCT="${DOCKER_PRODUCT}"
                                    export DOCKER_TAG="${DOCKER_TAG}"
                                    export PXC_VERSION="${PXC_VERSION}"
                                    export PXC_REVISION="${PXC_REVISION}"
                                    export PXC_WSREP_VERSION="${PXC_WSREP_VERSION}"
                                    export PXC_PXB_VERSION="${PXC_PXB_VERSION}"
                                    export PXC57_PKG_VERSION="${PXC57_PKG_VERSION}"
                                    ./run.sh
                                """
                            }
                            post {
                                always {
                                    junit 'package-testing/docker-image-tests/pxc-arm/report.xml'
                                }
                            }
                        }
                    }
                }
                stage('Run all tests on AMD') {
                    agent { label 'docker-32gb' }
                    stages {
                        stage('Run trivy analyzer AMD') {
                            steps {
                                sh """
                                    sudo yum install -y wget git
                                    TRIVY_VERSION="0.69.3"
                                    ARCH=\$(uname -m)
                                    if [[ "\$ARCH" == "aarch64" ]]; then
                                        ARCH_NAME="ARM64"
                                        TRIVY_CHECKSUM="7e3924a974e912e57b4a99f65ece7931f8079584dae12eb7845024f97087bdfd"
                                    elif [[ "\$ARCH" == "x86_64" ]]; then
                                        ARCH_NAME="64bit"
                                        TRIVY_CHECKSUM="1816b632dfe529869c740c0913e36bd1629cb7688bd5634f4a858c1d57c88b75"
                                    else
                                        echo "Unsupported architecture: \$ARCH"
                                        exit 1
                                    fi
                                    echo "Detected architecture: \$ARCH, using Trivy for Linux-\$ARCH_NAME"
                                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz
                                    echo "\${TRIVY_CHECKSUM}  trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz" | sha256sum -c -
                                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz -C /usr/local/bin/
                                    wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                                    /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit-amd.xml \
                                    --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/${DOCKER_PRODUCT}:${DOCKER_TAG} || true
                                """
                            }
                            post {
                                always {
                                    junit testResults: "*-junit-amd.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                                }
                            }
                        }
                        stage('Run docker tests AMD') {
                            steps {
                                sh """
                                    sudo rm -rf package-testing
                                    git clone ${PACKAGE_TESTING_REPO_URL} --depth 1 -b ${PACKAGE_TESTING_REPO_BRANCH} package-testing
                                """
                                sh """
                                    export PATH=\${PATH}:~/.local/bin
                                    sudo yum install -y python3 python3-pip
                                    cd package-testing/docker-image-tests/pxc
                                    pip3 install --user -r requirements.txt
                                    export DOCKER_ACC="${DOCKER_ACC}"
                                    export DOCKER_PRODUCT="${DOCKER_PRODUCT}"
                                    export DOCKER_TAG="${DOCKER_TAG}"
                                    export PXC_VERSION="${PXC_VERSION}"
                                    export PXC_REVISION="${PXC_REVISION}"
                                    export PXC_WSREP_VERSION="${PXC_WSREP_VERSION}"
                                    export PXC_PXB_VERSION="${PXC_PXB_VERSION}"
                                    export PXC57_PKG_VERSION="${PXC57_PKG_VERSION}"
                                    ./run.sh
                                """
                            }
                            post {
                                always {
                                    junit 'package-testing/docker-image-tests/pxc/report.xml'
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            sh '''
                echo Finish: $(date -u "+%s")
            '''
        }
    }
}
