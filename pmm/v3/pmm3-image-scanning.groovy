Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
This job helps run an image scan with Trivy
'''

pipeline {
    agent {
        label 'agent-amd64-ol9'
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
