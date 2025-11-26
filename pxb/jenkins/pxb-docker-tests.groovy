pipeline_timeout = 10


def docker_test() {
    def stepsForParallel = [:] 
        stepsForParallel['Run for ARM64'] = {
            node('docker-32gb-aarch64') {
                stage("Docker tests for ARM64") {
                    script{
                        sh '''
                            echo "running test for ARM"
                            export DOCKER_PLATFORM=linux/arm64
                            # disable THP on the host for TokuDB
                            echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                            echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                            chmod +x disable_thp.sh
                            sudo ./disable_thp.sh
                            # run test
                            export PATH=${PATH}:~/.local/bin
                            sudo yum install -y python3 python3-pip
                            rm -rf package-testing
                            git clone https://github.com/Percona-QA/package-testing.git --depth 1
                            cd package-testing/docker-image-tests/ps-arm
                            pip3 install --user -r requirements.txt
                            export PS_VERSION="${PS_RELEASE}-arm64"
                            echo "printing variables: \$DOCKER_ACC , \$PS_VERSION , \$PS_REVISION "
                            ./run.sh
                        '''
                    }
                }
                stage('Docker image version check for ARM64'){
                    script{
                        sh '''
                            export PS_VERSION="${PS_RELEASE}-arm64"
                            fetched_docker_version=$(docker run -i --rm -e MYSQL_ROOT_PASSWORD=asdasd ${DOCKER_ACC}/percona-server:${PS_VERSION} \
                                bash -c "mysql --version" | awk '{print $3}')
                            echo "fetching docker version: \$fetched_docker_version"
                            if [[ "$PS_RELEASE" == "$fetched_docker_version" ]]; then 
                                echo "Run succesfully for arm"
                            else 
                                echo "Failed for arm"
                            fi
                        '''
                    }
                }
                stage('Run trivy analyzer ARM64') {
                    script{
                        sh """
                            sudo yum install -y curl wget git
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
                            /usr/local/bin/trivy image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                            --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/percona-server:${PS_RELEASE}-arm64 || true
                            echo "Ran succesfully for arm"
                        """
                    }
                }
            }
        }
        stepsForParallel['Run for AMD'] = {
            node ( 'docker' ) {
                stage("Docker image version check for AMD") {
                    script {
                         sh '''
                                echo "running the test for AMD" 
                                # disable THP on the host for TokuDB
                                echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                                echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                                chmod +x disable_thp.sh
                                sudo ./disable_thp.sh
                                # run test
                                export PATH=${PATH}:~/.local/bin
                                sudo yum install -y python3 python3-pip
                                rm -rf package-testing
                                git clone https://github.com/Percona-QA/package-testing.git --depth 1
                                cd package-testing/docker-image-tests/ps
                                pip3 install --user -r requirements.txt
                                export PS_VERSION="${PS_RELEASE}-amd64"
                                echo "printing variables: \$DOCKER_ACC , \$PS_VERSION ,\$PS_REVISION "
                                ./run.sh
                            ''' 
                        }
                    }
                stage ("Docker image version check for amd64") {
                    script{
                        sh '''
                            export PS_VERSION="${PS_RELEASE}-amd64"
                            fetched_docker_version=$(docker run -i --rm -e MYSQL_ROOT_PASSWORD=asdasd ${DOCKER_ACC}/percona-server:${PS_VERSION} \
                                bash -c "mysql --version" | awk '{print $3}')
                            echo "fetching docker version: \$fetched_docker_version"
                            if [[ "$PS_RELEASE" == "$fetched_docker_version" ]]; then 
                                echo "Run succesfully for amd"
                            else 
                                echo "Failed for amd"
                            fi 
                        '''
                    }
                }
                stage ('Run trivy analyzer for AMD') {
                    script {
                        sh """
                            sudo yum install -y curl wget git
                            TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                            wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                            sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                            wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                            /usr/local/bin/trivy image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                            --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/percona-server:${PS_RELEASE}-amd64 || true
                            echo "ran succesfully for amd docker trivy"
                        """        
                    }
                }
            }  
        }
    parallel stepsForParallel
}



pipeline {
    parameters {
        choice(
            choices: 'pxb-24\npxb-80\npxb-84-lts\npxb-9x-innovation',
            description: 'Name of the repository',
            name: 'repo_name'
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
    agent {
        label 'docker'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {

        stage('Docker Binary Version Checks') {
            steps {
                try {
                    docker_test()
                    echo "DOCKER images run successfully."
                }   catch (err) {
                    echo "Docker test block failed: ${err}"
                    currentBuild.result = 'FAILURE'
                    throw err
                }
            }

        }


        stage('Docker Backup Tests') {
                steps {
                    git branch: '${PACKAGE_TESTING_REPO_BRANCH}', url: '${PACKAGE_TESTING_REPO_URL}'

                    sh """ cd backup_tests/
                        chmod +x docker_backup_tests.sh
                        sudo ./docker_backup_tests.sh ${repo_name} ${repo_type} ${server}"""
                }
        }
    }
    post {
        always {
            sh '''
                cat /mnt/jenkins/workspace/pxb-docker-tests/backup_tests/backup_log
                echo Finish: \$(date -u "+%s")
            '''
        }
    }
}

