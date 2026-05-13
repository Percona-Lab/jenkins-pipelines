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
        for PG_VER in 18 17 16 15 14; do
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
                    for PG_VER in 18 17 16 15 14; do
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

List selectedImages() {
    def images = []
    if (params.BUILD_PGBACKREST) images << 'pgbackrest'
    if (params.BUILD_PGBOUNCER) images << 'pgbouncer'
    if (params.BUILD_POSTGRES) images << 'postgres'
    if (params.BUILD_POSTGRES_GIS) images << 'postgres-gis'
    if (params.BUILD_UPGRADE) images << 'upgrade'
    return images
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
    agent {
        label 'docker-x64'
    }

    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-docker repository',
            name: 'GIT_PD_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-docker',
            description: 'percona/percona-docker repository',
            name: 'GIT_PD_REPO')
        booleanParam(
            name: 'BUILD_PGBACKREST',
            defaultValue: true,
            description: 'Build pgbackrest'
        )
        booleanParam(
            name: 'BUILD_PGBOUNCER',
            defaultValue: true,
            description: 'Build pgbouncer'
        )
        booleanParam(
            name: 'BUILD_POSTGRES',
            defaultValue: true,
            description: 'Build postgres'
        )
        booleanParam(
            name: 'BUILD_POSTGRES_GIS',
            defaultValue: true,
            description: 'Build postgres-gis'
        )
        booleanParam(
            name: 'BUILD_UPGRADE',
            defaultValue: true,
            description: 'Build upgrade'
        )
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
                script {
                    def images = selectedImages()

                    if (images.isEmpty()) {
                        error 'No image selected to build'
                    }

                    sh 'sudo rm -rf cloud'
                    unstash "cloud"
                    sh """
                       sudo rm -rf source
                       export GIT_REPO=$GIT_PD_REPO
                       export GIT_BRANCH=$GIT_PD_BRANCH
                       ./cloud/local/checkout
                    """

                    for (img in images) {
                        if (img == 'upgrade') {
                            retry(3) { buildUpgrade('upgrade') }
                        } else {
                            retry(3) { build(img) }
                        }
                    }
                }
            }
        }
        stage('Push Images to Docker registry') {
            steps {
                script {
                    def images = selectedImages()

                    if (images.isEmpty()) {
                        error 'No image selected to push'
                    }

                    for (img in images) {
                        if (img == 'upgrade') {
                            pushUpgradeImageToDockerHub('upgrade')
                        } else {
                            pushImageToDockerHub(img)
                        }
                    }
                }
            }
        }
    }
}
