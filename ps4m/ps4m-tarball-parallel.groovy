library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "ps4m/ps4m-tarball"

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
    }
    parameters {
        choice(
            name: 'REPO',
            description: 'Percona release repo channel for package install',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        string(
            defaultValue: 'psmdb-83',
            description: 'Percona release repo name, e.g. psmdb-83',
            name: 'PSMDB_REPO'
        )
        string(
            defaultValue: 'v8.3',
            description: 'PSMDB git branch for resmoke sources',
            name: 'PSMDB_BRANCH'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/TESTING/mongot-0.51.0/percona-server-mongodb-mongot-0.51.0-linux_x86_64.tar.gz',
            description: 'URL for mongot tarball (x86_64; aarch64 URL is derived automatically)',
            name: 'MONGOT_TARBALL_URL'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.REPO}-${params.PSMDB_REPO}"
                    currentBuild.description = "${params.MONGOT_TARBALL_URL}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
            }
        }
        stage ('Prepare') {
            steps {
                script {
                    installMoleculeBookworm()
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    moleculeParallelTestPSMDB(pdmdbOperatingSystems(PSMDB_REPO), moleculeDir)
                }
            }
            post {
                always {
                    junit testResults: "**/*-report.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
    }
    post {
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: PS4M tarball tests for ${MONGOT_TARBALL_URL} with ${PSMDB_REPO} repo ${REPO} - finished succesfully - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: PS4M tarball tests for ${MONGOT_TARBALL_URL} with ${PSMDB_REPO} repo ${REPO} - failed - [${BUILD_URL}]")
        }
        always {
            script {
                moleculeParallelPostDestroy(pdmdbOperatingSystems(PSMDB_REPO), moleculeDir)
            }
        }
    }
}
