pipeline_timeout = 10

pipeline {
    agent { label 'docker' }
    parameters {
        choice(name: 'DOCKER_ACC', choices: ['percona','perconalab'], description: 'Docker repo to use: percona or perconalab')
        choice(
            choices: ['PXB24', 'PXB80', 'PXB84', 'PXB_INN_LTS'],
            description: 'Choose the PXB version to test',
            name: 'PRODUCT_TO_TEST'
        )
        choice(
            choices: 'release\ntesting\nexperimental',
            description: 'Type of repository',
            name: 'repo_type'
        )
        choice(
            choices: 'ps\nms',
            description: 'Version of repository',
            name: 'server')
        string(
            defaultValue: 'https://github.com/Percona-QA/percona-qa.git',
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
        stage('SET PS_VERSION and PS_REVISION') {
            steps {
                script {
                    sh '''
                        rm -rf /package-testing
                        rm -f master.zip
                        wget https://github.com/Percona-QA/package-testing/archive/master.zip
                        unzip master.zip
                        rm -f master.zip
                        mv "package-testing-master" package-testing
                    '''
                            
                    def VERSION = sh(
                        script: "grep ${PRODUCT_TO_TEST}_VER package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/\"//g'",
                        returnStdout: true
                        ).trim()

                    def PKG_VERSION = sh(
                        script: "grep ${PRODUCT_TO_TEST}_PKG_VER package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/\"//g'",
                        returnStdout: true
                        ).trim()

                    def REVISION = sh(
                        script: "grep ${PRODUCT_TO_TEST}_REV package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/\"//g'",
                        returnStdout: true
                        ).trim()
                    
                    // Map PXB versions to PS versions for docker image testing
                    def PS_VERSION_MAP = [
                        'PXB24': 'PS57',
                        'PXB80': 'PS80',
                        'PXB84': 'PS84',
                        'PXB_INN_LTS': 'PS_INN_LTS'
                    ]
                    
                    def PS_PRODUCT = PS_VERSION_MAP[PRODUCT_TO_TEST]
                    def PS_VER = sh(
                        script: "grep ${PS_PRODUCT}_VER package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/\"//g'",
                        returnStdout: true
                        ).trim()
                    
                    env.VERSION = "${VERSION}"
                    
                    env.PKG_VERSION = "${PKG_VERSION}"

                    env.PS_VERSION = "${PS_VER}"

                    env.PS_REVISION = REVISION ?: sh(
                        script: "grep ${PS_PRODUCT}_REV package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/\"//g'",
                        returnStdout: true
                        ).trim()
                    
                    // Map PRODUCT_TO_TEST to repo_name format for backup tests
                    def REPO_NAME_MAP = [
                        'PXB24': 'pxb-24',
                        'PXB80': 'pxb-80',
                        'PXB84': 'pxb-84-lts',
                        'PXB_INN_LTS': 'pxb-9x-innovation'
                    ]
                    env.REPO_NAME = REPO_NAME_MAP[PRODUCT_TO_TEST]

                    echo "VERSION fetched: ${env.VERSION}"
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
                    currentBuild.displayName = "#${BUILD_NUMBER}-${env.PS_VERSION}-${env.PS_REVISION}"
                    currentBuild.description = "${DOCKER_ACC}"
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
                                git branch: '${PACKAGE_TESTING_REPO_BRANCH}', url: '${PACKAGE_TESTING_REPO_URL}'
                                sh """ cd backup_tests/
                                    chmod +x docker_backup_tests.sh
                                    # Remove -t flags from docker commands in the script to avoid TTY issues
                                    sed -i 's/docker run -it/docker run -i/g' docker_backup_tests.sh
                                    sed -i 's/docker run -t/docker run /g' docker_backup_tests.sh
                                    sudo ./docker_backup_tests.sh ${env.REPO_NAME} ${repo_type} ${server}"""


                                sh """
                                    echo "=== Listing Docker Images on ARM ==="
                                    sudo docker images | head -20
                                    echo ""
                                    echo "=== Listing images matching ${DOCKER_ACC}/percona-server ==="
                                    sudo docker images ${DOCKER_ACC}/percona-server
                                    echo ""
                                    echo "=== Listing images matching ${DOCKER_ACC}/percona-xtrabackup ==="
                                    sudo docker images ${DOCKER_ACC}/percona-xtrabackup || echo "No percona-xtrabackup images found"
                                    echo ""
                                """

                            }
                        }
                        stage('Run trivy analyzer ARM') {
                            steps {
                                sh """
                                    sudo yum install -y wget git
                                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                                    ARCH=\$(uname -m)
                                    if [[ "\$ARCH" == "aarch64" ]]; then
                                        ARCH_NAME="ARM64"
                                    elif [[ "\$ARCH" == "x86_64" ]]; then
                                        ARCH_NAME="64bit"
                                    else
                                        echo "Unsupported architecture: \$ARCH"
                                        exit 1
                                    fi
                                    echo "Detected architecture: \$ARCH, using Trivy for Linux-\$ARCH_NAME"
                                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz
                                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz -C /usr/local/bin/
                                    wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                                    /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit-arm.xml \
                                    --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/percona-xtrabackup:${env.VERSION}-${env.PKG_VERSION} || true
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
                                    # disable THP on the host for TokuDB
                                    echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                                    echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                                    chmod +x disable_thp.sh
                                    sudo ./disable_thp.sh
                                    # run test
                                    export PATH=\${PATH}:~/.local/bin
                                    sudo yum install -y python3 python3-pip
                                    rm -rf package-testing
                                    git clone https://github.com/Percona-QA/package-testing.git --depth 1
                                    cd package-testing/docker-image-tests/ps-arm
                                    pip3 install --user -r requirements.txt
                                    export PS_VERSION="${env.PS_VERSION}"
                                    export PS_REVISION="${env.PS_REVISION}"
                                    export DOCKER_ACC="${DOCKER_ACC}"
                                    echo "printing variables: \$DOCKER_ACC , \$PS_VERSION , \$PS_REVISION"
                                    ./run.sh
                                """
                            }
                            post {
                                always {
                                    junit 'package-testing/docker-image-tests/ps-arm/report.xml'
                                }
                            }
                        }
                    }
                }
                
                stage('Run all tests on AMD') {
                    agent { label 'docker' }
                    stages {
                        stage('Docker Backup Tests AMD') {
                            steps {
                                git branch: '${PACKAGE_TESTING_REPO_BRANCH}', url: '${PACKAGE_TESTING_REPO_URL}'

                                sh """ cd backup_tests/
                                    chmod +x docker_backup_tests.sh
                                    # Remove -t flags from docker commands in the script to avoid TTY issues
                                    sed -i 's/docker run -it/docker run -i/g' docker_backup_tests.sh
                                    sed -i 's/docker run -t/docker run /g' docker_backup_tests.sh
                                    sudo ./docker_backup_tests.sh ${env.REPO_NAME} ${repo_type} ${server}"""


                                sh """
                                    echo "=== Listing Docker Images on AMD ==="
                                    sudo docker images | head -20
                                    echo ""
                                    echo "=== Listing images matching ${DOCKER_ACC}/percona-server ==="
                                    sudo docker images ${DOCKER_ACC}/percona-server
                                    echo ""
                                    echo "=== Listing images matching ${DOCKER_ACC}/percona-xtrabackup ==="
                                    sudo docker images ${DOCKER_ACC}/percona-xtrabackup || echo "No percona-xtrabackup images found"
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
                                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                                    ARCH=\$(uname -m)
                                    if [[ "\$ARCH" == "aarch64" ]]; then
                                        ARCH_NAME="ARM64"
                                    elif [[ "\$ARCH" == "x86_64" ]]; then
                                        ARCH_NAME="64bit"
                                    else
                                        echo "Unsupported architecture: \$ARCH"
                                        exit 1
                                    fi
                                    echo "Detected architecture: \$ARCH, using Trivy for Linux-\$ARCH_NAME"
                                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz
                                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz -C /usr/local/bin/
                                    wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                                    /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit-amd.xml \
                                    --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/percona-xtrabackup:${env.VERSION}-${env.PKG_VERSION} || true
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
                                    # disable THP on the host for TokuDB
                                    echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                                    echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                                    chmod +x disable_thp.sh
                                    sudo ./disable_thp.sh
                                    # run test
                                    export PATH=\${PATH}:~/.local/bin
                                    sudo yum install -y python3 python3-pip
                                    rm -rf package-testing
                                    git clone https://github.com/Percona-QA/package-testing.git --depth 1
                                    cd package-testing/docker-image-tests/ps
                                    pip3 install --user -r requirements.txt
                                    export PS_VERSION="${env.PS_VERSION}"
                                    export PS_REVISION="${env.PS_REVISION}"
                                    export DOCKER_ACC="${DOCKER_ACC}"
                                    echo "printing variables: \$DOCKER_ACC , \$PS_VERSION , \$PS_REVISION"
                                    ./run.sh
                                """
                            }
                            post {
                                always {
                                    junit 'package-testing/docker-image-tests/ps/report.xml'
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

