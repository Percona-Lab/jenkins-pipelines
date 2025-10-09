JENKINS_SCRIPTS_BRANCH = 'master'
JENKINS_SCRIPTS_REPO = 'https://github.com/Percona-Lab/jenkins-pipelines'
LABEL = 'docker-32gb'
MICRO_LABEL = 'micro-amazon'

if (params.CLOUD == 'Hetzner') {
    LABEL = 'docker-x64'
    MICRO_LABEL = 'launcher-x64'
} else if (params.CLOUD == 'AWS') {
    LABEL = 'docker-32gb'
    MICRO_LABEL = 'micro-amazon'
} else {
    // by default fallback to AWS
    LABEL = 'docker-32gb'
    MICRO_LABEL = 'micro-amazon'
}

pipeline {
    agent { label MICRO_LABEL }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Integration Tests') {
            agent { label LABEL }
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
            agent { label LABEL }
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
