library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
    label 'min-centos-7-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdmdbOperatingSystems()
        )
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '5.0.2',
            description: 'PSMDB Version for tests',
            name: 'PSMDB_VERSION'
        )
        string(
            defaultValue: '',
            description: 'previous minor PSMDB version for upgrade tests (leave blank to skip)',
            name: 'PREV_MIN_PSMDB_VERSION'
        )
        string(
            defaultValue: '4.4.8',
            description: 'previous major PSMDB version for upgrade tests (leave blank to skip)',
            name: 'PREV_MAJ_PSMDB_VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'base Branch for upgrade test',
            name: 'TESTING_BRANCH')

    }
    options {
          withCredentials(moleculePbmJenkinsCreds())
          disableConcurrentBuilds()
    }
    stages {
        stage ('Test All') {
            parallel {
                stage ('fuctional tests') {
                    steps {
                        build job: 'psmdb', parameters: [
                        string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                        ]
                    }
                }
                stage('upgrade from minor version without encryption') {
                    steps {
                        script {
                            if (env.PREV_MIN_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade", parameters: [
                                 string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PREV_MIN_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "NONE"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                             }
                             else {
                                  echo 'skipped upgrade from minor version'
                             }
                        }
                    }
                }
                stage('upgrade from minor vesrsion with vault encryption') {
                    steps {
                        script {
                            if (env.PREV_MIN_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade", parameters: [
                                 string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PREV_MIN_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "VAULT"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped upgrade from minor version'
                            }
                        }
                    }
                }
                stage('upgrade from major version without encryption') {
                    steps {
                        script {
                            if (env.PREV_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade", parameters: [
                                 string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PREV_MAJ_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "NONE"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped upgrade from major version'
                            }
                        }
                    }
                }
                stage('upgrade from major version with vault encryption') {
                    steps {
                        script {
                            if (env.PREV_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade", parameters: [
                                 string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PREV_MAJ_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "VAULT"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped upgrade from major version'
                            }
                        }
                    }
                }
                stage('downgrade to minor version without encryption') {
                    steps {
                        script {
                            if (env.PREV_MIN_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade", parameters: [
                                 string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PREV_MIN_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "NONE"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                             }
                             else {
                                  echo 'skipped downgrade to minor version'
                             }
                        }
                    }
                }
                stage('downgrade to minor version with vault encryption') {
                    steps {
                        script {
                            if (env.PREV_MIN_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade", parameters: [
                                 string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PREV_MIN_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "VAULT"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped downgrade to minor version'
                            }
                        }
                    }
                }
                stage('downgrade to major version without encryption') {
                    steps {
                        script {
                            if (env.PREV_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade", parameters: [
                                 string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PREV_MAJ_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "NONE"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped downgrade to major version'
                            }
                        }
                    }
                }
                stage('downgrade to major version with vault encryption') {
                    steps {
                        script {
                            if (env.PREV_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade", parameters: [
                                 string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PREV_MAJ_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "VAULT"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped downgrade to major version'
                            }
                        }
                    }
                }
            }
        }
    }
}
