void build(String IMAGE_PREFIX){
    sh """
        cd ./source/
        docker build --no-cache --squash -t perconalab/percona-server-mongodb-operator:master-${IMAGE_PREFIX} -f build/Dockerfile.${IMAGE_PREFIX} .
    """
}

void pushImage(String IMAGE_PREFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                docker push perconalab/percona-server-mongodb-operator:master-${IMAGE_PREFIX}
            "
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

        stage('Build PSMDBO docker images') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        cd ./source/
                        sg docker -c "
                            docker login -u '${USER}' -p '${PASS}'
                            ./e2e-tests/build
                        "
                    '''
                }
            }
        }

        stage('Build PSMDB docker images') {
            steps {
                unstash "sourceFILES"
                echo 'Build PSMDB docker images'
                build('mongod36')
                build('mongod40')
                
                echo 'Push PSMDB docker images to docker hub'
                pushImage('mongod36')
                pushImage('mongod40')
                
                sh '''
                    sudo rm -rf ./source/build
                '''
            }
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}
