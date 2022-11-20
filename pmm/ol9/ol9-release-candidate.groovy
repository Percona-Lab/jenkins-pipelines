library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runPMM2AMIBuild(String SUBMODULES_GIT_BRANCH, String RELEASE_CANDIDATE) {
    pmm2AMI = build job: 'pmm2-ami', parameters: [
        string(name: 'PMM_BRANCH', value: SUBMODULES_GIT_BRANCH),
        string(name: 'RELEASE_CANDIDATE', value: RELEASE_CANDIDATE)
    ]
    env.AMI_ID = pmm2AMI.buildVariables.AMI_ID
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
    environment {
        // intentionally hard-coded
        REMOVE_RELEASE_BRANCH = 'no'
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
                    env.RELEASE_BRANCH = DEFAULT_BRANCH
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
                        script: 'git ls-remote --heads https://github.com/Percona-Lab/pmm-submodules ${RELEASE_BRANCH} | wc -l',
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
                error "The branch does not exist. Please create a branch in percona/pmm-submodules"
            }
        }
        stage('Rewind Release Submodule') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            steps {
                echo "No rewind for the time being"
                // build job: 'pmm2-rewind-submodules-fb', propagate: false, parameters: [
                //     string(name: 'GIT_BRANCH', value: SUBMODULES_GIT_BRANCH)
                // ]              
            }
        }
        stage('Build Server & Client') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            parallel {
                stage('Start OL9 Server Build') {
                    steps {
                        build job: 'ol9-build-server', parameters: [
                            string(name: 'GIT_BRANCH', value: RELEASE_BRANCH),
                            string(name: 'DESTINATION', value: 'experimental')
                        ]
                    }
                }
                stage('Start OL9 Client Build') {
                    steps {
                        pmm2Client = build job: 'ol9-build-client', parameters: [
                            string(name: 'GIT_BRANCH', value: RELEASE_BRANCH),
                            string(name: 'DESTINATION', value: 'experimental')
                        ]
                        env.TARBALL_URL = pmm2Client.buildVariables.TARBALL_URL
                    }
                }
            }
        }
        stage('Build OVF') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            stage('Start OL9 OVF Build') {
                steps {
                    build job: 'pmm2-ovf', parameters: [
                        string(name: 'PMM_BRANCH', value: 'PMM-6352-custom-build-ol9'),
                        string(name: 'RELEASE_CANDIDATE', value: 'no')
                    ]                    
                }
            }
        }
    }
    post {
        success {
            slackSend botUser: true,
                      channel: '@alexander.tymchuk',
                      color: '#00FF00',
                      message: """New OL9 RC is out :rocket:
Server: perconalab/pmm-server:${VERSION}-rc
Client: perconalab/pmm-client:${VERSION}-rc
OVA: https://percona-vm.s3.amazonaws.com/PMM2-Server-${VERSION}.ova
AMI: ${env.AMI_ID}
Tarball: ${env.TARBALL_URL}
                      """
        }
    }
}
