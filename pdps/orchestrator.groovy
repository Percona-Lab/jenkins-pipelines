library changelog: false, identifier: "lib@fix-orch", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/kaushikpuneet07/jenkins-pipelines.git'
])


pipeline {
    agent {
    label 'min-bookworm-x64'
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
            defaultValue: '8.0.33',
            description: 'PDMYSQL version for test',
            name: 'VERSION'
        )
        string(
            defaultValue: 'master',
            description: 'Branch for package-testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'Percona-QA',
            description: 'Git account for package-testing repository',
            name: 'TESTING_GIT_ACCOUNT'
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
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${REPO}-${VERSION}"
                    currentBuild.description = "${TESTING_BRANCH}-${TESTING_GIT_ACCOUNT}-${DESTROY_ENV}"
                }
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git'
            }
        }
        stage ('Prepare') {
            steps {
                script {
                    installMoleculeBookworm()
                }
            }
        }
        stage ('Create virtual machines') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "create", "ubuntu-jammy")
                }
            }
        }
        stage ('Run playbook for test') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "converge", "ubuntu-jammy")
                }
            }
        }
        stage ('Start testinfra tests') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "verify", "ubuntu-jammy")
                }
            }
        }
        stage ('Start Cleanup ') {
            steps {
                script {
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "cleanup", "ubuntu-jammy")
                }
            }
        }
    }
    post {
        always {
            script {
                if (env.DESTROY_ENV == "yes") {
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "destroy", "ubuntu-jammy")
                    junit "${MOLECULE_DIR}/report.xml"
                }
            }
        }
    }
}
