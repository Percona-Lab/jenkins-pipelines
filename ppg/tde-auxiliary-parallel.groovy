library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(psp_repo, psp_branch, version, io_method, tde_branch) {
    if (currentBuild.result == "SUCCESS") {
        buildSummary = "Job: ${env.JOB_NAME}\nPSP_Repo: ${psp_repo}\nPSP_Branch: ${psp_branch}\nVersion: ${version}\nIO_Method: ${io_method}\nTDE_Branch: ${tde_branch}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
        slackSend color: "good", message: "${buildSummary}", channel: '#postgresql-test'
    } else {
        buildSummary = "Job: ${env.JOB_NAME}\nPSP_Repo: ${psp_repo}\nPSP_Branch: ${psp_branch}\nVersion: ${version}\nIO_Method: ${io_method}\nTDE_Branch: ${tde_branch}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
        slackSend color: "danger", message: "${buildSummary}", channel: '#postgresql-test'
    }
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        string(
            name: 'VERSION',
            defaultValue: 'ppg-18.1',
            description: 'Server PG version for test, including major and minor version, e.g 17.4, 17.3'
        )
        choice(
            name: 'IO_METHOD',
            description: 'io_method to use for the server (applicable to pg-18 and onwards only).',
            choices: ['worker', 'sync', 'io_uring']
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
            name: 'REPO',
            description: 'Repo for testing. ONLY applicable with INSTALL_FROM_PACKAGES enabled.',
            choices: [
                'testing',
                'experimental',
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
            defaultValue: 'release-2.1',
            description: 'TDE repo version/branch/tag to use; e.g main, release-2.1. NOT applicable with INSTALL_FROM_PACKAGES enabled.'
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
            defaultValue: 'pg_receivewal.sh,pg_tde_change_database_key_provider_vault_v2.sh',
            description: '''If SKIP_TESTCASE option is enabled, then testcase given here will be ignored. 
            Values should be comma separated. For example:
            pg_receivewal.sh,pg_tde_change_database_key_provider_vault_v2.sh'''
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "pg_tde/auxiliary"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-tde-auxiliary-${env.VERSION}"
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
        stage('Test') {
            steps {
                script {
                    moleculeParallelTestPPG(ppgOperatingSystemsALL(), env.MOLECULE_DIR)
                }
            }
        }
    }
    post {
        always {
            script {
                moleculeParallelPostDestroyPPG(ppgOperatingSystemsALL(), env.MOLECULE_DIR)
                sendSlackNotification(env.PSP_REPO, env.PSP_BRANCH, env.VERSION, env.IO_METHOD, env.TDE_BRANCH)
            }
        }
    }
}
