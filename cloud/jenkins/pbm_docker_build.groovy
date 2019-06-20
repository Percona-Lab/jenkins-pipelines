void build(String IMAGE_PREFIX){
    sh """
        cd ./source
        SOURCE_ROOT_DIR=\$(pwd)
        mkdir -p src/github.com/percona
        ln -s /mnt/jenkins/workspace/pbm-docker-build/source  src/github.com/percona/percona-backup-mongodb
        cd src/github.com/percona/percona-backup-mongodb
        docker run --rm -v \$(pwd):/go/src/github.com/percona/percona-backup-mongodb -w /go/src/github.com/percona/percona-backup-mongodb golang:1.12 make
        cd \$SOURCE_ROOT_DIR
        docker build -t perconalab/percona-server-mongodb-operator:master-${IMAGE_PREFIX} -f docker/Dockerfile.common .
        sudo rm -rf ./vendor
    """
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
void pushImageToDocker(String IMAGE_PREFIX){
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
            description: 'Tag/Branch for percona/percona-backup-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-backup-mongodb',
            description: 'percona/percona-backup-mongodb repository',
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
        stage('Build PBM docker images') {
            steps {
                unstash "sourceFILES"
                echo 'Build PBM docker images'
                build('backup')
            }
        }
        stage('Push PBM image to Docker registry') {
            steps {
                pushImageToDocker('backup')
            }
        }
        stage('Push PBM image to RHEL registry') {
            steps {
                pushImageToRhel('backup')
            }
        }
    }
    post {
        always {
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
    }
}
