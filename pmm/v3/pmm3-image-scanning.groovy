Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
This job helps run an image scan with Trivy
'''

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

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
                    installTrivy(htmlTpl: true)
                }
            }
        }
        stage('Scan PMM Server') {
            steps {
                script {
                    sh """
                        trivy image --platform linux/amd64 --severity HIGH,CRITICAL --format table -o trivy-server-report-amd64.txt ${params.PMM_SERVER_IMAGE}
                        trivy image --platform linux/amd64 --severity HIGH,CRITICAL --format template --template "@html.tpl" -o trivy-server-report-amd64.html ${params.PMM_SERVER_IMAGE}
                        trivy image --platform linux/arm64 --severity HIGH,CRITICAL --format table -o trivy-server-report-arm64.txt ${params.PMM_SERVER_IMAGE}
                        trivy image --platform linux/arm64 --severity HIGH,CRITICAL --format template --template "@html.tpl" -o trivy-server-report-arm64.html ${params.PMM_SERVER_IMAGE}
                    """
                    archiveArtifacts artifacts: 'trivy-server-report-*.*', allowEmptyArchive: true
                }
            }
        }
        stage('Scan PMM Client') {
            steps {
                script {
                    sh """
                        trivy image --platform linux/amd64 --severity HIGH,CRITICAL --format table -o trivy-client-report-amd64.txt ${params.PMM_CLIENT_IMAGE}
                        trivy image --platform linux/amd64 --severity HIGH,CRITICAL --format template --template "@html.tpl" -o trivy-client-report-amd64.html ${params.PMM_CLIENT_IMAGE}
                        trivy image --platform linux/arm64 --severity HIGH,CRITICAL --format table -o trivy-client-report-arm64.txt ${params.PMM_CLIENT_IMAGE}
                        trivy image --platform linux/arm64 --severity HIGH,CRITICAL --format template --template "@html.tpl" -o trivy-client-report-arm64.html ${params.PMM_CLIENT_IMAGE}
                    """
                    archiveArtifacts artifacts: 'trivy-client-report-*.*', allowEmptyArchive: true
                }
            }
        }
    }
}
