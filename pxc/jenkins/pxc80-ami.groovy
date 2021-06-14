pipeline {
    agent {
        label 'min-centos-7-x64'
    }
    parameters {
        string(
            defaultValue: 'marketplace',
            description: 'Tag/Branch for percona-images repository',
            name: 'GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare') {
            steps {
                slackSend botUser: true, channel: '#releases-ci', color: '#FFFF00', message: "PXC80-AMI: build started - ${BUILD_URL}"
                git poll: true, branch: "marketplace", url: "https://github.com/Percona-Lab/percona-images.git"
                sh """
                    sudo yum -y install unzip
                    make clean
                    make deps
                """
            }
        }

        stage('Build Image Dev-Latest') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 're-cd-aws', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o pipefail
                        ~/bin/packer build \
                        -only amazon-ebs -color=false packer/pxc80.json \
                            | tee build.log
                    """
                }
                sh 'tail build.log | grep us-east-1 | cut -d " " -f 2 > IMAGE'
                stash includes: 'IMAGE', name: 'IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }
    }

    post {
        always {
            deleteDir()
        }
        success {
            script {
                unstash 'IMAGE'
                def IMAGE = sh(returnStdout: true, script: "cat IMAGE").trim()
                slackSend botUser: true, channel: '#releases-ci', color: '#00FF00', message: "PXC80-AMI: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend botUser: true, channel: '#releases-ci', color: '#FF0000', message: "PXC80-AMI: build failed"
        }
    }
}
