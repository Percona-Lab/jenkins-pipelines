pipeline {
    agent none
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'SUBMODULES_GIT_BRANCH')
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
                                branch: SUBMODULES_GIT_BRANCH,
                                url: 'https://github.com/Percona-Lab/pmm-submodules.git'
                        }
                    }
                    stage('Build') {
                        steps {
                            sh """
                                cd build/rpmbuild-docker
                                docker build --pull --squash --tag public.ecr.aws/e7j3v3n0/rpmbuild:2 .
                            """
                            withCredentials([[
                                $class: 'AmazonWebServicesCredentialsBinding',
                                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                credentialsId: 'ECRRWUser',
                                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                                sh """
                                    aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                                    docker push ${IMAGE_REGISTRY}/rpmbuild:${ARCH}
                                    docker manifest create ${IMAGE_REGISTRY}/rpmbuild:2 \
                                        ${IMAGE_REGISTRY}/rpmbuild:amd64  \
                                        ${IMAGE_REGISTRY}/rpmbuild:arm64
                                    docker manifest annotate --arch ${ARCH} ${IMAGE_REGISTRY}/rpmbuild:2 ${IMAGE_REGISTRY}/rpmbuild:${ARCH}
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

