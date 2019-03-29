pipeline {
    agent {
        label 'sphinx-1.4'
    }
    parameters {
        string(
            defaultValue: 'git@github.com:percona/pmm-doc',
            description: 'Repository name for build',
            name: 'REPO_NAME')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for build',
            name: 'BRANCH_NAME')
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '14', numToKeepStr: '5'))
        timestamps()
    }
    triggers {
        pollSCM 'H/15 * * * *'
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: BRANCH_NAME, url: 'https://github.com/percona/pmm.git'
            }
        }
        stage('Publish') {
            steps {
                sh """
                    echo BRANCH = ${BRANCH}
                    echo "Building in: " `pwd`
                    make clean latex && make latexpdf
                """
                stash includes: 'doc/build/latex/*.pdf', name: 'PDF'
                archiveArtifacts 'doc/build/latex/*.pdf'
            }
        }
    }
    post {
        always {
            // stop staging
            script {
                publishers {
                    warnings(['sphinx'], ['sphinx': '**/*.log']) {}
                }
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished"
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}"
                }
            }
            deleteDir()
        }
    }
}