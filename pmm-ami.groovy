pipeline {
    environment {
        app = 'AMI'
    }
    agent {
        label 'awscli'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: '',
            name: 'GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    triggers {
        upstream upstreamProjects: 'pmm-dashboards-package,pmm-manage-package,pmm-qan-api-package,pmm-qan-app-package,pmm-server-package,pmm-server-packages,pmm-update-package', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                slackSend channel: '@mykola', color: '#FFFF00', message: "[${app}]: build started - ${env.BUILD_URL}"
                git poll: true, branch: GIT_BRANCH, url: "https://github.com/Percona-Lab/percona-images.git"
                sh """
                    make clean
                """
            }
        }

        stage('Build Image') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        packer build -only amazon-ebs -color=false packer/pmm.json \
                            | tee build.log
                    """
                }
                sh 'tail build.log | grep us-east-1 | cut -d " " -f 2 > IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }
    }

    post {
        success {
            script {
                def IMAGE = sh(returnStdout: true, script: "cat IMAGE").trim()
                slackSend channel: '@mykola', color: '#00FF00', message: "[${app}]: build finished - ${IMAGE}"
                slackSend channel: '@nailya.kutlubaeva', color: '#00FF00', message: "[${app}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend channel: '@mykola', color: '#FF0000', message: "[${app}]: build failed"
        }
    }
}
