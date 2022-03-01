pipeline {
    agent {
        label 'docker-farm'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
    }
    options {
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: true,
                    branch: GIT_BRANCH,
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
                        docker push public.ecr.aws/e7j3v3n0/rpmbuild:2
                    """
                }
            }
        }
    }
}
