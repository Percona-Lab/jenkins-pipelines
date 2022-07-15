Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
With this job you can run an image scanning with LaceWork (security and the best practice)
'''

pipeline {
    agent {
        label 'agent-amd64'
    }
    environment {
        LW_ACCOUNT_NAME=credentials('LW_ACCOUNT_NAME')
        LW_ACCESS_TOKEN=credentials('LW_ACCESS_TOKEN')
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server',
            description: 'Image for scanning',
            name: 'IMAGE')
        string(
            defaultValue: 'dev-latest',
            description: 'Tag for scanning',
            name: 'TAG')
    }
    stages {
        stage('Run scanning') {
            steps {
                sh "lw-scanner image evaluate ${IMAGE} ${TAG} --html --html-file report.html"
                archiveArtifacts 'report.html'
            }
        }
    }
}
