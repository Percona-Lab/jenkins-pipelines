pipeline {
    environment {
        specName = 'OVF'
    }
    agent {
        label 'virtualbox'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-images repository',
            name: 'GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm-dashboards-package,pmm-manage-package,pmm-managed-package,pmm-qan-api-package,pmm-qan-app-package,pmm-qan-app2-package,pmm-server-package,pmm-server-packages,pmm-update-package', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"
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
                stash includes: 'IMAGE', name: 'IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }

        stage('Upload') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        aws s3 cp */*.ova s3://percona-vm/ --acl public-read
                    """
                }
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
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
                slackSend channel: '@ramesh.sivaraman', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}
