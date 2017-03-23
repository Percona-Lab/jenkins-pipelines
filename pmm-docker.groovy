pipeline {
    environment {
        specName = 'Docker'
    }
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'centos7',
            description: '',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server',
            description: '',
            name: 'TAG')
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
                slackSend channel: '@mykola', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-server.git'
                sh """
                    export IMAGE="${TAG}:\$(date -u '+%Y%m%d%H%M')"
                    echo \$IMAGE> IMAGE
                """
                archiveArtifacts 'IMAGE'
            }
        }

        stage('Build Image') {
            steps {
                sh 'docker build --no-cache -t \$(cat IMAGE) .'
            }
        }

        stage('Upload') {
            steps {
                sh """
                    docker tag  \$(cat IMAGE) ${TAG}:dev-latest
                    docker push \$(cat IMAGE)
                    docker rmi  \$(cat IMAGE)
                """
            }
        }
    }

    post {
        success {
            script {
                def IMAGE = sh(returnStdout: true, script: "cat IMAGE").trim()
                slackSend channel: '@mykola', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
                slackSend channel: '@nailya.kutlubaeva', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend channel: '@mykola', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}
