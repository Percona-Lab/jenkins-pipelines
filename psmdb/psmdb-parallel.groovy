library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb/psmdb"

pipeline {
    agent {
        label 'min-centos-7-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
    }
    parameters {
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        string(
            defaultValue: '4.4.8',
            description: 'PSMDB Version for tests',
            name: 'PSMDB_VERSION')
        choice( 
            name: 'ENABLE_TOOLKIT',
            description: 'Enable or disable percona toolkit check',
            choices: [
                'false',
                'true'
            ]
        )
        choice(
            name: 'GATED_BUILD',
            description: 'Test private repo?',
            choices: [
                'false',
                'true'
            ]
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
            }
        }
        stage ('Prepare') {
            steps {
                script {
                    installMolecule()
                }
            }
        }
        stage('Test') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'PSMDB_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                    script {
                        moleculeParallelTest(pdmdbOperatingSystems(PSMDB_VERSION), moleculeDir)
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: package tests for PSMDB ${PSMDB_VERSION}, repo ${REPO}, private repo - ${GATED_BUILD} finished succesfully - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: package tests for PSMDB ${PSMDB_VERSION}, repo ${REPO}, private repo - ${GATED_BUILD} failed - [${BUILD_URL}]")
        }
        always {
            script {
                moleculeParallelPostDestroy(pdmdbOperatingSystems(PSMDB_VERSION), moleculeDir)
            }
        }
    }
}
