library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker'
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
                    build job: 'psmdb-docker', parameters: [string(name: 'PSMDB_REPO', value: "testing"), string(name: 'PSMDB_VERSION', value: version ), string(name: 'LATEST', value: "no") ]
                }
            }
        }
    }
} 
