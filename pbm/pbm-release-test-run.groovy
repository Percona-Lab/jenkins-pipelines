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
        string(name: 'PBM_VERSION', defaultValue: '2.0.5', description: 'PBM Version')
        string(name: 'PBM_BRANCH', defaultValue: 'release-2.0.5', description: 'PBM Branch')
    }
    options {
          disableConcurrentBuilds()
    }
    stages {
        stage ('Run e2e tests') {
            steps {
                build job: 'hetzner-pbm-functional-tests-full', propagate: false, wait: true, parameters: [ string(name: 'PBM_BRANCH', value: params.PBM_BRANCH) ]
            }
        }
        stage ('Run package tests') {
            steps {
                build job: 'pbm-pkg-install-parallel', parameters: [ string(name: 'install_repo', value: "testing"), string(name: 'psmdb_to_test', value: "psmdb-60" ), string(name: 'VERSION', value: params.PBM_VERSION)]
            }
        }
        stage ('Build docker images and check for vulnerabilities') { 
            steps { 
                script {
                    def version = params.PBM_VERSION + '-1'
                    build job: 'hetzner-pbm-docker', parameters: [string(name: 'PBM_REPO_CH', value: "testing"), string(name: 'PBM_VERSION', value: version ), string(name: 'LATEST', value: "no") ]
                }
            }
        }
    }
} 
