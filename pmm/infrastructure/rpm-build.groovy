pipeline {
    agent none
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm repository',
            name: 'PMM_GIT_BRANCH')
    }
    options {
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(artifactNumToKeepStr: '10'))
    }
    environment {
        IMAGE_REGISTRY = "public.ecr.aws/e7j3v3n0"
    }
    stages {
        stage('Build rpmbuild image') {
            matrix {
                agent {
                    label "agent-${ARCH}"
                }
                axes {
                    axis {
                        name 'ARCH'
                        values 'amd64', 'arm64'
                    }
                }
                stages {
                    stage('Prepare') {
                        steps {
                            git poll: true,
                                branch: PMM_GIT_BRANCH,
                                url: 'https://github.com/percona/pmm.git'
                        }
                    }
                    stage('Build') {
                        steps {
                            sh """
                                cd build/docker/rpmbuild/
                                docker build --pull --tag ${IMAGE_REGISTRY}/rpmbuild:${ARCH} .
                            """
                            withCredentials([[
                                $class: 'AmazonWebServicesCredentialsBinding',
                                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                credentialsId: 'ECRRWUser',
                                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                                sh """
                                    aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin ${IMAGE_REGISTRY}
                                    docker push ${IMAGE_REGISTRY}/rpmbuild:${ARCH}
                                    docker manifest create ${IMAGE_REGISTRY}/rpmbuild:2 \
                                        ${IMAGE_REGISTRY}/rpmbuild:amd64  \
                                        ${IMAGE_REGISTRY}/rpmbuild:arm64
                                    docker manifest annotate --arch amd64 ${IMAGE_REGISTRY}/rpmbuild:2 ${IMAGE_REGISTRY}/rpmbuild:amd64
                                    docker manifest annotate --arch arm64 ${IMAGE_REGISTRY}/rpmbuild:2 ${IMAGE_REGISTRY}/rpmbuild:arm64

                                    docker manifest push ${IMAGE_REGISTRY}/rpmbuild:2
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}

