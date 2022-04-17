library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'ALL',
            description: 'Name or IP of VM to stop',
            name: 'VM_NAME')
    }

    stages {
        stage('Ask input') {
            steps {
                sh "python ${getResourceDirPath()}/pmm/test.py"
            }
        }

    }
}
