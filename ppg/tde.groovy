library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'min-ol-9-x64'
    }

    parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: ppgOperatingSystemsALL()
        )
        choice(
            name: 'REPO',
            description: 'Packages Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: 'https://github.com/percona/pg_tde.git',
            description: 'pg_tde repo that we want to test, we could also use forked developer repo here.',
            name: 'TDE_REPO'
        )
        string(
            defaultValue: 'release-2.1',
            description: 'TDE repo version/branch/tag to use; e.g main, release-2.1',
            name: 'TDE_BRANCH'
        )
        string(
            defaultValue: 'ppg-18.1',
            description: 'Server PG version for test, including major and minor version, e.g ppg-17.4, ppg-17.3',
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
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository',
            name: 'TESTING_BRANCH'
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (ppg-17) repo instead of ppg-17.4"
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "pg_tde/tde"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}-${env.PLATFORM}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/ppg-testing.git'
            }
        }
        stage('Prepare') {
            steps {
                script {
                    installMoleculePython39()
                }
            }
        }
        stage('Create virtual machines') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "create", env.PLATFORM)
                }
            }
        }
        stage('Run playbook for test') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "converge", env.PLATFORM)
                }
            }
        }
        stage('Start testinfra tests') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "verify", env.PLATFORM)
                }
            }
        }
        stage('Start Cleanup ') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "cleanup", env.PLATFORM)
                }
            }
        }
    }
    post {
        always {
            script {
                if (params.DESTROY_ENV) {
                    echo "DESTROY_ENV is true. Cleaning up resources..."
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "destroy", env.PLATFORM)
                } else {
                    echo "DESTROY_ENV is false. Leaving VMs active for debugging."
                }
            }
        }
    }
}
