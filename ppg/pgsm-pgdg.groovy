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
        string(
            defaultValue: 'https://github.com/percona/pg_stat_monitor.git',
            description: 'PGSM repo that we want to test, we could also use forked developer repo here.',
            name: 'PGSM_REPO'
        )
        string(
            defaultValue: 'main',
            description: 'PGSM repo version/branch/tag to use; e.g main, 2.0.5',
            name: 'PGSM_BRANCH'
        )
        string(
            defaultValue: 'ppg-18.3',
            description: 'PGDG Server PG version for test, including major and minor version, e.g pg-16.2, pg-15.5',
            name: 'VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository',
            name: 'TESTING_BRANCH'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "pg_stat_monitor/pgsm_pgdg"
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
