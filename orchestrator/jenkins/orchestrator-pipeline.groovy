pipeline_timeout = 10

pipeline {
    parameters {
        string(
            name: 'GIT_REPO',
            defaultValue: 'https://github.com/percona/orchestrator',
            description: 'URL to the Orchestrator repository',
            trim: true)
        string(
            name: 'BRANCH',
            defaultValue: 'master',
            description: 'Tag/Branch for the Orchestrator repository',
            trim: true)
        string(
            name: 'PS_TARBALL',
            defaultValue: 'https://downloads.percona.com/downloads/Percona-Server-8.0/Percona-Server-8.0.26-16/binary/tarball/Percona-Server-8.0.26-16-Linux.x86_64.glibc2.12-minimal.tar.gz',
            description: 'PS tarball to be used for testing',
            trim: true)
        string(
            name: 'CI_ENV_GIT_REPO',
            defaultValue: 'https://github.com/percona/orchestrator-ci-env.git',
            description: 'URL to the Orchestrator CI repository',
            trim: true)
        string(
            name: 'CI_ENV_BRANCH',
            defaultValue: 'master',
            description: 'Tag/Branch for the Orchestrator CI repository',
            trim: true)
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Integration Tests') {
            agent { label 'docker-32gb' }
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                echo 'Run Orchestrator integration tests'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf sources
                    ./orchestrator/local/checkout
                    ./orchestrator/local/run-integration-tests
                '''
            }
        }

        stage('System Tests') {
            agent { label 'docker-32gb' }
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                echo 'Run Orchestrator system tests'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf sources
                    ./orchestrator/local/checkout
                    ./orchestrator/local/run-system-tests
                '''
            }
        }
    }
    post {
        always {
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
    }
}
