library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(fromVersion, toVersion, upgradeType, tdeUpgrade, fromSource, toSource) {
    def status = currentBuild.result == 'SUCCESS' ? '*SUCCESS*' : '*FAILURE*'
    def color  = currentBuild.result == 'SUCCESS' ? 'good'     : 'danger'

    // Human-readable upgrade-type label; tde_only always upgrades pg_tde.
    def upgradeTypeLabel
    if (upgradeType == 'tde_only') {
        upgradeTypeLabel = 'same_server_with_tde_upgrade_only'
    } else if (tdeUpgrade.toString() == 'true') {
        upgradeTypeLabel = "${upgradeType}_upgrade_with_tde_upgrade"
    } else {
        upgradeTypeLabel = "${upgradeType}_upgrade"
    }

    def summary = """Job: ${env.JOB_NAME}
FROM: ${fromVersion} (${fromSource})
TO: ${toVersion} (${toSource})
UPGRADE_TYPE: ${upgradeTypeLabel}
Status: ${status}
Build Report: ${env.BUILD_URL}"""
    slackSend color: color, message: summary, channel: '#postgresql-test'
}

// FROM_VERSION actually used: the parameter, or the default for this upgrade.
def fromVersion = ''

// ['pg-18', 'minor'] for TO_VERSION ppg-18.6 and UPGRADE_TYPE server_minor
// (psp-16.x -> 'psp-16'); null when there is no versions.yml default.
@NonCPS
def upgradeDefaultKey(String toVersion, String upgradeType) {
    def m = (toVersion =~ /^(ppg|psp)-(\d+)\./)
    if (!m.find()) { return null }
    def instance = (m.group(1) == 'psp' ? 'psp-' : 'pg-') + m.group(2)
    def kind = [server_minor: 'minor', server_major: 'major'][upgradeType]
    return kind ? [instance, kind] : null
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }

    parameters {
        choice(
            name: 'UPGRADE_TYPE',
            choices: ['server_minor', 'server_major', 'tde_only'],
            description: 'server_minor: same major, newer minor (package update + restart). ' +
                         'server_major: previous major to this major (pg_tde_upgrade). ' +
                         'tde_only: same server version, newer pg_tde.'
        )
        booleanParam(
            name: 'TDE_UPGRADE',
            defaultValue: true,
            description: 'Also upgrade pg_tde during a server_minor/server_major upgrade (tde_only always does).'
        )
        string(
            name: 'TO_VERSION',
            defaultValue: '',
            description: 'Required. Version under test, from OBS, e.g. ppg-18.6 (OBS QA sets it).'
        )
        string(
            name: 'OBS_PROJECT',
            defaultValue: '',
            description: 'OBS project of the TO version, e.g. isv:percona:ppg:staging:18 or a pull request project. ' +
                         'Empty: derived from TO_VERSION (isv:percona:ppg:staging:<major>).'
        )
        string(
            name: 'FROM_VERSION',
            defaultValue: '',
            description: 'Version to upgrade from. Empty: server_minor uses the minor-upgrade and server_major the ' +
                         'major-upgrade default_from_version in ppg-testing ppg/versions.yml (by TO_VERSION\'s major); ' +
                         'tde_only uses TO_VERSION.'
        )
        string(
            name: 'FROM_OBS_PROJECT',
            defaultValue: '',
            description: 'server_major only: OBS project of the FROM version. Empty: OBS_PROJECT with the major replaced ' +
                         '(...:staging:18 -> ...:staging:17), or derived from FROM_VERSION.'
        )
        choice(
            name: 'FROM_REPO',
            choices: ['release', 'testing', 'experimental'],
            description: 'server_minor and tde_only: repo.percona.com channel of the FROM version (OBS keeps only the ' +
                         'newest minor, so an older version comes from repo.percona.com).'
        )
        string(
            name: 'OBS_HOST',
            defaultValue: '',
            description: 'OBS download host. Empty: download.opensuse.org.'
        )
        text(
            name: 'EXPECTED_VERSIONS',
            defaultValue: '',
            description: 'Expected versions of the OBS-built packages, one package=version per line (OBS QA sets it). ' +
                         'After the upgrade the server (percona-postgresql) and pg_tde (percona-pg_tde) versions are ' +
                         'checked against it. Empty: no version check.'
        )
        string(
            name: 'TESTING_BRANCH',
            defaultValue: 'main',
            description: 'Branch of ppg-testing to check out.'
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
                    if (!(env.TO_VERSION ?: '').trim()) {
                        error("TO_VERSION is required, e.g. ppg-18.6 (OBS QA sets it)")
                    }
                    sh "python3 tools/render.py --group pg_tde/upgrade"
                    fromVersion = (params.FROM_VERSION ?: '').trim()
                    if (!fromVersion && env.UPGRADE_TYPE == 'tde_only') {
                        fromVersion = env.TO_VERSION
                        echo "FROM_VERSION is empty: tde_only keeps the server version, using ${fromVersion}"
                    } else if (!fromVersion) {
                        def key = upgradeDefaultKey(env.TO_VERSION ?: '', env.UPGRADE_TYPE ?: '')
                        if (key == null) {
                            error("FROM_VERSION is empty and no default exists for TO_VERSION=${env.TO_VERSION}, UPGRADE_TYPE=${env.UPGRADE_TYPE}")
                        }
                        fromVersion = sh(returnStdout: true, script: """python3 -c "import yaml; print(yaml.safe_load(open('ppg/versions.yml'))['instances']['${key[0]}']['${key[1]}-upgrade']['default_from_version'])" """).trim()
                        echo "FROM_VERSION is empty: using ${fromVersion}, the ${key[1]}-upgrade default of ${key[0]} in ppg/versions.yml"
                    }
                    currentBuild.displayName = "${env.BUILD_NUMBER}-upgrade-${fromVersion}-to-${env.TO_VERSION}-${env.UPGRADE_TYPE}"
                    // Carried to the Test stage through env: a parameter of the same name
                    // (FROM_VERSION) cannot be overridden, a variable of its own can.
                    env.RESOLVED_FROM_VERSION = fromVersion
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    // Build parameters win over env.X assignments, so pass the resolved
                    // FROM_VERSION to Molecule (tasks and checks) explicitly.
                    def resolvedFrom = (env.RESOLVED_FROM_VERSION ?: '').trim()
                    if (!resolvedFrom) {
                        error("FROM_VERSION is empty and was not resolved in the Prepare stage")
                    }
                    echo "Running the upgrade tests with FROM_VERSION=${resolvedFrom}"
                    withEnv(["FROM_VERSION=${resolvedFrom}"]) {
                        moleculeParallelTestPPG(ppgOperatingSystemsALL(), env.MOLECULE_DIR)
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                moleculeParallelPostDestroyPPG(ppgOperatingSystemsALL(), env.MOLECULE_DIR)
                def fromSource = env.UPGRADE_TYPE == 'server_major' ?
                    "OBS ${env.FROM_OBS_PROJECT ?: 'previous major project'}" :
                    "repo.percona.com ${env.FROM_REPO}"
                def toSource = "OBS ${env.OBS_PROJECT ?: 'project of TO_VERSION'}"
                sendSlackNotification(env.RESOLVED_FROM_VERSION ?: env.FROM_VERSION, env.TO_VERSION, env.UPGRADE_TYPE, env.TDE_UPGRADE, fromSource, toSource)
            }
            archiveArtifacts(
                artifacts: 'pg_tde/upgrade/artifacts/**/*.tar.gz',
                allowEmptyArchive: true
            )
        }
    }
}
