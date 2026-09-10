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
            description: 'For which platform (OS) you want to test?',
            choices: ppgOperatingSystemsALL()
        )
        string(
            name: 'VERSION',
            defaultValue: 'ppg-18.6',
            description: 'Server PG version for test, including major and minor version, e.g 17.4, 17.3'
        )
        choice(
            name: 'IO_METHOD',
            description: 'io_method to use for the server (applicable to pg-18 and onwards only).',
            choices: ['sync', 'worker', 'io_uring']
        )
        string(
            name: 'TESTING_BRANCH',
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository'
        )
        booleanParam(
            name: 'INSTALL_FROM_PACKAGES',
            description: "Enable if want to install the PSP and pg_tde from the packages, intead of building from sources."
        )
        choice(
            name: 'PACKAGE_SOURCE',
            description: 'Package source to install PSP/pg_tde from. ONLY applicable with INSTALL_FROM_PACKAGES enabled.',
            choices: [
                'percona-release',
                'obs'
            ]
        )
        choice(
            name: 'REPO',
            description: 'Repo for testing. ONLY applicable with INSTALL_FROM_PACKAGES enabled and PACKAGE_SOURCE=percona-release.',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        choice(
            name: 'OBS_CHANNEL',
            description: 'OBS channel for testing. ONLY applicable with INSTALL_FROM_PACKAGES enabled and PACKAGE_SOURCE=obs.',
            choices: [
                'devel',
                'staging',
                'release'
            ]
        )
        string(
            name: 'PSP_REPO',
            defaultValue: 'https://github.com/percona/postgres',
            description: 'PSP repo that we want to test, we could also use forked developer repo here. NOT applicable with INSTALL_FROM_PACKAGES enabled.'
        )
        string(
            name: 'PSP_BRANCH',
            defaultValue: 'PSP_REL_18_STABLE',
            description: 'PSP repo version/branch/tag to use; e.g main, TDE_REL_17_STABLE. NOT applicable with INSTALL_FROM_PACKAGES enabled.'
        )
        string(
            name: 'TDE_REPO',
            defaultValue: 'https://github.com/percona/pg_tde.git',
            description: 'pg_tde repo that we want to test, we could also use forked developer repo here. NOT applicable with INSTALL_FROM_PACKAGES enabled.'
        )
        string(
            name: 'TDE_BRANCH',
            defaultValue: 'release-2.2.0',
            description: 'TDE repo version/branch/tag to use; e.g main, release-2.1. NOT applicable with INSTALL_FROM_PACKAGES enabled.'
        )
        choice(
            name: 'TEST_SUITE',
            description: 'Which pytest suite to run.',
            choices: [
                'Sanity',
                'All'
            ]
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "pg_tde/core"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
        buildDiscarder(logRotator(
            daysToKeepStr: '30',
            numToKeepStr: '100',
            artifactNumToKeepStr: '10'
        ))
        retry(conditions: [agent()], count: 2)
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-core-${env.VERSION}-${env.PLATFORM}-${env.IO_METHOD}"
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
            archiveArtifacts(
                artifacts: 'pg_tde/core/artifacts/**/*.tar.gz',
                allowEmptyArchive: true
            )
        }
    }
}
