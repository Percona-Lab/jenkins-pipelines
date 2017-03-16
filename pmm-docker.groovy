pipeline {
    agent { label 'docker' }
    parameters {
        string (
            defaultValue: 'centos7',
            description: '',
            name : 'GIT_BRANCH')
        string (
            defaultValue: 'perconalab/pmm-server',
            description: '',
            name : 'TAG')
        string (
            defaultValue: '1.1.2',
            description: '',
            name : 'VERSION')
    }

    triggers {
        upstream upstreamProjects: 'pmm-dashboards-package,pmm-manage-package,pmm-qan-api-package,pmm-qan-app-package,pmm-server-package,pmm-server-packages,pmm-update-package', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage ('Prepare') {
            steps {
                slackSend channel: '@mykola', color: '#FFFF00', message: "[Docker]: build started - ${env.BUILD_URL}"
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-server.git'
                sh """
                    export FULL_TAG="${TAG}:${VERSION}-dev\$(date -u '+%Y%m%d%H%M')"
                    echo \$FULL_TAG > FULL_TAG
                """
                archiveArtifacts 'FULL_TAG'
            }
        }

        stage ('Build container') {
            steps {
                sh 'docker build --no-cache -t \$(cat FULL_TAG) .'
            }
        }

        stage ('Push container') {
            steps {
                sh '''
                    docker push \$(cat FULL_TAG)
                    docker rmi  \$(cat FULL_TAG)
                '''
            }
        }
    }

    post {
        success {
            script {
                def FULL_TAG = sh(returnStdout: true, script: "cat FULL_TAG").trim()
                slackSend channel: '@mykola', color: '#00FF00', message: "[Docker]: build finished - ${FULL_TAG}"
            }
        }
        failure {
            slackSend channel: '@mykola', color: '#FF0000', message: "[Docker]: build failed - ${TAG}"
        }
    }
}
