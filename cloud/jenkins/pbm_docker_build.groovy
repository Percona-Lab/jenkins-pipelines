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
        stage('Build PSMDB docker images') {
            steps {
                unstash "sourceFILES"
                echo 'Build PBM docker images'
                build('backup')
                
                echo 'Push PBM docker images to docker hub'
                pushImage('backup')
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}
