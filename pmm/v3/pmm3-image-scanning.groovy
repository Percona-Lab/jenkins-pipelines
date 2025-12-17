Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
This job helps run an image scan with Snyk and Trivy
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
        stage('Install Snyk and Trivy') {
            steps {
                script {
                    sh '''
                        curl -sL https://static.snyk.io/cli/latest/snyk-linux -o snyk
                        chmod +x snyk
                        npm install snyk-to-html

                        sudo tee /etc/yum.repos.d/trivy.repo <<'EOF'
[trivy]
name=Trivy repository
baseurl=https://aquasecurity.github.io/trivy-repo/rpm/releases/$releasever/$basearch/
gpgcheck=0
enabled=1
EOF
                        sudo dnf install -y trivy

                        # Download HTML template for Trivy
                        mkdir -p contrib
                        curl -sL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/html.tpl -o contrib/html.tpl
                    '''
                }
            }
        }
        stage('Run Scans in Parallel') {
            parallel {
                stage('Snyk Scan') {
                    steps {
                        script {
                            sh(
                                script: "./snyk container test --severity-threshold=high --json-file-output=snyk-report.json ${params.IMAGE}:${params.TAG}",
                                returnStatus: true
                            )
                            sh 'snyk-to-html -i snyk-report.json -o snyk-report.html'
                            archiveArtifacts artifacts: 'snyk-report.*', allowEmptyArchive: true
                        }
                    }
                }
                stage('Trivy Scan') {
                    steps {
                        script {
                            sh """
                                trivy image --severity HIGH,CRITICAL --format table -o trivy-report.txt ${params.IMAGE}:${params.TAG}
                                trivy image --severity HIGH,CRITICAL --format template --template "@contrib/html.tpl" -o trivy-report.html ${params.IMAGE}:${params.TAG}
                            """
                            archiveArtifacts artifacts: 'trivy-report.*', allowEmptyArchive: true
                        }
                    }
                }
            }
        }
    }
}
