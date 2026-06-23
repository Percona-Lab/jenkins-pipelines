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
            description: 'Operating system to run the upgrade test on.',
            choices: ppgOperatingSystemsALL()
        )
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
            defaultValue: true,
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
            name: 'FROM_REPO',
            description: 'Percona repository channel for the FROM version. Only applicable when INSTALL_FROM_PACKAGES is enabled.',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Percona repository channel for the TO version. Only applicable when INSTALL_FROM_PACKAGES is enabled.',
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
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy the VM after the test run. Disable to keep the VM for debugging.'
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-upgrade-${env.FROM_VERSION}-to-${env.TO_VERSION}-${env.UPGRADE_TYPE}-${env.PLATFORM}"
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
        stage('Create virtual machine') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, 'create', env.PLATFORM)
                }
            }
        }
        stage('Run upgrade playbook') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, 'converge', env.PLATFORM)
                }
            }
        }
        stage('Run verification') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, 'verify', env.PLATFORM)
                }
            }
        }
        stage('Cleanup') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, 'cleanup', env.PLATFORM)
                }
            }
        }
    }

    post {
        always {
            script {
                if (params.DESTROY_ENV) {
                    echo "DESTROY_ENV is true — destroying VM."
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, 'destroy', env.PLATFORM)
                } else {
                    echo "DESTROY_ENV is false — VM left running for debugging."
                }
            }
            archiveArtifacts(
                artifacts: 'pg_tde/upgrade/artifacts/**/*.tar.gz',
                allowEmptyArchive: true
            )
        }
    }
}
