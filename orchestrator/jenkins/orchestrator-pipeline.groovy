def JENKINS_SCRIPTS_BRANCH = 'master'
def JENKINS_SCRIPTS_REPO = 'https://github.com/Percona-Lab/jenkins-pipelines'

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
            defaultValue: 'https://downloads.percona.com/downloads/Percona-Server-8.0/Percona-Server-8.0.30-22/binary/tarball/Percona-Server-8.0.30-22-Linux.x86_64.glibc2.17-minimal.tar.gz',
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
                git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
                echo 'Run Orchestrator integration tests'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf sources
                    ./orchestrator/local/checkout
                    export RESULT_FILE=${WORKSPACE}/integration-tests.result
                    ./orchestrator/local/run-integration-tests
                '''
                script {
                    env.INTEGRATION_TESTS_RESULT = sh(returnStdout: true, script: "cat ${WORKSPACE}/integration-tests.result").trim()
                    echo "INTEGRATION_TESTS_RESULT: ${env.INTEGRATION_TESTS_RESULT}"
                    if (env.INTEGRATION_TESTS_RESULT == 'UNSTABLE' || env.INTEGRATION_TESTS_RESULT == 'FAILURE') {
                        catchError(stageResult: env.INTEGRATION_TESTS_RESULT, buildResult: null) {
                            error 'Tests unstable or failed'
                        }                    
                    }
                }
            }
        }

        stage('System Tests') {
            agent { label 'docker-32gb' }
            steps {
                git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
                echo 'Run Orchestrator system tests'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf sources
                    ./orchestrator/local/checkout
                    export RESULT_FILE=${WORKSPACE}/system-tests.result
                    ./orchestrator/local/run-system-tests
                '''
                script {
                    env.SYSTEM_TESTS_RESULT = sh(returnStdout: true, script: "cat ${WORKSPACE}/system-tests.result").trim()
                    echo "SYSTEM_TESTS_RESULT: ${env.SYSTEM_TESTS_RESULT}"
                    if (env.SYSTEM_TESTS_RESULT == 'UNSTABLE' || env.SYSTEM_TESTS_RESULT == 'FAILURE') {
                        catchError(stageResult: env.SYSTEM_TESTS_RESULT, buildResult: null) {
                            error 'Tests unstable or failed'
                        }                    
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                // According to the documentation, build result can only get worse, so the following will work
                if (env.INTEGRATION_TESTS_RESULT == 'UNSTABLE' || env.SYSTEM_TESTS_RESULT == 'UNSTABLE') {                
                    currentBuild.result = 'UNSTABLE'
                }
                if (env.INTEGRATION_TESTS_RESULT == 'FAILURE' || env.SYSTEM_TESTS_RESULT == 'FAILURE') {                
                    currentBuild.result = 'FAILURE'
                }
            }
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
    }
}
