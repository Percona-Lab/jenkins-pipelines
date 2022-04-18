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
                if ( "${VM}" == "ALL" ) {
                    runPython('do_remove_droplets')
                else {
                    runPython('do_remove_droplets', VM)
                }
            }
        }

    }
}
