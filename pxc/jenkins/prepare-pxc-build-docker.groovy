void build(String SOURCE_IMAGE) {
    def ARCH_SUFFIX = params.ARCH == "aarch64" ? "-aarch64" : "";
    sh """
        sg docker -c "
            ./pxc/docker/prepare-docker ${SOURCE_IMAGE}
        "
    """
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'ECRRWUser', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            SOURCE_IMAGE=${SOURCE_IMAGE}
            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
            sg docker -c "
                docker push public.ecr.aws/e7j3v3n0/pxc-build:\${SOURCE_IMAGE//[:\\/]/-}${ARCH_SUFFIX}
            "
        """
    }
}

pipeline {
    agent {
        label params.ARCH == 'aarch64' ? 'docker-32gb-aarch64' : 'docker-32gb'
    }
    options {
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: true, branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh '''
                    git reset --hard
                    git clean -xdf
                '''
            }
        }
        stage('Build') {
            steps {
                script {
                    def builders = [
                        "oraclelinux:8":    { build('oraclelinux:8') },
                        "oraclelinux:9":    { build('oraclelinux:9') },
                        "oraclelinux:10":   { build('oraclelinux:10') },
                        "ubuntu:focal":     { build('ubuntu:focal') },
                        "ubuntu:jammy":     { build('ubuntu:jammy') },
                        "ubuntu:noble":     { build('ubuntu:noble') },
                        "debian:bullseye":  { build('debian:bullseye') },
                        "debian:bookworm":  { build('debian:bookworm') },
                        "debian:trixie":    { build('debian:trixie') },
                        "amazonlinux:2023": { build('amazonlinux:2023') },
                    ]
                    if (params.ARCH != 'aarch64') {
                        builders["centos:7"] = { build('centos:7') }
                    }
                    parallel builders
                }
            }
        }
    }
}
