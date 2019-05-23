void build(String IMAGE_PREFIX){
    sh """
        cd ./source/
        DOCKER_FILE_PREFIX=\$(echo ${IMAGE_PREFIX} | tr -d '.')
        docker build --no-cache --squash -t perconalab/percona-server-mongodb-operator:master-${IMAGE_PREFIX} -f build/Dockerfile.\$DOCKER_FILE_PREFIX .
    """
}

void pushImageToDocker(String IMAGE_PREFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                docker push perconalab/percona-server-mongodb-operator:master-${IMAGE_PREFIX}
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
            IMAGE_ID=\$(docker images -q perconalab/percona-server-mongodb-operator:master)
            IMAGE_NAME='percona-server-mongodb-operator'
            IMAGE_TAG="master-\$GIT_SHORT_COMMIT"
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
void pushImageToRhel(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'scan.connect.redhat.com-psmdb-containers', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            GIT_FULL_COMMIT=\$(git rev-parse HEAD)
            GIT_SHORT_COMMIT=\${GIT_FULL_COMMIT:0:7}
            IMAGE_ID=\$(docker images -q perconalab/percona-server-mongodb-operator:master-\$IMAGE_PREFIX)
            IMAGE_NAME='percona-server-mongodb-operator'
            IMAGE_TAG="master-\$GIT_SHORT_COMMIT-\$IMAGE_PREFIX"
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
            defaultValue: 'master',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona/percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
    }
    agent {
         label 'docker' 
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout
                '''
                stash includes: "source/**", name: "sourceFILES"
            }
        }

        stage('Build PSMDBO operator docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        cd ./source/
                        sg docker -c "
                            docker login -u '${USER}' -p '${PASS}'
                            ./e2e-tests/build
                            docker logout
                        "
                    '''
                }
            }
        }

        stage('Build PSMDB docker images') {
            steps {
                unstash "sourceFILES"
                echo 'Build PSMDB docker images'
                build('mongod3.6')
                build('mongod4.0')
            }
        }

        stage('Push PSMDB images to Docker registry') {
            steps {
                pushImageToDocker('mongod3.6')
                pushImageToDocker('mongod4.0')
            }
        }

        stage('Push PSMDB images to RHEL registry') {
            steps {
                pushImageToRhel('mongod3.6')
                pushImageToRhel('mongod4.0')
                pushImageToRhelOperator()
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
    }
}
