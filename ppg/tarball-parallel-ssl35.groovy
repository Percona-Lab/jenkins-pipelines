library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(version) {
    if (currentBuild.result == "SUCCESS") {
        buildSummary = "Job: ${env.JOB_NAME}\nVersion: ${version}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
        slackSend color: "good", message: "${buildSummary}", channel: '#postgresql-test'
    } else {
        buildSummary = "Job: ${env.JOB_NAME}\nVersion: ${version}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
        slackSend color: "danger", message: "${buildSummary}", channel: '#postgresql-test'
    }
}


pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        choice(
            name: 'SSL_VERSION',
            description: 'SSL version to use',
            choices: [
                '3.5'
            ]
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
        string(
            defaultValue: 'https://downloads.percona.com/downloads/TESTING/pg_tarballs-18.4/percona-postgresql-18.4-ssl3.5-linux-x86_64.tar.gz',
            description: 'URL for tarball. Leave empty and set OBS_PROJECT to test the tarball OBS built for this SSL variant.',
            name: 'TARBALL_URL'
        )
        string(
            defaultValue: '',
            description: 'OBS tarballs project to take the tarball from when TARBALL_URL is empty, e.g. isv:percona:ppg:staging:18:tarballs (or a PR project). Filled in by OBS QA.',
            name: 'OBS_PROJECT'
        )
        string(
            defaultValue: '',
            description: 'OBS download host for OBS_PROJECT. Empty: download.opensuse.org.',
            name: 'OBS_HOST'
        )
        text(
            defaultValue: '',
            description: 'Expected versions of the packages in the tarball, one package=version per line keyed by OBS package name (e.g. percona-pgbackrest=2.59.2). Filled automatically by OBS QA; overrides ppg-testing tables for this run. Leave empty to use the tables.',
            name: 'EXPECTED_VERSIONS'
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
        MOLECULE_DIR = "ppg/pg-tarballs"
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}-${env.JOB_NAME}"
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
                    sh "python3 tools/render.py --group ppg/pg-tarballs"
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    moleculeParallelTestPPG(ppgOperatingSystemsSSL35(), env.MOLECULE_DIR)
                }
            }
        }
    }
    post {
        always {
            script {
                moleculeParallelPostDestroyPPG(ppgOperatingSystemsSSL35(), env.MOLECULE_DIR)
                sendSlackNotification(env.VERSION)
            }
        }
    }
}
