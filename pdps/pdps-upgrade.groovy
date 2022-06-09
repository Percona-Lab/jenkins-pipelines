library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
    label 'min-centos-7-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
        MOLECULE_DIR = "molecule/pdmysql/pdps-minor-upgrade";
    }
    parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdmysqlOperatingSystems()
        )
        choice(
            name: 'FROM_REPO',
            description: 'Percona Server will be upgraded from this repository',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.28',
            description: 'Percona Server will be upgraded from this version',
            name: 'FROM_VERSION'
        )
        string(
            defaultValue: '8.0.29',
            description: 'Percona Server will be upgraded to this version',
            name: 'VERSION'
        )
        string(
            defaultValue: 'master',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: '2.3.2',
            description: 'Updated Proxysql version',
            name: 'PROXYSQL_VERSION'
        )
        string(
            defaultValue: '8.0.28',
            description: 'Updated PXB version',
            name: 'PXB_VERSION'
        )
        string(
            defaultValue: '3.3.1',
            description: 'Updated Percona Toolkit version',
            name: 'PT_VERSION'
        )
        string(
            defaultValue: '3.2.6',
            description: 'Updated Percona Orchestrator version',
            name: 'ORCHESTRATOR_VERSION'
        )
        choice(
            name: 'DESTROY_ENV',
            description: 'Destroy VM after tests',
            choices: [
                'yes',
                'no'
            ]
        )
    }
    options {
        withCredentials(moleculePdpsJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}-${env.MOLECULE_DIR}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/package-testing.git'
            }
        }
        stage ('Prepare') {
            steps {
                script {
                    installMolecule()
                }
            }
        }
        stage ('Create virtual machines') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "create", env.PLATFORM)
                }
            }
        }
        stage ('Run playbook for test') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "converge", env.PLATFORM)
                }
            }
        }
        stage ('Start testinfra tests') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "verify", env.PLATFORM)
                }
            }
        }
        stage ('Start Cleanup ') {
            steps {
                script {
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "cleanup", env.PLATFORM)
                }
            }
        }
    }
    post {
        always {
            script {
                if (env.DESTROY_ENV == "yes") {
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "destroy", env.PLATFORM)
                    junit "${MOLECULE_DIR}/report.xml"
                }
            }
        }
    }
}
