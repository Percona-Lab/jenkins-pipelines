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
            defaultValue: '6.0.4',
            description: 'next major PSMDB version for upgrade tests (leave blank to skip)',
            name: 'NEXT_MAJ_PSMDB_VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'base Branch for upgrade test',
            name: 'TESTING_BRANCH')
         choice(
            name: 'functionaltests',
            choices: ['no','yes'],
            description: 'run functional tests')
         choice(
            name: 'no_encryption',
            choices: ['no','yes'],
            description: 'check upgrade without encryption')
    }
    options {
          withCredentials(moleculePbmJenkinsCreds())
          disableConcurrentBuilds()
    }
    stages {
        stage ('Test All') {
            parallel {
                stage ('functional tests') {
                    steps {
                        script {
                            if (params.integrationtests == "yes") {
                                 build job: 'psmdb-parallel', parameters: [
                                 string(name: 'REPO', value: "${env.REPO}"),
                                 string(name: 'PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                ]
                             }
                             else {
                                  echo 'skipped functional tests'
                             }
                        }

                    }
                }
                stage('upgrade from minor version without encryption') {
                    steps {
                        script {
                            if ((env.PREV_MIN_PSMDB_VERSION != '') && (params.no_encryption == "yes")) {
                                 build job: "psmdb-upgrade-parallel", parameters: [
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
                stage('upgrade from minor version with vault encryption') {
                    steps {
                        script {
                            if (env.PREV_MIN_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
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
                stage('upgrade from minor version with KMIP encryption') {
                    steps {
                        script {
                            if (env.PREV_MIN_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PREV_MIN_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "KMIP"),
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
                stage('upgrade from prev major version without encryption') {
                    steps {
                        script {
                            if ((env.PREV_MIN_PSMDB_VERSION != '') && (params.no_encryption == "yes")) {
                                 build job: "psmdb-upgrade-parallel", parameters: [
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
                stage('upgrade from prev major version with vault encryption') {
                    steps {
                        script {
                            if (env.PREV_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
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
                stage('upgrade from prev major version with KMIP encryption') {
                    steps {
                        script {
                            if (env.PREV_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PREV_MAJ_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "KMIP"),
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
                stage('upgrade to next major version without encryption') {
                    steps {
                        script {
                            if ((env.PREV_MIN_PSMDB_VERSION != '') && (params.no_encryption == "yes")) {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.NEXT_MAJ_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "NONE"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped upgrade to major version'
                            }
                        }
                    }
                }
                stage('upgrade to next major version with vault encryption') {
                    steps {
                        script {
                            if (env.NEXT_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.NEXT_MAJ_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "VAULT"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped upgrade to major version'
                            }
                        }
                    }
                }
                stage('upgrade to next major version with KMIP encryption') {
                    steps {
                        script {
                            if (env.NEXT_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.NEXT_MAJ_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "KMIP"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped upgrade to major version'
                            }
                        }
                    }
                }
                stage('downgrade to minor version without encryption') {
                    steps {
                        script {
                            if ((env.PREV_MIN_PSMDB_VERSION != '') && (params.no_encryption == "yes")) {
                                 build job: "psmdb-upgrade-parallel", parameters: [
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
                                 build job: "psmdb-upgrade-parallel", parameters: [
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
                stage('downgrade to minor version with KMIP encryption') {
                    steps {
                        script {
                            if (env.PREV_MIN_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PREV_MIN_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "KMIP"),
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
                stage('downgrade to prev major version without encryption') {
                    steps {
                        script {
                            if ((env.PREV_MIN_PSMDB_VERSION != '') && (params.no_encryption == "yes")) {
                                 build job: "psmdb-upgrade-parallel", parameters: [
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
                stage('downgrade to prev major version with vault encryption') {
                    steps {
                        script {
                            if (env.PREV_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
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
                stage('downgrade to prev major version with KMIP encryption') {
                    steps {
                        script {
                            if (env.PREV_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "release"),
                                 string(name: 'FROM_REPO', value: "${env.REPO}"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PREV_MAJ_PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "KMIP"),
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
                stage('downgrade from next major version without encryption') {
                    steps {
                        script {
                            if ((env.PREV_MIN_PSMDB_VERSION != '') && (params.no_encryption == "yes")) {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.NEXT_MAJ_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "NONE"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped downgrade from major version'
                            }
                        }
                    }
                }
                stage('downgrade from next major version with vault encryption') {
                    steps {
                        script {
                            if (env.NEXT_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.NEXT_MAJ_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "VAULT"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped downgrade from major version'
                            }
                        }
                    }
                }
                stage('downgrade from next major version with KMIP encryption') {
                    steps {
                        script {
                            if (env.NEXT_MAJ_PSMDB_VERSION != '') {
                                 build job: "psmdb-upgrade-parallel", parameters: [
                                 string(name: 'TO_REPO', value: "${env.REPO}"),
                                 string(name: 'FROM_REPO', value: "release"),
                                 string(name: 'TO_PSMDB_VERSION', value: "${env.PSMDB_VERSION}"),
                                 string(name: 'FROM_PSMDB_VERSION', value: "${env.NEXT_MAJ_PSMDB_VERSION}"),
                                 string(name: 'ENCRYPTION', value: "KMIP"),
                                 string(name: 'CIPHER', value: "AES256-CBC"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                            }
                            else {
                                 echo 'skipped downgrade from major version'
                            }
                        }
                    }
                }
            }
        }
    }
}
