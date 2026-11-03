library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline_timeout = 10

// Effective image coordinates. For EOL runs the DOCKER_ACC/DOCKER_PRODUCT/DOCKER_TAG inputs
// are N/A (the image comes from the private gated tarball, not Docker Hub), so the image is
// derived deterministically from PXC_VERSION instead.
def dockerAcc()     { return params.EOL == 'yes' ? 'percona' : params.DOCKER_ACC }
def dockerProduct() { return params.EOL == 'yes' ? 'percona-xtradb-cluster' : params.DOCKER_PRODUCT }
def dockerTag()     { return params.EOL == 'yes' ? params.PXC_VERSION : params.DOCKER_TAG }
def pxcImage()      { return "${dockerAcc()}/${dockerProduct()}:${dockerTag()}" }

// Download the PXC EOL docker image tarball from the PS private (gated) repo and load it
// locally. EOL images are not published to Docker Hub, so they are pulled from
// repo.percona.com and `docker load`ed on each build agent before testing. The tarball is
// architecture-specific (-arm64 / -amd64).
def loadEolImage(String arch) {
    withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
        // PXC_VERSION is X.Y.Z-R.P (e.g. 8.0.46-39.1). The gated repo folder drops the trailing
        // package build (.P)  -> pxc-gated-8.0.46-39, while the tarball turns that dot into a
        // dash -> percona-xtradb-cluster-8.0.46-39-1-<arch>.docker.tar
        def lastDot     = params.PXC_VERSION.lastIndexOf('.')
        def baseVersion = params.PXC_VERSION.substring(0, lastDot)   // 8.0.46-39
        def pkgBuild    = params.PXC_VERSION.substring(lastDot + 1)  // 1
        def tarball     = "percona-xtradb-cluster-${baseVersion}-${pkgBuild}-${arch}.docker.tar"
        def dockerTarURL = "https://repo.percona.com/private/\${USERNAME}-\${PASSWORD}/qa-test/pxc-gated-${baseVersion}/${tarball}"
        // Load the tarball and re-tag whatever image it contains to the EOL reference
        // (percona/percona-xtradb-cluster:${PXC_VERSION}), so trivy and the pytest suite scan
        // the downloaded EOL image without relying on the N/A DOCKER_ inputs.
        sh """
            set -e
            sudo yum install -y wget
            rm -f ${tarball}
            wget -q "${dockerTarURL}"
            LOADED_IMAGE=\$(sudo docker load -i ${tarball} | sed -n 's/^Loaded image: //p' | head -n1)
            if [ -z "\${LOADED_IMAGE}" ]; then
                echo "ERROR: could not determine image name from ${tarball}"; exit 1
            fi
            echo "Loaded EOL image: \${LOADED_IMAGE}"
            sudo docker tag "\${LOADED_IMAGE}" "${pxcImage()}"
        """
    }
}

// Parameters are defined via properties() so the DOCKER_ fields can react to EOL:
// Active Choices (CascadeChoiceParameter) makes DOCKER_ACC/DOCKER_PRODUCT/DOCKER_TAG show
// only 'NA' when EOL=yes (the image comes from the private gated tarball), and their normal
// values when EOL=no. NOTE: the Active Choices Groovy scripts require one-time in-process
// script approval the first time the job runs.
properties([
    parameters([
        [
            $class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            name: 'EOL',
            description: 'Set to yes to test an EOL PXC docker image from the PS private (qa-test gated) repo instead of Docker Hub',
            script: [
                $class: 'GroovyScript',
                script: [classpath: [], sandbox: true, script: 'return ["no", "yes"]']
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            name: 'DOCKER_ACC',
            description: 'Docker Hub account for the PXC image (N/A when EOL=yes)',
            referencedParameters: 'EOL',
            script: [
                $class: 'GroovyScript',
                script: [classpath: [], sandbox: true, script: 'return (EOL == "yes") ? ["NA"] : ["percona", "perconalab"]']
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            name: 'DOCKER_PRODUCT',
            description: 'Product to test (N/A when EOL=yes)',
            referencedParameters: 'EOL',
            script: [
                $class: 'GroovyScript',
                script: [classpath: [], sandbox: true, script: 'return (EOL == "yes") ? ["NA"] : ["percona-xtradb-cluster"]']
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            name: 'DOCKER_TAG',
            description: 'Docker tag to test (N/A when EOL=yes)',
            referencedParameters: 'EOL',
            script: [
                $class: 'GroovyScript',
                script: [classpath: [], sandbox: true, script: 'return (EOL == "yes") ? ["NA"] : ["8.0.22-13.1"]']
            ]
        ],
        string(name: 'PXC_VERSION', defaultValue: '8.0.22-13.1', description: 'Full PXC version'),
        string(name: 'PXC_REVISION', defaultValue: '428f061', description: 'Short git hash for release'),
        string(name: 'PXC_WSREP_VERSION', defaultValue: '26.4.3', description: 'Galera WSREP version'),
        string(name: 'PXC_PXB_VERSION', defaultValue: '2.4.22', description: 'PXB version installed (only for PXC 5.7)'),
        string(name: 'PXC57_PKG_VERSION', defaultValue: '5.7.33-rel36-49.1', description: 'PXC package version (only for PXC 5.7)'),
        string(name: 'PACKAGE_TESTING_REPO_URL', defaultValue: 'https://github.com/Percona-QA/package-testing.git', description: 'Package Testing Repository URL', trim: true),
        string(name: 'PACKAGE_TESTING_REPO_BRANCH', defaultValue: 'master', description: 'Package Testing Repository Branch', trim: true)
    ])
])

pipeline {
    agent { label 'docker' }
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
                    currentBuild.description = params.EOL == 'yes' ? "EOL (gated repo)" : "${DOCKER_ACC}"
                }
            }
        }
        stage('Verify Docker image exists') {
            when { expression { params.EOL == 'no' } }
            steps {
                script {
                    def image = pxcImage()
                    sh """
                        set -e
                        echo "Checking PXC image: ${image}"
                        sudo docker manifest inspect ${image} > /dev/null \
                            || { echo "ERROR: PXC image ${image} not found on registry"; exit 1; }
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
                        stage('Load EOL image ARM') {
                            when { expression { params.EOL == 'yes' } }
                            steps {
                                script {
                                    loadEolImage('arm64')
                                }
                            }
                        }
                        stage('Run trivy analyzer ARM') {
                            steps {
                                sh "sudo yum install -y wget git"
                                installTrivy(method: 'binary', junitTpl: true)
                                sh """
                                    /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit-arm.xml \
                                    --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${pxcImage()} || true
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
                                    export DOCKER_ACC="${dockerAcc()}"
                                    export DOCKER_PRODUCT="${dockerProduct()}"
                                    export DOCKER_TAG="${dockerTag()}"
                                    export PXC_VERSION="${PXC_VERSION}"
                                    export PXC_REVISION="${PXC_REVISION}"
                                    export PXC_WSREP_VERSION="${PXC_WSREP_VERSION}"
                                    export PXC_PXB_VERSION="${PXC_PXB_VERSION}"
                                    export PXC57_PKG_VERSION="${PXC57_PKG_VERSION}"
                                    export EOL="${EOL}"
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
                        stage('Load EOL image AMD') {
                            when { expression { params.EOL == 'yes' } }
                            steps {
                                script {
                                    loadEolImage('amd64')
                                }
                            }
                        }
                        stage('Run trivy analyzer AMD') {
                            steps {
                                sh "sudo yum install -y wget git"
                                installTrivy(method: 'binary', junitTpl: true)
                                sh """
                                    /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit-amd.xml \
                                    --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${pxcImage()} || true
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
                                    export DOCKER_ACC="${dockerAcc()}"
                                    export DOCKER_PRODUCT="${dockerProduct()}"
                                    export DOCKER_TAG="${dockerTag()}"
                                    export PXC_VERSION="${PXC_VERSION}"
                                    export PXC_REVISION="${PXC_REVISION}"
                                    export PXC_WSREP_VERSION="${PXC_WSREP_VERSION}"
                                    export PXC_PXB_VERSION="${PXC_PXB_VERSION}"
                                    export PXC57_PKG_VERSION="${PXC57_PKG_VERSION}"
                                    export EOL="${EOL}"
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
