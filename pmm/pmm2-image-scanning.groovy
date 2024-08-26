Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
This job helps run an image scan with LaceWork (security and best practice)
'''

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    environment {
        LW_ACCOUNT_NAME=credentials('LW_ACCOUNT_NAME')
        LW_ACCESS_TOKEN=credentials('LW_ACCESS_TOKEN')
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server',
            description: 'Image to scan',
            name: 'IMAGE')
        string(
            defaultValue: 'dev-latest',
            description: 'Image tag',
            name: 'TAG')
    }
    stages {
        stage('Scan docker image') {
            steps {
                sh "lw-scanner image evaluate ${IMAGE} ${TAG} --html --html-file report.html --data-directory ."
                archiveArtifacts 'report.html'
                archiveArtifacts 'evaluations/**/**/evaluation_*.json'
            }
        }
    }
}
