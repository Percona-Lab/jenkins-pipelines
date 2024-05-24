pipeline {
    agent {
        label "agent-amd64"
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm repository',
            name: 'PMM_GIT_BRANCH')
    }
    options {
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(artifactNumToKeepStr: '10'))
    }
    environment {
        IMAGE_REGISTRY = "public.ecr.aws/e7j3v3n0"
        DOCKER_TAG = "rpmbuild:3"
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
        stage('Build') {
            steps {
                sh '''
                    cd build/docker/rpmbuild/
                    docker buildx build --pull --tag ${IMAGE_REGISTRY}/${DOCKER_TAG} -f Dockerfile.el9 .
                '''
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'ECRRWUser',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin ${IMAGE_REGISTRY}
                        docker push ${IMAGE_REGISTRY}/${DOCKER_TAG}
                    '''
                }
            }
        }
    }
}
