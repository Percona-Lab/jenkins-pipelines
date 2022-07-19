void build(String IMAGE_PREFIX){
    sh """
        cd ./source
        SOURCE_ROOT_DIR=\$(pwd)
        mkdir -p src/github.com/percona
        ln -s \$SOURCE_ROOT_DIR  src/github.com/percona/percona-backup-mongodb
        cd src/github.com/percona/percona-backup-mongodb
        docker run --rm -v \$(pwd):/go/src/github.com/percona/percona-backup-mongodb -w /go/src/github.com/percona/percona-backup-mongodb golang:1.15 sh -c 'apt-get update -y && apt-get install -y libkrb5-dev && make build'
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
            TrivyLog="$WORKSPACE/trivy-hight-\$IMAGE_NAME-${IMAGE_SUFFIX}.xml"
            wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl

            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @junit.tpl  -o \$TrivytLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL perconalab/\$IMAGE_NAME:main-\${IMAGE_SUFFIX}
            "
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
                    sudo sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
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
        stage('Check PBM Docker image') {
            steps {
                checkImageForDocker('backup')
            }
            post {
                always {
                    junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-backup.xml"
                }
            }
        }
    }
    post {
        always {
            sh '''
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            '''
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PBM docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PBM docker image failed. Please check the log ${BUILD_URL}"
        }
    }
}
