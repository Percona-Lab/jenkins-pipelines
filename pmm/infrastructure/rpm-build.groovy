pipeline {
    agent {
        label "agent-amd64-ol9"
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm repository',
            name: 'PMM_GIT_BRANCH'
        )
    }
    options {
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(artifactNumToKeepStr: '10'))
    }
    environment {
        IMAGE_REGISTRY = "public.ecr.aws/e7j3v3n0"
        DOCKER_TAG = "rpmbuild"
    }
    // Tag versions: (see what's available for download at https://gallery.ecr.aws/e7j3v3n0/rpmbuild)
    // rpmbuild:2   - PMM2 el7
    // rpmbuild:ol9 - PMM2 el9
    // rpmbuild:3   - PMM3 el9
    stages {
        stage('Prepare') {
            steps {
                git poll: true,
                    branch: PMM_GIT_BRANCH,
                    url: 'https://github.com/percona/pmm.git'
            }
        }
        stage('Setup Buildx') {
            steps {
                script {
                    sh '''
                        # Ensure Buildx is available and create a new builder with the docker-container driver
                        docker buildx create --use --name multiarch-builder --driver docker-container || true
                        docker buildx inspect multiarch-builder --bootstrap
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
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin ${IMAGE_REGISTRY}
                    '''
                }
            }
        }
        stage('Build rpmbuild images el7') {
            steps {
                sh '''
                    cd build/docker/rpmbuild/
                    docker buildx build --pull --platform linux/amd64,linux/arm64 --tag ${IMAGE_REGISTRY}/${DOCKER_TAG}:2 --push .
                '''
            }
        }
        stage('Build rpmbuild images ol9') {
            steps {
                sh '''
                    cd build/docker/rpmbuild/
                    docker buildx build --pull --platform linux/amd64,linux/arm64 --tag ${IMAGE_REGISTRY}/${DOCKER_TAG}:ol9 -f Dockerfile.el9 --push .
                '''
            }
        }
    }
}

