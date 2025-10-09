pipeline {
    agent {
        label "agent-amd64-ol9"
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm repository',
            name: 'PMM_GIT_BRANCH')
    }
    options {
        buildDiscarder(logRotator(artifactNumToKeepStr: '20'))
    }
    environment {
        ECR_REGISTRY = "public.ecr.aws/e7j3v3n0"
        DOCKERHUB_REGISTRY = "docker.io"
        IMAGE_NAME = "rpmbuild:3"
        IMAGE_OL8_NAME = "rpmbuild:3-ol8"
    }
    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                script {
                    sh '''
                        git clone --single-branch --no-tags --branch ${PMM_GIT_BRANCH} --depth=1 https://github.com/percona/pmm.git .
                    '''
                }
            }
        }
        stage('Setup Buildx') {
            steps {
                script {
                    sh '''
                        # Ensure Buildx is available and create a new builder with the docker-container driver
                        docker buildx create --use --name pmmbuilder --driver docker-container || true
                        docker buildx inspect pmmbuilder --bootstrap
                    '''
                }
            }
        }
        stage('Login to ECR') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'ECRRWUser',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin ${ECR_REGISTRY}
                    '''
                }
            }
        }
        stage('Login to Dockerhub') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin ${DOCKERHUB_REGISTRY}
                    '''
                }
            }
        }
        stage('Build') {
            steps {
                sh '''
                    cd build/docker/rpmbuild/
                    docker buildx build --pull --platform linux/amd64,linux/arm64 \
                      --tag ${ECR_REGISTRY}/${IMAGE_NAME} \
                      --tag ${DOCKERHUB_REGISTRY}/perconalab/${IMAGE_NAME} \
                      -f Dockerfile.el9 --push .

                    docker buildx build --pull --platform linux/amd64,linux/arm64 \
                      --tag ${ECR_REGISTRY}/${IMAGE_OL8_NAME} \
                      --tag ${DOCKERHUB_REGISTRY}/perconalab/${IMAGE_OL8_NAME} \
                      -f Dockerfile.el8 --push .
                '''
            }
        }
    }
}
