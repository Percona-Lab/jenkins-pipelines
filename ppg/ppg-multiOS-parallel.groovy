library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(scenario, version) {
    if (currentBuild.result == "SUCCESS") {
        buildSummary = "Job: ${env.JOB_NAME}\nScenario: ${scenario}\nVersion: ${version}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
        slackSend color: "good", message: "${buildSummary}", channel: '#postgresql-test'
    } else {
        buildSummary = "Job: ${env.JOB_NAME}\nScenario: ${scenario}\nVersion: ${version}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
        slackSend color: "danger", message: "${buildSummary}", channel: '#postgresql-test'
    }
}


// The operating systems to test: every supported one, or the PLATFORMS subset.
// An unknown name fails the build instead of silently testing nothing.
def selectedOperatingSystems() {
    def all = ppgOperatingSystemsALL()
    def wanted = (params.PLATFORMS ?: '').tokenize()
    if (!wanted) {
        return all
    }
    def unknown = wanted.findAll { !all.contains(it) }
    if (unknown) {
        error("Unknown PLATFORMS: ${unknown.join(' ')}. Supported: ${all.join(' ')}")
    }
    return all.findAll { wanted.contains(it) }
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        booleanParam(
            name: 'USE_OBS_REPO',
            defaultValue: false,
            description: 'Install from the OBS repo (isv:percona:ppg:&lt;channel&gt;:&lt;major_version&gt;) instead of repo.percona.com. REPO maps to the OBS channel: testing-&gt;staging, release-&gt;releases, experimental-&gt;devel; the major version is taken from VERSION.'
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
        string(
            defaultValue: '',
            description: 'Space-separated operating systems to test, e.g. "rocky-9 debian-13 ubuntu-noble". Leave empty to test every supported OS.',
            name: 'PLATFORMS'
        )
        text(
            defaultValue: '',
            description: 'Expected versions of the packages under test, one package=version per line keyed by OBS package name (e.g. percona-pgbackrest=2.59.2). Filled automatically by OBS QA (percona-obs qa); overrides ppg-testing versions/*.py for this run. Leave empty to use the tables.',
            name: 'EXPECTED_VERSIONS'
        )
        string(
            defaultValue: 'ppg-18.4',
            description: 'PG version for test',
            name: 'VERSION'
        )
        choice(
            name: 'IO_METHOD',
            description: 'io_method to use for the server (applicable to pg-18 and onwards only).',
            choices: [
                'worker',
                'sync',
                'io_uring'
            ]
        )
        choice(
            name: 'SCENARIO',
            description: 'PG scenario for test',
            choices: ppgScenarios()
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (ppg-17) repo instead of ppg-17.0"
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
                    if (params.MAJOR_REPO) {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${env.VERSION}-Major_Repo"
                    } else {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${env.VERSION}"
                    }
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
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    moleculeParallelTestPPG(selectedOperatingSystems(), env.MOLECULE_DIR)
                }
            }
        }
    }
    post {
        always {
            script {
                moleculeParallelPostDestroyPPG(selectedOperatingSystems(), env.MOLECULE_DIR)
                sendSlackNotification(env.SCENARIO, env.VERSION)
            }
        }
    }
}
