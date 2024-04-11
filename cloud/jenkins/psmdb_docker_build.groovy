void build(String IMAGE_SUFFIX){
    sh """
        cd ./source/
        if [ ${IMAGE_SUFFIX} = backup ]; then
            docker build --no-cache --progress plain --squash -t perconalab/percona-server-mongodb-operator:main-${IMAGE_SUFFIX} -f percona-backup-mongodb/Dockerfile percona-backup-mongodb
        else
            DOCKER_FILE_PREFIX=\$(echo ${IMAGE_SUFFIX} | tr -d 'mongod')
            docker build --no-cache --progress plain --squash -t perconalab/percona-server-mongodb-operator:main-${IMAGE_SUFFIX} -f percona-server-mongodb-\$DOCKER_FILE_PREFIX/Dockerfile percona-server-mongodb-\$DOCKER_FILE_PREFIX
            docker build --build-arg DEBUG=1 --no-cache --progress plain --squash -t perconalab/percona-server-mongodb-operator:main-${IMAGE_SUFFIX}-debug -f percona-server-mongodb-\$DOCKER_FILE_PREFIX/Dockerfile percona-server-mongodb-\$DOCKER_FILE_PREFIX
        fi
    """
}
void checkImageForDocker(String IMAGE_SUFFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=${IMAGE_SUFFIX}
            IMAGE_NAME='percona-server-mongodb-operator'
            TrivyLog="$WORKSPACE/trivy-\$IMAGE_NAME-${IMAGE_SUFFIX}-psmdb.xml"

            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \$TrivyLog --timeout 40m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX}
            "
        """
    }
}

void pushImageToDocker(String IMAGE_SUFFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=${IMAGE_SUFFIX}
            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                docker push perconalab/percona-server-mongodb-operator:main-${IMAGE_SUFFIX}
                docker logout
            "
        """
    }
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona/percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-docker repository',
            name: 'GIT_PD_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-docker',
            description: 'percona/percona-docker repository',
            name: 'GIT_PD_REPO')
    }
    agent {
         label 'docker'
    }
    environment {
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/

                    if [ ! -f junit.tpl ]; then
                        wget --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    fi

                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout
                """
                stash includes: "cloud/**" , name: "checkout"
                stash includes: "source/**", name: "sourceFILES"

                sh '''
                    rm -rf cloud
                '''
            }
        }

        stage('Build and push PSMDB operator docker image') {
            steps {
                retry(3) {
                    timeout(time: 30, unit: 'MINUTES') {
                        unstash "sourceFILES"
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh '''
                                docker buildx create --use
                                cd ./source/
                                sg docker -c "
                                    docker login -u '${USER}' -p '${PASS}'
                                    RHEL=1 DOCKER_DEFAULT_PLATFORM='linux/amd64,linux/arm64' ./e2e-tests/build
                                    docker logout
                                "
                            '''
                        }
                    }
                }
            }
        }


        stage('Build PSMDB docker images') {
            steps {
                unstash "checkout"
                sh """
                    sudo rm -rf ./source
                    export GIT_REPO=$GIT_PD_REPO
                    export GIT_BRANCH=$GIT_PD_BRANCH
                    ./cloud/local/checkout
                """
                echo 'Build PBM docker image'
                retry(3) {
                    build('backup')
                }
                echo 'Build PSMDB docker images'
                retry(3) {
                    build('mongod5.0')
                }
                retry(3) {
                    build('mongod6.0')
                }
                retry(3) {
                    build('mongod7.0')
                }
            }
        }

        stage('Push PSMDB images to Docker registry') {
            steps {
                pushImageToDocker('mongod5.0')
                pushImageToDocker('mongod5.0-debug')
                pushImageToDocker('mongod6.0')
                pushImageToDocker('mongod6.0-debug')
                pushImageToDocker('mongod7.0')
                pushImageToDocker('mongod7.0-debug')
                pushImageToDocker('backup')
            }
        }
       stage('Trivy Checks') {
            parallel {
                stage('psmdb operator'){
                    steps {
                        checkImageForDocker('main')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-main-psmdb.xml"
                        }
                    }
                }
                stage('mongod5.0'){
                    steps {
                        checkImageForDocker('main-mongod5.0')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-mongod5.0-psmdb.xml"
                        }
                    }
                }
                stage('mongod6.0'){
                    steps {
                        checkImageForDocker('main-mongod6.0')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-mongod6.0-psmdb.xml"
                        }
                    }
                }
                stage('mongod7.0'){
                    steps {
                        checkImageForDocker('main-mongod7.0')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-mongod7.0-psmdb.xml"
                        }
                    }
                }
                stage('mongod5.0-debug'){
                    steps {
                        checkImageForDocker('main-mongod5.0-debug')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-main-mongod5.0-debug-psmdb.xml"
                        }
                    }
                }
                stage('mongod6.0-debug'){
                    steps {
                        checkImageForDocker('main-mongod6.0-debug')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-main-mongod6.0-debug-psmdb.xml"
                        }
                    }
                }
                stage('mongod7.0-debug'){
                    steps {
                        checkImageForDocker('main-mongod7.0-debug')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-main-mongod7.0-debug-psmdb.xml"
                        }
                    }
                }
                stage('PBM'){
                    steps {
                        checkImageForDocker('main-backup')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-main-backup-psmdb.xml"
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./source/build
            '''
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PSMDB docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PSMDB docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
