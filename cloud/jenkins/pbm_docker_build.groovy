void build(String IMAGE_PREFIX){
    sh """
        cd ./source
        SOURCE_ROOT_DIR=\$(pwd)
        mkdir -p src/github.com/percona
        ln -s \$SOURCE_ROOT_DIR  src/github.com/percona/percona-backup-mongodb
        cd src/github.com/percona/percona-backup-mongodb
        docker run --rm -v \$(pwd):/go/src/github.com/percona/percona-backup-mongodb -w /go/src/github.com/percona/percona-backup-mongodb golang:1.14 make build
        cd \$SOURCE_ROOT_DIR
        docker build -t perconalab/percona-server-mongodb-operator:main-${IMAGE_PREFIX} -f docker/Dockerfile.k8s .
        sudo rm -rf ./vendor
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
                /usr/local/bin/trivy -q --cache-dir /mnt/trivy/ image -o \$TrityHightLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH --auto-refresh perconalab/\$IMAGE_NAME:main-\${IMAGE_SUFFIX}
                /usr/local/bin/trivy -q --cache-dir /mnt/trivy/ image -o \$TrityCriticaltLog --timeout 10m0s --ignore-unfixed --exit-code 1 --severity CRITICAL --auto-refresh perconalab/\$IMAGE_NAME:main-\${IMAGE_SUFFIX}
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
void pushImageToRhel(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'scan.connect.redhat.com-psmdb-containers', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            GIT_FULL_COMMIT=\$(git rev-parse HEAD)
            GIT_SHORT_COMMIT=\${GIT_FULL_COMMIT:0:7}
            IMAGE_ID=\$(docker images -q perconalab/percona-server-mongodb-operator:main-\$IMAGE_PREFIX)
            IMAGE_NAME='percona-server-mongodb-operator'
            IMAGE_TAG="main-\$GIT_SHORT_COMMIT-\$IMAGE_PREFIX"
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
                docker push perconalab/percona-server-mongodb-operator:main-${IMAGE_PREFIX}
            "
        """
    }
}

pipeline {
    parameters {
        string(
            defaultValue: 'main',
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
                stash includes: "source/**", name: "sourceFILES"
            }
        }
        stage('Build PBM docker images') {
            steps {
                unstash "sourceFILES"
                echo 'Build PBM docker images'
                retry(3) {
                    build('backup')
                }
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
        stage('Check PBM Docker image') {
            steps {
                checkImageForDocker('backup')
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
            sh '''
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            '''
            deleteDir()
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PBM image failed. Please check the log ${BUILD_URL}"
        }
    }
}
