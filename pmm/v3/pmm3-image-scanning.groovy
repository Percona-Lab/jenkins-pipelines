Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
This job helps run an image scan with Trivy
'''

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-client:3-dev-latest',
            description: 'PMM Client image with tag to scan',
            name: 'PMM_CLIENT_IMAGE')
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server image with tag to scan',
            name: 'PMM_SERVER_IMAGE')
    }
    stages {
        stage('Install Trivy') {
            steps {
                script {
                    sh '''
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
        stage('Scan PMM Server') {
            steps {
                script {
                    sh """
                        trivy image --severity HIGH,CRITICAL --format json -o trivy-server-report.json ${params.PMM_SERVER_IMAGE}
                        trivy convert --format table -o trivy-server-report.txt trivy-server-report.json
                        trivy convert --format template --template "@contrib/html.tpl" -o trivy-server-report.html trivy-server-report.json
                    """
                    archiveArtifacts artifacts: 'trivy-server-report.*', allowEmptyArchive: true
                }
            }
        }
        stage('Scan PMM Client') {
            steps {
                script {
                    sh """
                        trivy image --severity HIGH,CRITICAL --format json -o trivy-client-report.json ${params.PMM_CLIENT_IMAGE}
                        trivy convert --format table -o trivy-client-report.txt trivy-client-report.json
                        trivy convert --format template --template "@contrib/html.tpl" -o trivy-client-report.html trivy-client-report.json
                    """
                    archiveArtifacts artifacts: 'trivy-client-report.*', allowEmptyArchive: true
                }
            }
        }
    }
}
