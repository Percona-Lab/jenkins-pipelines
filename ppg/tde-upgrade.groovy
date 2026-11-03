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
            defaultValue: 'ppg-17.10',
            description: 'PostgreSQL version to install before the upgrade, e.g. ppg-17.10, ppg-18.3'
        )
        string(
            name: 'TO_VERSION',
            defaultValue: 'ppg-18.4',
            description: 'PostgreSQL version to upgrade to, e.g. ppg-18.4 (minor) or ppg-19.0 (major)'
        )
        choice(
            name: 'UPGRADE_TYPE',
            description: 'Type of upgrade to perform. ' +
                         'server_minor: PostgreSQL minor-version update within the same major (e.g. ppg-17.10 -> ppg-17.11), via package update + restart. ' +
                         'server_major: PostgreSQL major-version upgrade (e.g. ppg-17.x -> ppg-18.x), via pg_tde_upgrade --link. ' +
                         'tde_only: keep FROM_VERSION == TO_VERSION and upgrade only pg_tde. ' +
                         'For server_minor/server_major, whether pg_tde is also upgraded is controlled by TDE_UPGRADE.',
            choices: [
                'server_minor',
                'server_major',
                'tde_only'
            ]
        )
        booleanParam(
            name: 'TDE_UPGRADE',
            defaultValue: true,
            description: 'Also upgrade pg_tde during a server_minor/server_major upgrade. ' +
                         'Packages path: updates pg_tde package to latest. ' +
                         'Source path: rebuilds pg_tde from TO_TDE_BRANCH. ' +
                         'Ignored when UPGRADE_TYPE=tde_only (that mode always upgrades pg_tde).'
        )
        booleanParam(
            name: 'INSTALL_FROM_PACKAGES',
            defaultValue: true,
            description: 'Controls how pg_tde is installed; the PostgreSQL server is always installed from Percona packages regardless of this setting. ' +
                         'When enabled, pg_tde is installed from Percona packages (FROM_REPO/TO_REPO channel). ' +
                         'When disabled, pg_tde is built from source using FROM_TDE_BRANCH / TO_TDE_BRANCH.'
        )
        choice(
            name: 'FROM_REPO',
            description: 'Percona repository channel for the FROM version. Always used for the PostgreSQL server packages; also used for pg_tde packages when INSTALL_FROM_PACKAGES is enabled.',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Percona repository channel for the TO version. Always used for the PostgreSQL server packages; also used for pg_tde packages when INSTALL_FROM_PACKAGES is enabled.',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        booleanParam(
            name: 'USE_OBS_REPO',
            description: "Install the TO version from the OBS (openSUSE Build Service) repo instead of repo.percona.com. OBS only carries the latest minor, so the FROM version always comes from percona-release. TO_REPO still selects the channel (testing/release/experimental -> staging/releases/devel)."
        )
        string(
            name: 'OBS_HOST',
            defaultValue: '',
            description: 'OBS instance hostname to use when USE_OBS_REPO is enabled. Leave empty for the default public instance (download.opensuse.org).'
        )
        string(
            name: 'OBS_PROJECT',
            defaultValue: '',
            description: 'Full OBS project for the TO version when USE_OBS_REPO is enabled, e.g. isv:percona:PR:pr-42:ppg:staging:18 for a pull request build. Leave empty to derive it from TO_REPO and TO_VERSION.'
        )
        string(
            name: 'TESTING_BRANCH',
            defaultValue: 'main',
            description: 'Branch of ppg-testing to check out.'
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
            name: 'FROM_PKG_RELEASE',
            defaultValue: '',
            description: 'Optional Percona server build increment to pin for the FROM install ' +
                         '(e.g. "1" -> 18.4-1, "2" -> 18.4-2). Empty installs the latest build in FROM_REPO. ' +
                         'Use to model patched-release upgrades (18.4.1 -> 18.4.2). Packages path only.'
        )
        string(
            name: 'TO_PKG_RELEASE',
            defaultValue: '',
            description: 'Optional Percona server build increment to pin for the TO install ' +
                         '(e.g. "2" -> 18.4-2). Empty installs the latest build in TO_REPO. Packages path only.'
        )
        string(
            name: 'FROM_TDE_PKG_VERSION',
            defaultValue: '',
            description: 'Optional pg_tde package version to pin for the FROM install (e.g. "2.2.0"). ' +
                         'Empty installs the latest pg_tde in FROM_REPO. Packages path only (source path uses FROM_TDE_BRANCH).'
        )
        string(
            name: 'TO_TDE_PKG_VERSION',
            defaultValue: '',
            description: 'Optional pg_tde package version to pin for the TO install (e.g. "2.2.1"). ' +
                         'Empty installs the latest pg_tde in TO_REPO. Packages path only (source path uses TO_TDE_BRANCH).'
        )
        string(
            name: 'FROM_PERCONA_SERVER_VERSION',
            defaultValue: '',
            description: 'Optional expected Percona Server patch version for the FROM cluster (e.g. "18.4.1"). ' +
                         'When set, the test asserts SELECT version() reports it before the upgrade and fails otherwise.'
        )
        string(
            name: 'TO_PERCONA_SERVER_VERSION',
            defaultValue: '',
            description: 'Optional expected Percona Server patch version for the TO cluster (e.g. "18.4.2"). ' +
                         'When set, the test asserts SELECT version() reports it after the upgrade and fails otherwise.'
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy the VM after the test run. Disable to keep the VM for debugging.'
        )
        string(
            name: 'RUN_LABELS',
            defaultValue: 'Manual',
            description: 'Optional comma-separated labels to categorize this run, e.g. Manual, Nightly, Release.'
        )
    }

    environment {
        PATH        = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = 'pg_tde/upgrade'
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
                    sh "python3 tools/render.py --group pg_tde/upgrade"
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
