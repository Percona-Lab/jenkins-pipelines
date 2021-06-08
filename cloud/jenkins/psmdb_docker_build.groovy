void build(String IMAGE_SUFFIX){
    sh """
        cd ./source/
        DOCKER_FILE_PREFIX=\$(echo ${IMAGE_SUFFIX} | tr -d 'mongod')
        docker build --no-cache --squash -t perconalab/percona-server-mongodb-operator:main-${IMAGE_SUFFIX} -f percona-server-mongodb-\$DOCKER_FILE_PREFIX/Dockerfile.k8s percona-server-mongodb-\$DOCKER_FILE_PREFIX
        docker build --build-arg DEBUG=1 --no-cache --squash -t perconalab/percona-server-mongodb-operator:main-${IMAGE_SUFFIX}-debug -f percona-server-mongodb-\$DOCKER_FILE_PREFIX/Dockerfile.k8s percona-server-mongodb-\$DOCKER_FILE_PREFIX
    """
}
void checkImageForDocker(String IMAGE_SUFFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=${IMAGE_SUFFIX}
            IMAGE_NAME='percona-server-mongodb-operator'
            TrityHightLog="$WORKSPACE/trivy-hight-\$IMAGE_NAME-${IMAGE_SUFFIX}.log"
            TrityCriticaltLog="$WORKSPACE/trivy-critical-\$IMAGE_NAME-${IMAGE_SUFFIX}.log"

            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image -o \$TrityHightLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX}
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image -o \$TrityCriticaltLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity CRITICAL perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX}
            "

            if [ ! -s \$TrityHightLog ]; then
                rm -rf \$TrityHightLog
            fi

            if [ ! -s \$TrityCriticaltLog ]; then
                rm -rf \$TrityCriticaltLog
            fi
        """
    }
}

void pushImageToDocker(String IMAGE_SUFFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=${IMAGE_SUFFIX}
            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                docker trust sign perconalab/percona-server-mongodb-operator:main-${IMAGE_SUFFIX}
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

                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
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

        stage('Build PSMDB operator docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        cd ./source/
                        sg docker -c "
                            docker login -u '${USER}' -p '${PASS}'
                            RHEL=1 ./e2e-tests/build
                            docker logout
                        "
                    '''
                }
            }
        }

        stage('Push docker image to dockerhub') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
                    sh '''
                        sg docker -c "
                            mkdir -p /home/ec2-user/.docker/trust/private
                            cp "${docker_key}" ~/.docker/trust/private/

                            docker login -u '${USER}' -p '${PASS}'
                            export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                            docker trust sign perconalab/percona-server-mongodb-operator:main
                            docker push perconalab/percona-server-mongodb-operator:main
                            docker logout
                        "
                    '''
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
                echo 'Build PSMDB docker images'
                retry(3) {
                    build('mongod4.0')
                }
                retry(3) {
                    build('mongod4.2')
                }
                retry(3) {
                    build('mongod4.4')
                }
            }
        }

        stage('Push PSMDB images to Docker registry') {
            steps {
                pushImageToDocker('mongod4.0')
                pushImageToDocker('mongod4.0-debug')
                pushImageToDocker('mongod4.2')
                pushImageToDocker('mongod4.2-debug')
                pushImageToDocker('mongod4.4')
                pushImageToDocker('mongod4.4-debug')
            }
        }

        stage('Check PSMDB Docker images') {
            steps {
                checkImageForDocker('main')
                checkImageForDocker('main-mongod4.0')
                checkImageForDocker('main-mongod4.0-debug')
                checkImageForDocker('main-mongod4.2')
                checkImageForDocker('main-mongod4.2-debug')
                checkImageForDocker('main-mongod4.4')
                checkImageForDocker('main-mongod4.4-debug')
                sh '''
                   CRITICAL=$(ls trivy-critical-*) || true
                   if [ -n "$CRITICAL" ]; then
                       exit 1
                   fi
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./source/build
            '''
            deleteDir()
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PSMDB images failed. Please check the log ${BUILD_URL}"
        }
    }
}
