pipeline {
    environment {
        specName = 'AMI'
    }
    agent {
        label 'docker-farm'
    }
    parameters {
        string(
            defaultValue: 'pmm-1.x',
            description: 'Tag/Branch for percona-images repository',
            name: 'GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
                git poll: true, branch: GIT_BRANCH, url: "https://github.com/Percona-Lab/percona-images.git"
                sh """
                    make clean
                    make deps
                """
            }
        }

        stage('Build Image') {
            steps {
                sh """
                    set -o pipefail
                    ~/bin/packer build -only amazon-ebs -color=false packer/pmm.json \
                        | tee build.log
                """
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
                slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}
