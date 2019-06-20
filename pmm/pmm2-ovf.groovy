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
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
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
                        packer build -only virtualbox-ovf -color=false packer/pmm2.json \
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
                        FILE=\$(ls */*.ova)
                        NAME=\$(basename \${FILE})
                        aws s3 cp \
                            --only-show-errors \
                            --acl public-read \
                            \${FILE} \
                            s3://percona-vm/\${NAME}

                        echo /\${NAME} > PMM2-Server-dev-latest.ova
                        aws s3 cp \
                            --only-show-errors \
                            --website-redirect /\${NAME} \
                            PMM2-Server-dev-latest.ova \
                            s3://percona-vm/PMM2-Server-dev-latest.ova
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
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - http://percona-vm.s3-website-us-east-1.amazonaws.com/${IMAGE}"
                slackSend channel: '@puneet.kala', color: '#00FF00', message: "[${specName}]: build finished - http://percona-vm.s3-website-us-east-1.amazonaws.com/${IMAGE}"
            }
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}
