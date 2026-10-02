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
        string(
            defaultValue: 'ppg-18.3',
            description: 'From this version PPG will be updated',
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
                sendSlackNotification(env.SCENARIO, env.FROM_VERSION, env.VERSION)
            }
        }
    }
}
