library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runSubmodulesRewind(String SUBMODULES_GIT_BRANCH) {
    rewindSubmodule = build job: 'pmm2-rewind-submodules-fb', propagate: false, parameters: [
        string(name: 'GIT_BRANCH', value: SUBMODULES_GIT_BRANCH)
    ]
}

void runPMM2ServerAutobuild(String SUBMODULES_GIT_BRANCH, String DESTINATION) {
    pmm2Server = build job: 'pmm2-server-autobuild', parameters: [
        string(name: 'GIT_BRANCH', value: SUBMODULES_GIT_BRANCH),
        string(name: 'DESTINATION', value: DESTINATION)
    ]
}

void runPMM2ClientAutobuild(String SUBMODULES_GIT_BRANCH, String DESTINATION) {
    pmm2Client = build job: 'pmm2-client-autobuilds', parameters: [
        string(name: 'GIT_BRANCH', value: SUBMODULES_GIT_BRANCH),
        string(name: 'DESTINATION', value: DESTINATION)
    ]
    env.TARBALL_URL = pmm2Client.buildVariables.TARBALL_URL
}

void runPMM2AMIBuild(String SUBMODULES_GIT_BRANCH, String RELEASE_CANDIDATE) {
    pmm2AMI = build job: 'pmm2-ami', parameters: [
        string(name: 'PMM_BRANCH', value: SUBMODULES_GIT_BRANCH),
        string(name: 'RELEASE_CANDIDATE', value: RELEASE_CANDIDATE)
    ]
    env.AMI_ID = pmm2AMI.buildVariables.AMI_ID
}

void runPMM2OVFBuild(String SUBMODULES_GIT_BRANCH, String RELEASE_CANDIDATE) {
    pmm2OVF = build job: 'pmm2-ovf', parameters: [
        string(name: 'PMM_BRANCH', value: SUBMODULES_GIT_BRANCH),
        string(name: 'RELEASE_CANDIDATE', value: RELEASE_CANDIDATE)
    ]
}

def pmm_submodules() {
    return [
        "pmm",
        "pmm-update",
        "grafana-dashboards",
        "pmm-ui-tests",
        "pmm-qa",
        "mysqld_exporter",
        "grafana",
        "dbaas-controller",
        "node_exporter",
        "postgres_exporter",
        "clickhouse_exporter",
        "proxysql_exporter",
        "rds_exporter",
        "azure_metrics_exporter",
        "percona-toolkit",
        "pmm-dump"
    ]
}

String DEFAULT_BRANCH = 'PMM-6352-custom-build-ol9'

pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: DEFAULT_BRANCH,
            description: 'Choose a pmm-submodules branch to build the image from',
            name: 'SUBMODULES_GIT_BRANCH')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }    
    stages {
        stage('Get version') {
            steps {
                script {
                    git branch: env.SUBMODULES_GIT_BRANCH,
                        credentialsId: 'GitHub SSH Key',
                        poll: false,
                        url: 'git@github.com:Percona-Lab/pmm-submodules'
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                    env.RELEASE_BRANCH = 'pmm-' + VERSION
                }
            }
        }
        stage('Check if Release Branch Exists') {
            steps {
                deleteDir()
                script {
                    currentBuild.description = "$VERSION"
                    slackSend botUser: true,
                        channel: '@alexander.tymchuk',
                        color: '#0000FF',
                        message: "Special build for PMM $VERSION has started. You can check progress at: ${BUILD_URL}"
                    env.EXIST = sh (
                        script: 'git ls-remote --heads https://github.com/Percona-Lab/pmm-submodules pmm-${VERSION} | wc -l',
                        returnStdout: true
                    ).trim()
                }
            }
        }
        stage('Checkout Submodules and Prepare for creating branches') {
            when {
                expression { env.EXIST.toInteger() == 0 }
            }
            steps {
                error "The branch does not exist. Please create a bramch in percona/pmm-submodules"
            }
        }
        stage('Rewind Release Submodule') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            steps {
                runSubmodulesRewind(RELEASE_BRANCH)
            }
        }
        stage('Autobuilds RC for Server & Client') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            parallel {
                stage('Start PMM2 Server Autobuild') {
                    steps {
                        runPMM2ServerAutobuild(RELEASE_BRANCH, 'experimental')
                    }
                }
                stage('Start PMM2 Client Autobuild') {
                    steps {
                        runPMM2ClientAutobuild(RELEASE_BRANCH, 'experimental')
                    }
                }
            }
        }
        stage('Autobuilds RC for OVF & AMI') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            stage('Start OVF Build for RC') {
                steps {
                    runPMM2OVFBuild("pmm-${VERSION}", 'yes')
                }
            }
        }
    }
    post {
        success {
            slackSend botUser: true,
                      channel: '#pmm-dev',
                      color: '#00FF00',
                      message: """New Release Candidate is out :rocket:
Server: perconalab/pmm-server:${VERSION}-rc
Client: perconalab/pmm-client:${VERSION}-rc
OVA: https://percona-vm.s3.amazonaws.com/PMM2-Server-${VERSION}.ova
AMI: ${env.AMI_ID}
Tarball: ${env.TARBALL_URL}
                      """
        }
    }
}
