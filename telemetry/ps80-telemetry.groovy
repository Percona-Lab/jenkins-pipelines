library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

List operating_systems = []
operating_systems = ps80telemOperatingSystems() + ['rocky-8', 'rocky-9']

pipeline {
    agent {
    label 'min-bookworm-x64'
    }
    environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/telemetry/${SCENARIO}";
    }
    parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: operating_systems
        )
        string(
            defaultValue: '1.0.1-2',
            description: 'Telemetry Agent version',
            name: 'VERSION'
        )
        string(
            defaultValue: '5da6a8b',
            description: 'Telemetry Agent revision',
            name: 'REVISION'
        )
        choice(
            name: 'TA_UPDATE',
            description: 'Set yes for update of Telemetry Agent',
            choices: [
                'yes',
                'no'
            ]
        )
        choice(
            name: 'TA_INSTALL_REPO',
            description: 'Select repo for Telemetry Agent installation',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        choice(
            name: 'PS_INSTALL_REPO',
            description: 'Select repo for Percona Server installation',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        choice(
            name: 'SCENARIO',
            description: 'Scenario for test',
            choices: ['telemetry-ps',]
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
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}-TA_UPDATE_${env.TA_UPDATE}"
                    currentBuild.description = "${env.VERSION}-TA_REPO_${env.TA_INSTALL_REPO}-PS_REPO_${env.PS_INSTALL_REPO}"
                }
            }
        }
        stage('Check version param and checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
            }
        }
        stage ('Prepare') {
            steps {
                script {
                    installMoleculeBookwormold()
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

