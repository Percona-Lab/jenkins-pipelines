library changelog: false, identifier: 'lib@PMM-6800-add-ova-staging', retriever: modernSCM([
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
            description: 'Name or IP of VM to stop. ALL means stop all vm older 24 hours',
            name: 'VM')
    }
    triggers {
        cron('@daily')
    }
    stages {
        stage('Run ') {
            steps {
                script {
                    withCredentials([
                            string(credentialsId: '82c0e9e0-75b5-40ca-8514-86eca3a028e0', variable: 'DIGITALOCEAN_ACCESS_TOKEN')
                        ]) {
                        if ( "${VM}" == "ALL" ) {
                            runPython('do_remove_droplets')
                        } else {
                            runPython('do_remove_droplets', "-o ${VM}")
                        }
                    }
                }
            }
        }

    }
}
