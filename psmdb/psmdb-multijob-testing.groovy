library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'master'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        string(name: 'PSMDB_VERSION', defaultValue: '4.4.17', description: 'PSMDB Version')
        string(name: 'PSMDB_RELEASE', defaultValue: '17', description: 'PSMDB Release')
    }
    options {
          disableConcurrentBuilds()
    }
    stages {
        stage ('Run functional tests on tarballs') {
            steps {
                script {
                    // path https://downloads.percona.com/downloads/TESTING/psmdb-4.4.17/percona-server-mongodb-4.4.17-17-x86_64.glibc2.17-minimal.tar.gz
                    def tarball = 'https://downloads.percona.com/downloads/TESTING/psmdb-' + params.PSMDB_VERSION + '/percona-server-mongodb-' + params.PSMDB_VERSION + '-' + params.PSMDB_RELEASE + '-x86_64.glibc2.17-minimal.tar.gz'
                    build job: 'psmdb-tarball-functional', propagate: false, wait: true, parameters: [ string(name: 'TARBALL', value: tarball), string(name: 'TESTING_BRANCH', value: "main") ]
                }
            }
        }
        stage ('Run functional tests on packages') {
            steps {
                build job: 'psmdb-parallel', parameters: [ string(name: 'REPO', value: "testing"), string(name: 'PSMDB_VERSION', value: params.PSMDB_VERSION ), string(name: 'ENABLE_TOOLKIT', value: "false"), string(name: 'TESTING_BRANCH', value: "main") ]
            }
        }
        stage ('Build docker images and check for vulnerabilities') {
            steps {
                script {
                    def version = params.PSMDB_VERSION + '-' + params.PSMDB_RELEASE
                    build job: 'psmdb-docker', parameters: [string(name: 'PSMDB_REPO', value: "testing"), string(name: 'PSMDB_VERSION', value: version ), string(name: 'TARGET_REPO', value: "PerconaLab") ]
                    build job: 'psmdb-docker-arm', parameters: [string(name: 'PSMDB_REPO', value: "testing"), string(name: 'PSMDB_VERSION', value: version ), string(name: 'TARGET_REPO', value: "PerconaLab") ]
                }
            }
        }
        stage ('Run integration tests') {
            steps {
                script {
                    def version = params.PSMDB_VERSION + '-' + params.PSMDB_RELEASE
                    build job: 'psmdb-integration', parameters: [string(name: 'TEST_VERSION', value: "main"), string(name: 'PSMDB_VERSION', value: version), string(name: 'PBM_VERSION', value: "latest" ), string(name: 'PMM_VERSION', value: "latest"), string(name: 'PMM_REPO', value: "release"), string(name: 'PMM_IMAGE', value: "percona/pmm-server:2") ]
                    build job: 'psmdb-integration', parameters: [string(name: 'TEST_VERSION', value: "v3"), string(name: 'PSMDB_VERSION', value: version), string(name: 'PBM_VERSION', value: "latest" ), string(name: 'PMM_VERSION', value: "latest"), string(name: 'PMM_REPO', value: "release"), string(name: 'PMM_IMAGE', value: "percona/pmm-server:latest") ]
                    build job: 'psmdb-integration', parameters: [string(name: 'TEST_VERSION', value: "v3"), string(name: 'PSMDB_VERSION', value: version), string(name: 'PBM_VERSION', value: "latest" ), string(name: 'PMM_VERSION', value: "latest"), string(name: 'PMM_REPO', value: "experimental"), string(name: 'PMM_IMAGE', value: "perconalab/pmm-server:dev-latest") ]
                }
            }
        }
    }
} 
