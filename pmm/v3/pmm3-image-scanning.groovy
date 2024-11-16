Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
This job helps run an image scan with Snyk (security and best practice)
'''

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    environment {
        PATH = "${WORKSPACE}/node_modules/.bin:$PATH" // Add local npm bin to PATH
        SNYK_TOKEN=credentials('SNYK_TOKEN')
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server',
            description: 'Image to scan',
            name: 'IMAGE')
        string(
            defaultValue: '3-dev-latest',
            description: 'Image tag',
            name: 'TAG')
    }
    stages {
        stage('Install Snyk CLI') {
            steps {
                script {
                    sh '''
                        curl -sL https://static.snyk.io/cli/latest/snyk-linux -o snyk
                        chmod +x snyk
                        npm install snyk-to-html
                    '''
                }
            }
        }
        stage('Scan Image') {
            steps {
                script {
                    def status = sh(
                        script: "./snyk container test --severity-threshold=high --json-file-output=report.json ${params.IMAGE}:${params.TAG}",
                        returnStatus: true
                    )
                    sh 'snyk-to-html -i report.json -o report.html'
                    archiveArtifacts artifacts: 'report.html', allowEmptyArchive: true
                }
            }
        }
    }
}
