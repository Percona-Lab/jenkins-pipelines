library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(pgsm_repo, pgsm_branch, version) {
    if (currentBuild.result == "SUCCESS") {
        buildSummary = "Job: ${env.JOB_NAME}\nPGSM_Repo: ${pgsm_repo}\nPGSM_Branch: ${pgsm_branch}\nVersion: ${version}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
        slackSend color: "good", message: "${buildSummary}", channel: '#postgresql-test'
    } else {
        buildSummary = "Job: ${env.JOB_NAME}\nPGSM_Repo: ${pgsm_repo}\nPGSM_Branch: ${pgsm_branch}\nVersion: ${version}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
        slackSend color: "danger", message: "${buildSummary}", channel: '#postgresql-test'
    }
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/pg_stat_monitor.git',
            description: 'PGSM repo that we want to test, we could also use forked developer repo here.',
            name: 'PGSM_REPO'
        )
        string(
            defaultValue: '2.3.2',
            description: 'PGSM repo version/branch/tag to use; e.g main, 2.0.5',
            name: 'PGSM_BRANCH'
        )
        string(
            defaultValue: 'pg-18.4',
            description: 'PGDG Server PG version for test, including major and minor version, e.g pg-16.2, pg-15.5',
            name: 'VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository',
            name: 'TESTING_BRANCH'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "pg_stat_monitor/pgsm_pgdg"
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-pgsm-${env.VERSION}"
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
                sendSlackNotification(env.PGSM_REPO, env.PGSM_BRANCH, env.VERSION)
            }
            archiveArtifacts(
                artifacts: 'pg_stat_monitor/pgsm_pgdg/artifacts/**/*.tar.gz',
                allowEmptyArchive: true
            )
        }
    }
}
