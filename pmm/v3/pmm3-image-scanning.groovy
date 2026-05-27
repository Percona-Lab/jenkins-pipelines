Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
This job helps run an image scan with Trivy
'''

pipeline {
    agent {
        label params.USE_ONDEMAND ? 'agent-amd64-ondemand' : 'agent-amd64'
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
        booleanParam(
            defaultValue: false,
            description: 'Use on-demand instances instead of spot (for RC/Release builds)',
            name: 'USE_ONDEMAND'
        )
    }
    stages {
        stage('Install Trivy') {
            steps {
                script {
                    sh '''
                        # https://trivy.dev/docs/latest/getting-started/installation/#rhelcentos-official
                        sudo tee /etc/yum.repos.d/trivy.repo <<'EOF'
[trivy]
name=Trivy repository
baseurl=https://aquasecurity.github.io/trivy-repo/rpm/releases/$basearch/
gpgcheck=1
enabled=1
gpgkey=https://aquasecurity.github.io/trivy-repo/rpm/public.key
EOF

                        sudo dnf install -y trivy-0.70.0

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
                        trivy image --severity HIGH,CRITICAL --format table -o trivy-server-report.txt ${params.PMM_SERVER_IMAGE}
                        trivy image --severity HIGH,CRITICAL --format template --template "@contrib/html.tpl" -o trivy-server-report.html ${params.PMM_SERVER_IMAGE}
                    """
                    archiveArtifacts artifacts: 'trivy-server-report.*', allowEmptyArchive: true
                }
            }
        }
        stage('Scan PMM Client') {
            steps {
                script {
                    sh """
                        trivy image --severity HIGH,CRITICAL --format table -o trivy-client-report.txt ${params.PMM_CLIENT_IMAGE}
                        trivy image --severity HIGH,CRITICAL --format template --template "@contrib/html.tpl" -o trivy-client-report.html ${params.PMM_CLIENT_IMAGE}
                    """
                    archiveArtifacts artifacts: 'trivy-client-report.*', allowEmptyArchive: true
                }
            }
        }
    }
}
