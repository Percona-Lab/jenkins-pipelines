pipeline {
    environment {
        app = 'OVF'
    }
    agent {
        label 'virtualbox'
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
                    make fetch
                """
            }
        }

        stage('Build Image') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        packer build -only virtualbox-ovf -color=false packer/pmm.json \
                            | tee build.log
                    """
                }
                sh 'ls */*.ova | cut -d "/" -f 2 > IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }

        stage('Upload') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        aws s3 cp */*.ova s3://percona-vm/
                    """
                }
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
