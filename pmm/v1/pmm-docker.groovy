library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    environment {
        specName = 'Docker'
    }
    agent {
        label 'min-centos-7-x64'
    }
    parameters {
        string(
            defaultValue: '1.x',
            description: 'Tag/Branch for pmm-server repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server',
            description: 'Docker Hub repository',
            name: 'TAG')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm-dashboards-package,pmm-manage-package,pmm-managed-package,pmm-qan-api-package,pmm-qan-app-package,pmm-server-package,pmm-server-packages,pmm-update-package', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                installDocker()
                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-server.git'
                sh """
                    export IMAGE="${TAG}:\$(date -u '+%Y%m%d%H%M')"
                    echo \$IMAGE> IMAGE
                """
            }
        }

        stage('Build Image') {
            steps {
                sh '''
                    sg docker -c "
                        docker pull centos:latest
                        docker build --squash --no-cache -t \$(cat IMAGE) .
                    "
                '''
                stash includes: 'IMAGE', name: 'IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }

        stage('Upload') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u "${USER}" -p "${PASS}"
                        "
                    """
                }
                sh """
                    sg docker -c "
                        docker tag  \$(cat IMAGE) ${TAG}:dev-latest
                        docker push \$(cat IMAGE)
                        docker push ${TAG}:dev-latest
                        docker rmi  \$(cat IMAGE)
                        docker rmi  ${TAG}:dev-latest
                    "
                """
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
                slackSend botUser: true, channel: '@nailya.kutlubaeva', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}
