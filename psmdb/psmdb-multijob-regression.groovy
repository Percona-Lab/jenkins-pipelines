library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'launcher-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        string(name: 'PSMDB_BRANCH', defaultValue: 'master', description: 'PSMDB branch')
        string(name: 'PSMDB_VERSION', defaultValue: '8.2.0', description: 'PSMDB version')
    }
    options {
          disableConcurrentBuilds()
    }
    stages {
        stage ('Run regression tests on ubuntu 24.04') {
            steps {
                script {
                    build job: 'hetzner-psmdb-regression', propagate: false, wait: false, parameters: [ 
                        string(name: 'branch', value: params.PSMDB_BRANCH), 
                        string(name: 'version', value: params.PSMDB_VERSION), 
                        string(name: 'tag', value: params.PSMDB_BRANCH),
                        string(name: 'parallelexecutors', value: '2'),
                        string(name: 'testsuites', value: 'core,unittests,dbtest,audit,oidc,telemetry --jobs=1,ldapauthz||/etc/init.d/slapd start && /etc/init.d/saslauthd start,replica_sets_fcbis_jscore_passthrough')                    
                    ]
                }
            }
        }
        stage ('Run regression tests on oraclelinux9') {
            steps {
                script {
                    def tag = params.PSMDB_BRANCH + '-ol9'
                    build job: 'hetzner-psmdb-regression', propagate: true, wait: true, parameters: [ 
                        string(name: 'branch', value: params.PSMDB_BRANCH),
                        string(name: 'version', value: params.PSMDB_VERSION), 
                        string(name: 'tag', value: tag),
                        string(name: 'testsuites', value: 'core'),
                        booleanParam(name: 'unittests', value: false),
                        string(name: 'OS', value: 'oraclelinux:9')                         
                    ]
                }
            }
        }
        stage ('Run PBM tests') {
            steps {
                script {
                    def image = 'public.ecr.aws/e7j3v3n0/psmdb-build:' + params.PSMDB_BRANCH + '-ol9'
                    build job: 'hetzner-pbm-functional-tests', propagate: false, wait: false, parameters: [ string(name: 'PSMDB', value: image) ]
                }
            }
        }
    }
} 
