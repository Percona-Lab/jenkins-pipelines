library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])


pipeline {
    agent {
    label 'min-bookworm-x64'
    }
    environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/pdmysql/${SCENARIO}";
    }
    parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdpsOperatingSystems()
        )
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.37-29',
            description: 'Percona Server version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
            name: 'VERSION'
        )
        string(
            defaultValue: '',
            description: 'Percona Server revision for test. Empty by default (not checked).',
            name: 'PS_REVISION'
        )
        string(
            defaultValue: '2.6.3',
            description: 'Proxysql version for test',
            name: 'PROXYSQL_VERSION'
        )
        string(
            defaultValue: '8.0.35-31',
            description: 'PXB version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-25 OR 8.0.32-25.1',
            name: 'PXB_VERSION'
        )
        string(
            defaultValue: '3.6.0',
            description: 'Percona toolkit version for test',
            name: 'PT_VERSION'
        )
        string(
            defaultValue: '3.2.6-13',
            description: 'Percona Orchestrator version for test',
            name: 'ORCHESTRATOR_VERSION'
        )
        string(
            defaultValue: '',
            description: 'Orchestrator revision for version from https://github.com/percona/orchestrator . Empty by default (not checked).',
            name: 'ORCHESTRATOR_REVISION'
        )
        choice(
            name: 'SCENARIO',
            description: 'Scenario for test',
            choices: pdpsScenarios()
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
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (pdps-8.0) repo instead of pdps-8.0.XX"
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}-${env.SCENARIO}"
                    currentBuild.description = "${env.VERSION}-${env.REPO}-${env.TESTING_BRANCH}-${env.MAJOR_REPO}"
                }
            }
        }
        stage('Check version param and checkout') {
            steps {
                deleteDir()
                checkOrchVersionParam()
                git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
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
