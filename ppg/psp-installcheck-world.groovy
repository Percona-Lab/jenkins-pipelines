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
            defaultValue: '18.4',
            description: 'Server PG version for test, including major and minor version, e.g 17.4, 17.3',
            name: 'VERSION'
        )
        string(
            defaultValue: '18.4.1',
            description: 'Server PG version for test, including major and minor version, e.g 17.6.1',
            name: 'PERCONA_SERVER_VERSION'
        )
        string(
            defaultValue: 'https://github.com/percona/postgres',
            description: 'PSP repo that we want to test, we could also use forked developer repo here.',
            name: 'PSP_REPO'
        )
        string(
            defaultValue: 'PSP_REL_18_STABLE',
            description: 'PSP repo version/branch/tag to use; e.g main, TDE_REL_17_STABLE',
            name: 'PSP_BRANCH'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository',
            name: 'TESTING_BRANCH'
        )
        choice(
            name: 'TESTSUITE',
            description: 'Testsuite to run',
            choices: [
                'check-server',
                'check-tde',
                'installcheck-world'
            ]
        )
        choice(
            name: 'IO_METHOD',
            description: 'io_method to use for the server (applicable to pg-18 and onwards only).',
            choices: [
                'sync',
                'worker',
                'io_uring'
            ]
        )
        string(
            defaultValue: 'https://github.com/percona/pg_tde.git',
            description: 'In case you want to test a different pg_tde repository than the default one.',
            name: 'TDE_REPO'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for pg_tde repository. Would only be used with check-tde, check-all and installcheck-world testsuites.',
            name: 'TDE_BRANCH'
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "psp/server_tests"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
        buildDiscarder(logRotator(
            numToKeepStr: '30',
            artifactNumToKeepStr: '30'
        ))
        retry(conditions: [agent()], count: 2)
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-psp-${env.VERSION}-${env.PLATFORM}-${env.IO_METHOD}-${env.TESTSUITE}"
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
                artifacts: 'psp/server_tests/artifacts/**/*.tar.gz',
                allowEmptyArchive: true
            )
        }
    }
}
