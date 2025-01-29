void buildUpgrade(String IMAGE_POSTFIX){
    sh """
        PG_VER='17'
        IMAGE_POSTFIX='upgrade'
        cd ./source/
        docker build --no-cache --squash --build-arg PG_MAJOR=\${PG_VER} --build-arg PGO_TAG=\${GIT_PD_BRANCH} \
          -t perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
          -f ./postgresql-containers/build/${IMAGE_POSTFIX}/Dockerfile ./postgresql-containers
    """
}
void checkUpgradeImage(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        withEnv(["SOME_IMAGE_POSTFIX=${IMAGE_POSTFIX}"]) {
            sh '''
                sg docker -c "
                    IMAGE_NAME='percona-postgresql-operator'
                    docker login -u '${USER}' -p '${PASS}'

                    TrivyLog="$WORKSPACE/trivy-hight-\\${IMAGE_NAME}-\\${SOME_IMAGE_POSTFIX}.xml"
                    /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \\${TrivyLog} --ignore-unfixed --timeout 20m --exit-code 0 \
                        --severity HIGH,CRITICAL perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}
                "
            '''
        }
    }
}

void pushUpgradeImageToDockerHub(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),
                      [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withEnv(["SOME_IMAGE_POSTFIX=${IMAGE_POSTFIX}"]) {
            sh '''
                sg docker -c "
                    IMAGE_NAME='percona-postgresql-operator'
                    docker login -u '${USER}' -p '${PASS}'
                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $ECR
                    docker push perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}
                    docker tag perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX} $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}
                    docker push $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}
                    docker logout
                "
            '''
        }
    }
}
void build(String IMAGE_POSTFIX){
    sh """
        cd ./source/
        for PG_VER in 17 16 15 14 13; do
            docker build --no-cache --squash --build-arg PG_MAJOR=\${PG_VER} --build-arg PGO_TAG=\${GIT_PD_BRANCH} \
                -t perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX} \
                -f ./postgresql-containers/build/${IMAGE_POSTFIX}/Dockerfile ./postgresql-containers
        done
    """
}
void checkImageForDocker(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        withEnv(["SOME_IMAGE_POSTFIX=${IMAGE_POSTFIX}"]) {
            sh '''
                sg docker -c "
                    IMAGE_NAME='percona-postgresql-operator'
                    docker login -u '${USER}' -p '${PASS}'

                    for PG_VER in 17 16 15 14 13; do
                        TrivyLog="$WORKSPACE/trivy-hight-\\${IMAGE_NAME}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}.xml"
                        /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \\${TrivyLog} --ignore-unfixed --timeout 20m --exit-code 0 \
                            --severity HIGH,CRITICAL perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}

                    done
                "
            '''
        }
    }
}

void pushImageToDockerHub(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),
                      [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withEnv(["SOME_IMAGE_POSTFIX=${IMAGE_POSTFIX}"]) {
            sh '''
                sg docker -c "
                    IMAGE_NAME='percona-postgresql-operator'
                    docker login -u '${USER}' -p '${PASS}'
                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $ECR
                    for PG_VER in 17 16 15 14 13; do
                        docker push perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}
                        docker tag perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX} $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}
                        docker push $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}
                    done
                    docker logout
                "
            '''
        }
    }
}
pipeline {
    parameters {
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
        ECR = "119175775298.dkr.ecr.us-east-1.amazonaws.com"
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
                    sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                """
                stash includes: "cloud/**", name: "cloud"
            }
        }
        stage('Build PG database related docker images') {
            steps {
                sh '''
                    sudo rm -rf cloud
                '''
                unstash "cloud"
                sh """
                   sudo rm -rf source
                   export GIT_REPO=$GIT_PD_REPO
                   export GIT_BRANCH=$GIT_PD_BRANCH
                   ./cloud/local/checkout
                """
                retry(3) {
                    build('pgbackrest')
                }
                retry(3) {
                    build('pgbouncer')
                }
                retry(3) {
                    build('postgres')
                }
                retry(3) {
                    build('postgres-gis')
                }
                retry(3) {
                    buildUpgrade('upgrade')
                }
            }
        }
        stage('Push Images to Docker registry') {
            steps {
                pushImageToDockerHub('pgbackrest')
                pushImageToDockerHub('pgbouncer')
                pushImageToDockerHub('postgres')
                pushImageToDockerHub('postgres-gis')
                pushUpgradeImageToDockerHub('upgrade')
            }
        }
        stage('Trivy Checks') {
            parallel {
                stage('pgbackrest'){
                    steps {
                        checkImageForDocker('pgbackrest')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pgbackrest.xml"
                        }
                    }
                }
                stage('pgbouncer'){
                    steps {
                        checkImageForDocker('pgbouncer')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pgbouncer.xml"
                        }
                    }
                }
                stage('postgres'){
                    steps {
                        checkImageForDocker('postgres')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-postgres.xml"
                        }
                    }
                }
                stage('postgres-gis'){
                    steps {
                        checkImageForDocker('postgres-gis')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-postgres-gis.xml"
                        }
                    }
                }
                stage('upgrade'){
                    steps {
                        checkUpgradeImage('upgrade')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-upgrade.xml"
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.pdf', allowEmptyArchive: true
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PG docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PG docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
