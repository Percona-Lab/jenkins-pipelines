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
        MOLECULE_DIR = "molecule/pdmysql/orchestrator";
    }
    parameters {
        choice(
            name: 'REPO',
            description: 'PDMYSQL will be installed from this repo',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.27',
            description: 'PDMYSQL version for test',
            name: 'VERSION'
        )
        string(
            defaultValue: 'master',
            description: 'Branch for package-testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'master',
            description: 'Tests will be run from branch of  https://github.com/percona/orchestrator',
            name: 'ORCHESTRATOR_TESTS_VERSION'
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
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "create", "ubuntu-bionic")
                }
            }
        }
        stage ('Run playbook for test') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "converge", "ubuntu-bionic")
                }
            }
        }
        stage ('Start testinfra tests') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "verify", "ubuntu-bionic")
                }
            }
        }
        stage ('Start Cleanup ') {
            steps {
                script {
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "cleanup", "ubuntu-bionic")
                }
            }
        }
    }
    post {
        always {
            script {
                if (env.DESTROY_ENV == "yes") {
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "destroy", "ubuntu-bionic")
                    junit "${MOLECULE_DIR}/report.xml"
                }
            }
        }
    }
}
