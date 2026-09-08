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
            name: 'OLD_VERSION',
            defaultValue: 'ppg-17.11',
            description: 'Old PostgreSQL version to build from source (major+minor), e.g. ppg-17.11. Used for build gating (io_method/Ubuntu 26 support) and artifact naming; the actual source checked out is controlled by OLD_PSP_BRANCH.'
        )
        string(
            name: 'NEW_VERSION',
            defaultValue: 'ppg-18.6',
            description: 'New PostgreSQL version to upgrade to, built from source (major+minor), e.g. ppg-18.6. Used for build gating (io_method/Ubuntu 26 support) and artifact naming; the actual source checked out is controlled by NEW_PSP_BRANCH.'
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
        string(
            name: 'PSP_REPO',
            defaultValue: 'https://github.com/percona/postgres',
            description: 'PSP repo to build both the old and new server from; we could also use a forked developer repo here.'
        )
        string(
            name: 'OLD_PSP_BRANCH',
            defaultValue: 'PSP_REL_17_STABLE',
            description: 'PSP repo version/branch/tag to build for the old (pre-upgrade) server, e.g. PSP_REL_17_STABLE.'
        )
        string(
            name: 'NEW_PSP_BRANCH',
            defaultValue: 'PSP_REL_18_STABLE',
            description: 'PSP repo version/branch/tag to build for the new (post-upgrade) server, e.g. PSP_REL_18_STABLE.'
        )
        string(
            name: 'TDE_REPO',
            defaultValue: 'https://github.com/percona/pg_tde.git',
            description: 'pg_tde repo to build against both the old and new server; we could also use a forked developer repo here.'
        )
        string(
            name: 'OLD_TDE_BRANCH',
            defaultValue: 'release-2.2.0',
            description: 'pg_tde repo version/branch/tag to build against the old server, e.g. main, release-2.1.'
        )
        string(
            name: 'NEW_TDE_BRANCH',
            defaultValue: 'release-2.2.0',
            description: 'pg_tde repo version/branch/tag to build against the new server, e.g. main, release-2.1.'
        )
        string(
            name: 'PERCONA_QA_REPO',
            defaultValue: 'https://github.com/Percona-QA/percona-qa.git',
            description: 'Repo that contains the percona-qa bash tests scripts.'
        )
        string(
            name: 'PERCONA_QA_BRANCH',
            defaultValue: 'master',
            description: 'PERCONA_QA_REPO branch to use.'
        )
        booleanParam(
            name: 'SKIP_TESTCASE',
            defaultValue: false,
            description: "Enable if want to skip some test cases."
        )
        string(
            name: 'TESTCASE_TO_SKIP',
            defaultValue: '',
            description: '''If SKIP_TESTCASE option is enabled, then testcase given here will be ignored.
            Values should be comma separated.'''
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "pg_tde/auxiliary_upgrade"
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-auxiliary-upgrade-${env.OLD_VERSION}-to-${env.NEW_VERSION}-${env.PLATFORM}-${env.IO_METHOD}"
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
                artifacts: 'pg_tde/auxiliary_upgrade/artifacts/**/*.tar.gz',
                allowEmptyArchive: true
            )
        }
    }
}
