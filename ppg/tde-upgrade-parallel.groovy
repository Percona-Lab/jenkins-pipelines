library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(fromVersion, toVersion, upgradeType, tdeUpgrade) {
    def status = currentBuild.result == 'SUCCESS' ? '*SUCCESS*' : '*FAILURE*'
    def color  = currentBuild.result == 'SUCCESS' ? 'good'     : 'danger'
    def summary = """Job: ${env.JOB_NAME}
FROM_VERSION: ${fromVersion}
TO_VERSION: ${toVersion}
UPGRADE_TYPE: ${upgradeType}
TDE_UPGRADE: ${tdeUpgrade}
Status: ${status}
Build Report: ${env.BUILD_URL}"""
    slackSend color: color, message: summary, channel: '#postgresql-test'
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }

    parameters {
        string(
            name: 'FROM_VERSION',
            defaultValue: 'ppg-16.8',
            description: 'PostgreSQL version to install before the upgrade, e.g. ppg-16.8, ppg-17.4'
        )
        string(
            name: 'TO_VERSION',
            defaultValue: 'ppg-16.9',
            description: 'PostgreSQL version to upgrade to, e.g. ppg-16.9 (minor) or ppg-17.4 (major)'
        )
        choice(
            name: 'UPGRADE_TYPE',
            description: 'Type of upgrade to perform.',
            choices: [
                'minor',
                'major'
            ]
        )
        booleanParam(
            name: 'TDE_UPGRADE',
            defaultValue: false,
            description: 'Also upgrade pg_tde during the server upgrade. ' +
                         'Packages path: updates pg_tde package to latest. ' +
                         'Source path: rebuilds pg_tde from TO_TDE_BRANCH.'
        )
        booleanParam(
            name: 'INSTALL_FROM_PACKAGES',
            defaultValue: true,
            description: 'Install pg_tde from Percona packages. ' +
                         'When disabled, pg_tde is built from source using FROM_TDE_BRANCH / TO_TDE_BRANCH.'
        )
        choice(
            name: 'REPO',
            description: 'Percona repository channel. Only applicable when INSTALL_FROM_PACKAGES is enabled.',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            name: 'TDE_REPO',
            defaultValue: 'https://github.com/percona/pg_tde.git',
            description: 'pg_tde git repository. Only applicable when INSTALL_FROM_PACKAGES is disabled.'
        )
        string(
            name: 'FROM_TDE_BRANCH',
            defaultValue: 'main',
            description: 'pg_tde branch/tag to build for the initial (FROM) installation. ' +
                         'Only applicable when INSTALL_FROM_PACKAGES is disabled.'
        )
        string(
            name: 'TO_TDE_BRANCH',
            defaultValue: 'main',
            description: 'pg_tde branch/tag to build after the upgrade when TDE_UPGRADE is enabled. ' +
                         'Only applicable when INSTALL_FROM_PACKAGES is disabled and TDE_UPGRADE is enabled.'
        )
        string(
            name: 'TESTING_BRANCH',
            defaultValue: 'main',
            description: 'Branch of ppg-testing to check out.'
        )
    }

    environment {
        PATH        = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = 'pg_tde/upgrade'
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-upgrade-${env.FROM_VERSION}-to-${env.TO_VERSION}-${env.UPGRADE_TYPE}"
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
                sendSlackNotification(env.FROM_VERSION, env.TO_VERSION, env.UPGRADE_TYPE, env.TDE_UPGRADE)
            }
            archiveArtifacts(
                artifacts: 'pg_tde/upgrade/artifacts/**/*.tar.gz',
                allowEmptyArchive: true
            )
        }
    }
}
