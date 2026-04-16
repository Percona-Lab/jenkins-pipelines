pipeline {
    agent any

    options {
        timestamps()
        skipDefaultCheckout()
    }

    environment {
        SOURCE_JOB = 'percona-server-8.0-pipeline-parallel-mtr'
        TARGET_DIR = 'upstream-artifacts'
    }

    stages {
        stage('Copy Artifacts') {
            steps {
                deleteDir()
                sh 'mkdir -p "${TARGET_DIR}"'

                copyArtifacts(
                    projectName: "${SOURCE_JOB}",
                    selector: permalink('lastUnstableBuild'),
                    filter: '**/*',
                    target: "${TARGET_DIR}",
                    flatten: false,
                    optional: false
                )

                sh '''
                    echo "Copied artifacts:"
                    ls -la "${TARGET_DIR}" || true
                '''
            }
        }

        stage('Verify') {
            steps {
                sh '''
                
                ls -la "${TARGET_DIR}"

                
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: "${TARGET_DIR}/**/*", allowEmptyArchive: true
        }
    }
}
