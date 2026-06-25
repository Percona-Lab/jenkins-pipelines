library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(fromVersion, toVersion, upgradeType, tdeUpgrade, installFromPackages, fromRepo, toRepo, fromTdeBranch, toTdeBranch, fromPkgRelease, toPkgRelease, fromTdePkgVersion, toTdePkgVersion) {
    def status = currentBuild.result == 'SUCCESS' ? '*SUCCESS*' : '*FAILURE*'
    def color  = currentBuild.result == 'SUCCESS' ? 'good'     : 'danger'

    def tdeOn = (tdeUpgrade.toString() == 'true')

    // Derived, human-readable upgrade-type label.
    // tde_only always upgrades pg_tde, so its label is the same regardless of TDE_UPGRADE.
    def upgradeTypeLabel
    if (upgradeType == 'tde_only') {
        upgradeTypeLabel = 'same_server_with_tde_upgrade_only'
    } else if (tdeOn) {
        upgradeTypeLabel = "${upgradeType}_upgrade_with_tde_upgrade"
    } else {
        upgradeTypeLabel = "${upgradeType}_upgrade"
    }

    // Show repo channels when installing pg_tde from packages, otherwise the source branches.
    def sourceLines
    if (installFromPackages.toString() == 'true') {
        sourceLines = """FROM_REPO: ${fromRepo}
TO_REPO: ${toRepo}"""
    } else {
        sourceLines = """FROM_TDE_BRANCH: ${fromTdeBranch}
TO_TDE_BRANCH: ${toTdeBranch}"""
    }

    // Surface any package pins (patched-release upgrades) so the run is self-describing.
    def pinLines = ""
    if (fromPkgRelease)      { pinLines += "\nFROM_PKG_RELEASE: ${fromPkgRelease}" }
    if (toPkgRelease)        { pinLines += "\nTO_PKG_RELEASE: ${toPkgRelease}" }
    if (fromTdePkgVersion)   { pinLines += "\nFROM_TDE_PKG_VERSION: ${fromTdePkgVersion}" }
    if (toTdePkgVersion)     { pinLines += "\nTO_TDE_PKG_VERSION: ${toTdePkgVersion}" }

    def summary = """Job: ${env.JOB_NAME}
FROM_VERSION: ${fromVersion}
TO_VERSION: ${toVersion}
UPGRADE_TYPE: ${upgradeTypeLabel}
${sourceLines}${pinLines}
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
                         'server_minor: PostgreSQL minor-version update within the same major (e.g. ppg-17.4 -> ppg-17.5), via package update + restart. ' +
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
                sendSlackNotification(env.FROM_VERSION, env.TO_VERSION, env.UPGRADE_TYPE, env.TDE_UPGRADE, env.INSTALL_FROM_PACKAGES, env.FROM_REPO, env.TO_REPO, env.FROM_TDE_BRANCH, env.TO_TDE_BRANCH, env.FROM_PKG_RELEASE, env.TO_PKG_RELEASE, env.FROM_TDE_PKG_VERSION, env.TO_TDE_PKG_VERSION)
            }
            archiveArtifacts(
                artifacts: 'pg_tde/upgrade/artifacts/**/*.tar.gz',
                allowEmptyArchive: true
            )
        }
    }
}
