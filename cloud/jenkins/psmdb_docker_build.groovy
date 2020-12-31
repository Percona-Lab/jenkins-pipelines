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
                /usr/local/bin/trivy -q --cache-dir /mnt/trivy/ image -o \$TrityHightLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX}
                /usr/local/bin/trivy -q --cache-dir /mnt/trivy/ image -o \$TrityCriticaltLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity CRITICAL perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX}
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
void pushImageToRhelOperator(){
     withCredentials([usernamePassword(credentialsId: 'scan.connect.redhat.com-psmdb-operator', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            GIT_FULL_COMMIT=\$(git rev-parse HEAD)
            GIT_SHORT_COMMIT=\${GIT_FULL_COMMIT:0:7}
            IMAGE_ID=\$(docker images -q perconalab/percona-server-mongodb-operator:main)
            IMAGE_NAME='percona-server-mongodb-operator'
            IMAGE_TAG="main-\$GIT_SHORT_COMMIT"
            if [ -n "\${IMAGE_ID}" ]; then
                sg docker -c "
                    docker login -u '${USER}' -p '${PASS}' scan.connect.redhat.com
                    docker tag \${IMAGE_ID} scan.connect.redhat.com/ospid-e90784a0-8fe7-4122-9c91-f0ce60be8314/\$IMAGE_NAME:\$IMAGE_TAG
                    docker push scan.connect.redhat.com/ospid-e90784a0-8fe7-4122-9c91-f0ce60be8314/\$IMAGE_NAME:\$IMAGE_TAG
                    docker logout
                "
            fi
        """
    }
}
void pushImageToRhel(String IMAGE_SUFFIX){
     withCredentials([usernamePassword(credentialsId: 'scan.connect.redhat.com-psmdb-containers', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=${IMAGE_SUFFIX}
            GIT_FULL_COMMIT=\$(git rev-parse HEAD)
            GIT_SHORT_COMMIT=\${GIT_FULL_COMMIT:0:7}
            IMAGE_ID=\$(docker images -q perconalab/percona-server-mongodb-operator:main-\$IMAGE_SUFFIX)
            IMAGE_NAME='percona-server-mongodb-operator'
            IMAGE_TAG="main-\$GIT_SHORT_COMMIT-\$IMAGE_SUFFIX"
            if [ -n "\${IMAGE_ID}" ]; then
                sg docker -c "
                    docker login -u '${USER}' -p '${PASS}' scan.connect.redhat.com
                    docker tag \${IMAGE_ID} scan.connect.redhat.com/ospid-5690f369-d04c-45f9-8195-5acb27d80ebf/\$IMAGE_NAME:\$IMAGE_TAG
                    docker push scan.connect.redhat.com/ospid-5690f369-d04c-45f9-8195-5acb27d80ebf/\$IMAGE_NAME:\$IMAGE_TAG
                    docker logout
                "
            fi
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
                    build('mongod3.6')
                }
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
                pushImageToDocker('mongod3.6')
                pushImageToDocker('mongod3.6-debug')
                pushImageToDocker('mongod4.0')
                pushImageToDocker('mongod4.0-debug')
                pushImageToDocker('mongod4.2')
                pushImageToDocker('mongod4.2-debug')
                pushImageToDocker('mongod4.4')
                pushImageToDocker('mongod4.4-debug')
            }
        }

        stage('Push PSMDB images to RHEL registry') {
            steps {
                pushImageToRhel('mongod3.6')
                pushImageToRhel('mongod3.6-debug')
                pushImageToRhel('mongod4.0')
                pushImageToRhel('mongod4.0-debug')
                pushImageToRhel('mongod4.2')
                pushImageToRhel('mongod4.2-debug')
                pushImageToRhel('mongod4.4')
                pushImageToRhel('mongod4.4-debug')
                pushImageToRhelOperator()
            }
        }
        stage('Check PSMDB Docker images') {
            steps {
                checkImageForDocker('main')
                checkImageForDocker('main-mongod3.6')
                checkImageForDocker('main-mongod3.6-debug')
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
