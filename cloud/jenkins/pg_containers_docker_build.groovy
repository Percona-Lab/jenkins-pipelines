void buildUpgrade(String IMAGE_POSTFIX){
    sh """
        PG_VER='18'
        IMAGE_POSTFIX='upgrade'
        cd ./source/
        docker build --no-cache --squash --build-arg PG_MAJOR=\${PG_VER} --build-arg PGO_TAG=${GIT_PD_BRANCH} \
          -t perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
          -f ./postgresql-containers/build/${IMAGE_POSTFIX}/Dockerfile ./postgresql-containers
    """
}
void pushUpgradeImageToDockerHub(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),
                      [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withEnv(["SOME_IMAGE_POSTFIX=${IMAGE_POSTFIX}"]) {
            sh '''
                IMAGE_NAME='percona-postgresql-operator'
                sg docker -c "
                    set -e
                    IMAGE_NAME='percona-postgresql-operator'
                    docker login -u '${USER}' -p '${PASS}'
                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $ECR
                    docker push perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}
                    docker tag perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX} $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}
                    docker push $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}
                    docker logout
                    echo "perconalab/\\$IMAGE_NAME:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}" >> list-of-images.txt
                "
            '''
        }
    }
}
void build(String IMAGE_POSTFIX){
    sh """
        cd ./source/
        for PG_VER in 18 17 16 15 14 13; do
            if [ ${IMAGE_POSTFIX} = pgbouncer ]; then
                docker build --no-cache --squash --build-arg PG_VERSION=\${PG_VER} --build-arg PPG_REPO='release' --build-arg PGO_TAG=${GIT_PD_BRANCH} \
                  -t perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX}\${PG_VER} \
                  -f ./percona-pgbouncer/Dockerfile ./percona-pgbouncer
            elif [ ${IMAGE_POSTFIX} = pgbackrest ]; then
                docker build --no-cache --squash --build-arg PG_VERSION=\${PG_VER} --build-arg PPG_REPO='release' --build-arg PGO_TAG=${GIT_PD_BRANCH} \
                  -t perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX}\${PG_VER} \
                  -f ./percona-pgbackrest/Dockerfile ./percona-pgbackrest
            elif [ ${IMAGE_POSTFIX} = postgres-gis ]; then
                docker build --no-cache --squash --build-arg PG_MAJOR=\${PG_VER} --build-arg PPG_REPO='release' --build-arg PGO_TAG=${GIT_PD_BRANCH} \
                  -t perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX} \
                  -f ./postgresql-containers/build/${IMAGE_POSTFIX}/Dockerfile ./postgresql-containers
            else
                docker build --no-cache --squash --build-arg PG_MAJOR=\${PG_VER} --build-arg PPG_REPO='release' --build-arg PGO_TAG=${GIT_PD_BRANCH} \
                    -t perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX} \
                    -f ./percona-distribution-postgresql-\${PG_VER}/Dockerfile ./percona-distribution-postgresql-\${PG_VER}
            fi
        done
    """
}

void pushImageToDockerHub(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),
                      [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withEnv(["SOME_IMAGE_POSTFIX=${IMAGE_POSTFIX}"]) {
            sh '''
                sg docker -c "
                    set -e
                    IMAGE_NAME='percona-postgresql-operator'
                    docker login -u '${USER}' -p '${PASS}'
                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $ECR
                    for PG_VER in 18 17 16 15 14 13; do
                        if [ \\${SOME_IMAGE_POSTFIX} = pgbouncer ] || [ \\${SOME_IMAGE_POSTFIX} = pgbackrest ]; then
                            docker push perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}\\${PG_VER}
                            echo "perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}\\${PG_VER}" >> list-of-images.txt

                            docker tag perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}\\${PG_VER} $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}\\${PG_VER}
                            docker push $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-\\${SOME_IMAGE_POSTFIX}\\${PG_VER}
                        else
                            docker push perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}
                            echo "perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}" >> list-of-images.txt

                            docker tag perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX} $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}
                            docker push $ECR/perconalab/\\${IMAGE_NAME}:${GIT_PD_BRANCH}-ppg\\${PG_VER}-\\${SOME_IMAGE_POSTFIX}
                        fi
                    done
                    docker logout
                "
            '''
        }
    }
}

void generateImageSummary(filePath) {
    def images = readFile(filePath).trim().split("\n")

    def report = "<h2>Image Summary Report</h2>\n"
    report += "<p><strong>Total Images:</strong> ${images.size()}</p>\n"
    report += "<ul>\n"

    images.each { image ->
        report += "<li>${image}</li>\n"
    }

    report += "</ul>\n"
    return report
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-postgresql-operator repository',
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
         label 'docker-x64'
    }
    environment {
        PATH = "${WORKSPACE}/node_modules/.bin:$PATH" // Add local npm bin to PATH
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
    }

    post {
        always {
            script {
                if (fileExists('list-of-images.txt')) {
                    def summary = generateImageSummary('list-of-images.txt')

                    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
                        text: "<pre>${summary}</pre>"
                    )
                    // Also save as a file if needed
                     writeFile(file: 'image-summary.html', text: summary)
                } else {
                    echo 'No list-of-images.txt file found - skipping summary generation'
                }
            }
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./source/build
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
