library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(scenario, fromVersion, toVersion) {
    if (currentBuild.result == "SUCCESS") {
        buildSummary = "Job: ${env.JOB_NAME}\nScenario: ${scenario}\nFrom version: ${fromVersion}\nTo version: ${toVersion}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
        slackSend color: "good", message: "${buildSummary}", channel: '#postgresql-test'
    } else {
        buildSummary = "Job: ${env.JOB_NAME}\nScenario: ${scenario}\nFrom version: ${fromVersion}\nTo version: ${toVersion}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
        slackSend color: "danger", message: "${buildSummary}", channel: '#postgresql-test'
    }
}

// FROM_VERSION actually used: the parameter, or the scenario's default.
def fromVersion = ''

// 'pg-18-minor-upgrade' -> ['pg-18', 'minor']; null for other scenarios.
@NonCPS
def upgradeScenarioParts(String scenario) {
    def m = (scenario =~ /^(.+)-(minor|major)-upgrade$/)
    return m.matches() ? [m.group(1), m.group(2)] : null
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        choice(
            name: 'FROM_REPO',
            description: 'From this repo will be upgraded PPG',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        booleanParam(
            name: 'USE_OBS_REPO',
            defaultValue: false,
            description: 'Install the TO version from the OBS repo (isv:percona:ppg:&lt;channel&gt;:&lt;major_version&gt;) instead of repo.percona.com. TO_REPO maps to the OBS channel: testing-&gt;staging, release-&gt;releases, experimental-&gt;devel. OBS only ever carries the latest minor of each major, so the FROM version always installs from repo.percona.com regardless of this setting.'
        )
        string(
            defaultValue: '',
            description: 'OBS instance hostname to use when USE_OBS_REPO is enabled. Leave empty for the default public instance (download.opensuse.org).',
            name: 'OBS_HOST'
        )
        string(
            defaultValue: '',
            description: 'Full OBS project to install from when USE_OBS_REPO is enabled, e.g. isv:percona:PR:pr-42:ppg:staging:18 for a pull request build. Leave empty to derive it from REPO and VERSION.',
            name: 'OBS_PROJECT'
        )
        text(
            defaultValue: '',
            description: 'Expected versions of the packages for the version upgraded to, one package=version per line keyed by OBS package name (e.g. percona-pgbackrest=2.59.2). Filled automatically by OBS QA (percona-obs qa); overrides ppg-testing versions/*.py for this run. Leave empty to use the tables.',
            name: 'EXPECTED_VERSIONS'
        )
        string(
            defaultValue: '',
            description: 'From this version PPG will be updated. Empty: the scenario\'s default_from_version in ppg-testing ppg/versions.yml (the latest released version to upgrade from).',
            name: 'FROM_VERSION'
        )
        string(
            defaultValue: 'ppg-18.4',
            description: 'To this version PPG will be updated',
            name: 'VERSION'
        )
        choice(
            name: 'SCENARIO',
            description: 'PG version for test',
            choices: ppgUpgradeScenarios()
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            name: 'RUN_LABELS',
            defaultValue: 'Manual',
            description: 'Optional comma-separated labels to categorize this run, e.g. Manual, Nightly, Release.'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "ppg/${SCENARIO}"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
        buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '100', artifactNumToKeepStr: '10'))
        retry(conditions: [agent()], count: 2)
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${env.FROM_VERSION}-to-${env.VERSION}"
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
                    sh "python3 tools/render.py --group ppg/${SCENARIO}"
                    fromVersion = (params.FROM_VERSION ?: '').trim()
                    if (!fromVersion) {
                        def parts = upgradeScenarioParts(env.SCENARIO)
                        if (parts == null) {
                            error("FROM_VERSION is empty and ${env.SCENARIO} is not a minor/major upgrade scenario")
                        }
                        fromVersion = sh(returnStdout: true, script: """python3 -c "import yaml; print(yaml.safe_load(open('ppg/versions.yml'))['instances']['${parts[0]}']['${parts[1]}-upgrade']['default_from_version'])" """).trim()
                        echo "FROM_VERSION is empty: using ${fromVersion}, the default for ${env.SCENARIO} in ppg/versions.yml"
                    }
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${fromVersion}-to-${env.VERSION}"
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
                    // FROM_VERSION to Molecule (tasks, verifier tests) explicitly.
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
                sendSlackNotification(env.SCENARIO, env.RESOLVED_FROM_VERSION ?: env.FROM_VERSION, env.VERSION)
            }
        }
    }
}
