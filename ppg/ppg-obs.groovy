library changelog: false, identifier: "lib@obs", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def platformsList = []
def defaultPlatforms = ppgOperatingSystemsALL().join('\n')

pipeline {
    agent {
        label 'min-ol-9-x64'
    }

    parameters {
        text(
            name: 'PLATFORMS',
            description: 'Platforms (OSes) to test, one per line. Defaults to the full ppgOperatingSystemsALL() list — trim to target a subset.',
            defaultValue: defaultPlatforms
        )
        string(
            name: 'REPO',
            description: 'Repo for testing',
            defaultValue: 'testing'
        )
        string(
            defaultValue: 'ppg-18.3',
            description: 'PG version for test',
            name: 'VERSION'
        )
        choice(
            name: 'IO_METHOD',
            description: 'io_method to use for the server (applicable to pg-18 and onwards only).',
            choices: [
                'worker',
                'sync',
                'io_uring'
            ]
        )
        string(
            defaultValue: 'pg-18',
            description: 'PG scenario for test (free-form; must match a directory under ppg/ in Percona-QA/ppg-testing)',
            name: 'SCENARIO'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (ppg-14) repo instead of ppg-17.0"
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "ppg/${SCENARIO}"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
    }
    stages {
        stage('Set platforms') {
            steps {
                script {
                    platformsList = params.PLATFORMS
                        .split('\n')
                        .collect { it.trim() }
                        .findAll { it }
                    if (platformsList.isEmpty()) {
                        error('PLATFORMS parameter is empty — provide at least one OS name.')
                    }
                    echo "Targeting ${platformsList.size()} platform(s): ${platformsList.join(', ')}"
                }
            }
        }
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${platformsList.size()}os"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/rjd15372/ppg-testing.git'
            }
        }
        stage('Prepare') {
            steps {
                script {
                    installMoleculePython39()
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    moleculeParallelTestPPG(platformsList, env.MOLECULE_DIR)
                }
            }
        }
    }
    post {
        always {
            script {
                if (params.DESTROY_ENV) {
                    echo "DESTROY_ENV is true. Cleaning up ${platformsList.size()} platform(s) in parallel..."
                    moleculeParallelPostDestroyPPG(platformsList, env.MOLECULE_DIR)
                } else {
                    echo "DESTROY_ENV is false. Leaving VMs active for debugging."
                }
            }
        }
    }
}
