library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'master'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        string(name: 'PCSM_VERSION', defaultValue: '0.9.0', description: 'PCSM Version')
        string(name: 'PCSM_BRANCH', defaultValue: 'main', description: 'PCSM Branch')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
//        stage ('Run PCSM functional tests') {
//            steps {
//                build job: 'hetzner-pcsm-functional-tests', propagate: false, parameters: [ string(name: 'CLOUD', value: params.CLOUD), string(name: 'PCSM_BRANCH', value: params.PCSM_BRANCH )]
//            }
//        }
        stage ('Run PCSM package tests') {
            steps {
                build job: 'pcsm-packaging', parameters: [ string(name: 'pcsm_version', value: params.PCSM_VERSION), string(name: 'install_repo', value: "testing" )]
            }
        }
        stage ('Create single-arch (amd64) docker image') {
            steps {
                script {
                    def version = params.PCSM_VERSION + '-1'
                    build job: 'pcsm-docker', parameters: [string(name: 'CLOUD', value: params.CLOUD), string(name: 'PCSM_REPO_CH', value: 'testing'), string(name: 'PCSM_VERSION', value: version), string(name: 'LATEST', value: "no") ]
                }
            }
        }
        stage ('Create multi-arch (amd64+arm) docker image') {
            steps {
                script {
                    def version = params.PBM_VERSION + '-1'
                    build job: 'pcsm-docker-arm', parameters: [string(name: 'CLOUD', value: params.CLOUD), string(name: 'PCSM_REPO_CH', value: 'testing' ), string(name: 'PCSM_VERSION', value: version), string(name: 'LATEST', value: "no") ]
                }
            }
        }
        stage ('Run PCSM tarball/docker SBOM tests') {
            steps {
                script {
                    build job: 'hetzner-pcsm-sbom-tests', parameters: [string(name: 'PCSM_VERSION', value: params.PCSM_VERSION), string(name: 'install_repo', value: "testing")]
                }
            }
        }
    }
}
