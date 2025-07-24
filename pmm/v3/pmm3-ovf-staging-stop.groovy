library changelog: false, identifier: 'lib@PMM-7-fix-ovf-staging-stop', retriever: modernSCM([
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
            description: 'Name or IP of VM to stop. ALL means stop all VMs older than 24 hours',
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
                            string(credentialsId: 'f5415992-e274-45c2-9eb9-59f9e8b90f43', variable: 'DIGITALOCEAN_ACCESS_TOKEN')
                        ]) {
                        if ( params.VM == "ALL" ) {
                            runPython('do_remove_droplets', python="/home/ec2-user/venv/bin/python")
                        } else {
                            runPython('do_remove_droplets', "-o ${VM}", python="/home/ec2-user/venv/bin/python")
                        }
                    }
                }
            }
        }

    }
}
