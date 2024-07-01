library changelog: false, identifier: "lib@telem_ph1", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/eleo007/jenkins-pipelines.git'
])

List operating_systems = []
operating_systems = pdpsOperatingSystems() + ['rocky-8', 'rocky-9']

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
            defaultValue: 'phase-0.1',
            description: 'Telemetry Agent version',
            name: 'VERSION'
        )
        string(
            defaultValue: 'f193432',
            description: 'Telemetry Agent revision',
            name: 'REVISION'
        )
        choice(
            name: 'SCENARIO',
            description: 'Scenario for test',
            choices: ['telemetry-ps',]
        )
        string(
            defaultValue: 'telemetry_ph1',
            description: 'Branch for package-testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'eleo007',
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}-${env.SCENARIO}"
                    currentBuild.description = "${env.VERSION}-${env.TESTING_BRANCH}"
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

