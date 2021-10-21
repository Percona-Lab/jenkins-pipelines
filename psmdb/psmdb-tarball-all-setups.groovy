library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb-tarball/psmdb-tarball-upgrade"
def confList = [ 'single' , 'replicaset' , 'sharded' ]
def encList = [ 'NONE', 'KEYFILE', 'VAULT']

pipeline {
    agent {
    label 'min-centos-7-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
    }
    parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdmdbOperatingSystems()
        )
        choice(
            name: 'INSTANCE_TYPE',
            description: 'Ec2 instance type',
            choices: [
                't2.micro',
                't2.medium',
                't2.large',
                't2.xlarge'
            ]
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-4.2/percona-server-mongodb-4.2.15-16/binary/tarball/percona-server-mongodb-4.2.15-16-x86_64.glibc2.17-minimal.tar.gz',
            description: 'URL/S3 link for tarball to upgrade/downgrade from',
            name: 'OLD_TARBALL'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-LATEST/percona-server-mongodb-4.4.8-9/binary/tarball/percona-server-mongodb-4.4.8-9-x86_64.glibc2.17-minimal.tar.gz',
            description: 'URL/S3 link for tarball to upgrade/downgrade to',
            name: 'NEW_TARBALL'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}"
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
                    installMolecule()
                }
            }
        }
        stage ('Create virtual machines') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "create", env.PLATFORM)
                }
            }
        }
        stage ('Prepare VM for test') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "prepare", env.PLATFORM)
                }
            }
        }
        stage ('Run tests for all configurations') {
            steps {
                script {
                    for(conf in confList) {
                        for(enc in encList) {
                            stage("Test ${conf} with ${enc} encryption") {
                                script {
                                    moleculeExecuteActionWithVariableListAndScenario(moleculeDir, "converge", env.PLATFORM, "LAYOUT_TYPE=${conf} ENCRYPTION=${enc} CIPHER=AES256-CBC PSMDB_VERSION=${params.OLD_TARBALL}")
                                    moleculeExecuteActionWithVariableListAndScenario(moleculeDir, "verify", env.PLATFORM, "LAYOUT_TYPE=${conf} ENCRYPTION=${enc} CIPHER=AES256-CBC PSMDB_VERSION=${params.OLD_TARBALL}")
                                }
                                junit testResults: "**/*-report.xml", keepLongStdio: true
                            }
                            if (env.NEW_TARBALL != '') {
                                stage("Upgrade ${conf}  with ${enc} encryption") {
                                    script {
                                        moleculeExecuteActionWithVariableListAndScenario(moleculeDir, "side-effect", env.PLATFORM, "LAYOUT_TYPE=${conf} ENCRYPTION=${enc} CIPHER=AES256-CBC PSMDB_VERSION=${params.NEW_TARBALL}")
                                        moleculeExecuteActionWithVariableListAndScenario(moleculeDir, "verify", env.PLATFORM, "LAYOUT_TYPE=${conf} ENCRYPTION=${enc} CIPHER=AES256-CBC PSMDB_VERSION=${params.NEW_TARBALL}")
                                    }
                                    junit testResults: "**/*-report.xml", keepLongStdio: true
                                }
                            }
                            stage("Cleanup") {
                                script {
                                    moleculeExecuteActionWithScenario(moleculeDir, "cleanup", env.PLATFORM)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
             script {
                 moleculeExecuteActionWithScenario(moleculeDir, "destroy", env.PLATFORM)
             }
        }
    }
}

