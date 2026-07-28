pipeline_timeout = 10

pipeline {
    agent { label 'docker' }
    parameters {
        choice(name: 'PXB_DOCKER_ACC', choices: ['percona','perconalab'], description: 'Docker Hub account for the PXB image')
        choice(name: 'PS_DOCKER_ACC',  choices: ['percona','perconalab'], description: 'Docker Hub account for the PS image')
        choice(
            choices: ['PXB24', 'PXB80', 'PXB84', 'PXB97', 'PXB_INN_LTS'],
            description: 'Choose the PXB version to test',
            name: 'PRODUCT_TO_TEST'
        )
        choice(
            choices: 'ps\nms',
            description: 'Version of repository',
            name: 'server')
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
        //skipStagesAfterUnstable()
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
        stage('SET VERSION and REVISION for PXB and PS') {
            steps {
                script {
                    sh """
                        rm -rf package-testing package-testing-master master.zip
                        git clone ${PACKAGE_TESTING_REPO_URL} --depth 1 -b ${PACKAGE_TESTING_REPO_BRANCH} package-testing
                    """
                            
                    def grepVar = { key ->
                        sh(
                            script: "grep '^${key}=' package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/\"//g'",
                            returnStdout: true
                        ).trim()
                    }

                    def PXB_VER = grepVar("${PRODUCT_TO_TEST}_VER")
                    def PXB_PKG_VER = grepVar("${PRODUCT_TO_TEST}_PKG_VER")
                    def PXB_REV = grepVar("${PRODUCT_TO_TEST}_REV")

                    // Map PXB versions to PS versions for docker image testing
                    def PS_VERSION_MAP = [
                        'PXB24': 'PS57',
                        'PXB80': 'PS80',
                        'PXB84': 'PS84',
                        'PXB97': 'PS97',
                        'PXB_INN_LTS': 'PS_INN_LTS'
                    ]
                    def PS_PRODUCT = PS_VERSION_MAP[PRODUCT_TO_TEST]
                    def PS_VER = grepVar("${PS_PRODUCT}_VER")
                    def PS_REV = grepVar("${PS_PRODUCT}_REV")

                    // The 9.x LTS lines (PXB_INN_LTS, PXB97) already contain the pkg
                    // suffix in _VER (e.g. "9.6.0-1" / "9.7.1-1"); PXB24/80/84 _VER is
                    // upstream-only, needs _PKG_VER appended.
                    if (PRODUCT_TO_TEST == 'PXB_INN_LTS' || PRODUCT_TO_TEST == 'PXB97') {
                        env.PXB_VERSION = "${PXB_VER}"
                    } else {
                        env.PXB_VERSION = "${PXB_VER}-${PXB_PKG_VER}"
                    }

                    // The 9.x LTS lines carry a _REV in VERSIONS; fail loudly if it's
                    // missing so the docker test doesn't silently skip the revision check.
                    if ((PRODUCT_TO_TEST == 'PXB_INN_LTS' || PRODUCT_TO_TEST == 'PXB97') && !PXB_REV) {
                        error("${PRODUCT_TO_TEST}_REV not found in package-testing/VERSIONS — add ${PRODUCT_TO_TEST}_REV=\"<sha>\" so the docker test can assert it.")
                    }
                    env.PXB_REVISION = "${PXB_REV}"

                    if (!PS_VER) {
                        error("${PS_PRODUCT}_VER not found in package-testing/VERSIONS")
                    }
                    if (!PS_REV) {
                        error("${PS_PRODUCT}_REV not found in package-testing/VERSIONS")
                    }
                    env.PS_VERSION = "${PS_VER}"
                    env.PS_REVISION = "${PS_REV}"

                    // For innovation repos, docker_backup_tests.sh requires explicit
                    // full Docker Hub tags for the PS and PXB images (passed as
                    // 4th and 5th args). PS and PXB tags can differ.
                    env.PS_DOCKER_TAG  = "${PS_VER}"
                    env.PXB_DOCKER_TAG = "${env.PXB_VERSION}"
                    
                    // Map PRODUCT_TO_TEST to repo_name format for backup tests
                    def REPO_NAME_MAP = [
                        'PXB24': 'pxb-24',
                        'PXB80': 'pxb-80',
                        'PXB84': 'pxb-84-lts',
                        'PXB97': 'pxb-97-lts',
                        'PXB_INN_LTS': 'pxb-9x-innovation'
                    ]
                    env.REPO_NAME = REPO_NAME_MAP[PRODUCT_TO_TEST]

                    echo "PXB_VERSION computed: ${env.PXB_VERSION}"
                    echo "PXB_REVISION fetched: ${env.PXB_REVISION}"
                    echo "PS_VERSION fetched: ${env.PS_VERSION}"
                    echo "PS_REVISION fetched: ${env.PS_REVISION}"
                    echo "REPO_NAME derived: ${env.REPO_NAME}"
                }
            }
        }
        stage('Set environmental variable') {
            steps {
                script {
                    echo "Using PS_VERSION: ${env.PS_VERSION}"
                    echo "Using PS_REVISION: ${env.PS_REVISION}"
                }
            }
        }
        stage('Prepare') {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-PXB-${env.PXB_VERSION}-${env.PXB_REVISION}-PS-${env.PS_VERSION}-${env.PS_REVISION}"
                    currentBuild.description = "PXB:${PXB_DOCKER_ACC} PS:${PS_DOCKER_ACC}"
                }
            }
        }
        stage('Verify Docker images exist') {
            steps {
                script {
                    def pxbImage = "${PXB_DOCKER_ACC}/percona-xtrabackup:${env.PXB_VERSION}"
                    def psImage  = "${PS_DOCKER_ACC}/percona-server:${env.PS_VERSION}"
                    sh """
                        set -e
                        echo "Checking PXB image: ${pxbImage}"
                        sudo docker manifest inspect ${pxbImage} > /dev/null \
                            || { echo "ERROR: PXB image ${pxbImage} not found on registry"; exit 1; }
                        echo "Checking PS image:  ${psImage}"
                        sudo docker manifest inspect ${psImage} > /dev/null \
                            || { echo "ERROR: PS image ${psImage} not found on registry (backup test will fail)"; exit 1; }
                        echo "Both images are available."
                    """
                }
            }
        }
        stage("Run parallel") {
            parallel {
                stage('Run all tests on ARM') {
                    agent { label 'docker-32gb-aarch64' }
                    stages {
                        stage('Docker Backup Tests ARM') {
                            steps {
                                sh """
                                    sudo rm -rf package-testing
                                    git clone ${PACKAGE_TESTING_REPO_URL} --depth 1 -b ${PACKAGE_TESTING_REPO_BRANCH} package-testing
                                """
                                sh """ cd package-testing/docker-image-tests/pxb/
                                    chmod +x docker_backup_tests.sh
                                    # Remove -t flags from docker commands in the script to avoid TTY issues
                                    sed -i 's/docker run -it/docker run -i/g' docker_backup_tests.sh
                                    sed -i 's/docker run -t/docker run /g' docker_backup_tests.sh

                                    echo "=== Host arch (before backup test) ==="
                                    echo "uname -m: \$(uname -m)"
                                    echo "uname -a: \$(uname -a)"

                                    sudo PS_DOCKER_ACC=${PS_DOCKER_ACC} PXB_DOCKER_ACC=${PXB_DOCKER_ACC} ./docker_backup_tests.sh ${env.REPO_NAME} ${server} ${env.PS_DOCKER_TAG} ${env.PXB_DOCKER_TAG}"""


                                sh """
                                    echo "=== Listing Docker Images on ARM ==="
                                    sudo docker images | head -20
                                    echo ""
                                    echo "=== Listing images matching ${PS_DOCKER_ACC}/percona-server ==="
                                    sudo docker images ${PS_DOCKER_ACC}/percona-server
                                    echo ""
                                    echo "=== Listing images matching ${PXB_DOCKER_ACC}/percona-xtrabackup ==="
                                    sudo docker images ${PXB_DOCKER_ACC}/percona-xtrabackup || echo "No percona-xtrabackup images found"
                                    echo ""
                                """

                            }
                        }
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
                                    --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${PXB_DOCKER_ACC}/percona-xtrabackup:${env.PXB_VERSION} || true
                                """
                            }
                            post {
                                always {
                                    junit testResults: "*-junit-arm.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                                }
                            }
                        }
                        stage('Run docker binary version check tests ARM') {
                            steps {
                                sh """
                                    # disable THP on the host for TokuDB
                                    echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                                    echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                                    chmod +x disable_thp.sh
                                    sudo ./disable_thp.sh
                                    # run test
                                    export PATH=\${PATH}:~/.local/bin
                                    sudo yum install -y python3 python3-pip
                                    cd package-testing/docker-image-tests/pxb
                                    pip3 install --user -r requirements.txt
                                    export PXB_VERSION="${env.PXB_VERSION}"
                                    export PXB_REVISION="${env.PXB_REVISION}"
                                    export PXB_DOCKER_ACC="${PXB_DOCKER_ACC}"
                                    echo "printing variables: \$PXB_DOCKER_ACC , \$PXB_VERSION , \$PXB_REVISION"
                                    sudo docker run --rm --entrypoint xtrabackup \${PXB_DOCKER_ACC}/percona-xtrabackup:\${PXB_VERSION} --version
                                    sudo docker run --rm --entrypoint xbcloud    \${PXB_DOCKER_ACC}/percona-xtrabackup:\${PXB_VERSION} --version
                                    sudo docker run --rm --entrypoint xbcrypt    \${PXB_DOCKER_ACC}/percona-xtrabackup:\${PXB_VERSION} --version
                                    sudo docker run --rm --entrypoint xbstream   \${PXB_DOCKER_ACC}/percona-xtrabackup:\${PXB_VERSION} --version
                                    ./run.sh
                                """
                            }
                            post {
                                always {
                                    junit 'package-testing/docker-image-tests/pxb/report.xml'
                                }
                            }
                        }
                    }
                }
                stage('Run all tests on AMD') {
                    agent { label 'docker-32gb' }
                    stages {
                        stage('Docker Backup Tests AMD') {
                            steps {
                                sh """
                                    sudo rm -rf package-testing
                                    git clone ${PACKAGE_TESTING_REPO_URL} --depth 1 -b ${PACKAGE_TESTING_REPO_BRANCH} package-testing
                                """
                                sh """ cd package-testing/docker-image-tests/pxb/
                                    chmod +x docker_backup_tests.sh
                                    # Remove -t flags from docker commands in the script to avoid TTY issues
                                    sed -i 's/docker run -it/docker run -i/g' docker_backup_tests.sh
                                    sed -i 's/docker run -t/docker run /g' docker_backup_tests.sh

                                    echo "=== Host arch (before backup test) ==="
                                    echo "uname -m: \$(uname -m)"
                                    echo "uname -a: \$(uname -a)"

                                    sudo PS_DOCKER_ACC=${PS_DOCKER_ACC} PXB_DOCKER_ACC=${PXB_DOCKER_ACC} ./docker_backup_tests.sh ${env.REPO_NAME} ${server} ${env.PS_DOCKER_TAG} ${env.PXB_DOCKER_TAG}"""


                                sh """
                                    echo "=== Listing Docker Images on AMD ==="
                                    sudo docker images | head -20
                                    echo ""
                                    echo "=== Listing images matching ${PS_DOCKER_ACC}/percona-server ==="
                                    sudo docker images ${PS_DOCKER_ACC}/percona-server
                                    echo ""
                                    echo "=== Listing images matching ${PXB_DOCKER_ACC}/percona-xtrabackup ==="
                                    sudo docker images ${PXB_DOCKER_ACC}/percona-xtrabackup || echo "No percona-xtrabackup images found"
                                    echo ""
                                """

                            }
                        }
                        stage('Run trivy analyzer AMD') {
                            steps {
                                sh """
                                    sudo yum install -y wget git
                                    echo "installing curl"
                                    sudo yum install -y curl
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
                                    --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${PXB_DOCKER_ACC}/percona-xtrabackup:${env.PXB_VERSION} || true
                                """
                            }
                            post {
                                always {
                                    junit testResults: "*-junit-amd.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                                }
                            }
                        }
                        stage('Run docker binary version check tests AMD') {
                            steps {
                                sh """
                                    # disable THP on the host for TokuDB
                                    echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                                    echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                                    chmod +x disable_thp.sh
                                    sudo ./disable_thp.sh
                                    # run test
                                    export PATH=\${PATH}:~/.local/bin
                                    sudo yum install -y python3 python3-pip
                                    cd package-testing/docker-image-tests/pxb
                                    pip3 install --user -r requirements.txt
                                    export PXB_VERSION="${env.PXB_VERSION}"
                                    export PXB_REVISION="${env.PXB_REVISION}"
                                    export PXB_DOCKER_ACC="${PXB_DOCKER_ACC}"
                                    echo "printing variables: \$PXB_DOCKER_ACC , \$PXB_VERSION , \$PXB_REVISION"
                                    sudo docker run --rm --entrypoint xtrabackup \${PXB_DOCKER_ACC}/percona-xtrabackup:\${PXB_VERSION} --version
                                    sudo docker run --rm --entrypoint xbcloud    \${PXB_DOCKER_ACC}/percona-xtrabackup:\${PXB_VERSION} --version
                                    sudo docker run --rm --entrypoint xbcrypt    \${PXB_DOCKER_ACC}/percona-xtrabackup:\${PXB_VERSION} --version
                                    sudo docker run --rm --entrypoint xbstream   \${PXB_DOCKER_ACC}/percona-xtrabackup:\${PXB_VERSION} --version
                                    ./run.sh
                                """
                            }
                            post {
                                always {
                                    junit 'package-testing/docker-image-tests/pxb/report.xml'
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
                if [ -f /mnt/jenkins/workspace/pxb-docker-tests/backup_tests/backup_log ]; then
                    cat /mnt/jenkins/workspace/pxb-docker-tests/backup_tests/backup_log
                fi
                echo Finish: $(date -u "+%s")
            '''
        }
    }
}

