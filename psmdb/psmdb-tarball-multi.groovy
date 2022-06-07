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
        string(
            defaultValue: '',
            description: 'Tarball for tests',
            name: 'TARBALL'
        )
        string(
            defaultValue: '',
            description: 'Tarball with previous minor PSMDB version for upgrade tests (leave blank to skip)',
            name: 'PREV_MIN_TARBALL'
        )
        string(
            defaultValue: '',
            description: 'Tarball with previous major PSMDB version for upgrade tests (leave blank to skip)',
            name: 'PREV_MAJ_TARBALL'
        )
        string(
            defaultValue: '',
            description: 'Tarball with next major PSMDB version for upgrade tests (leave blank to skip)',
            name: 'NEXT_MAJ_TARBALL'
        )
        choice(
            name: 'INSTANCE_TYPE',
            description: 'Ec2 instance type',
            choices: [
                't2.large',
                't2.medium',
                't2.micro',
                't2.xlarge'
            ]
        )
        string(
            defaultValue: 'main',
            description: 'base Branch for upgrade test',
            name: 'TESTING_BRANCH'
        )
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
                            if ((env.PREV_MIN_TARBALL == '') && (env.PREV_MAJ_TARBALL == '') && (env.NEXT_MAJ_TARBALL == '')) {
                                build job: 'psmdb-tarball-all-os', parameters: [
                                string(name: 'INSTANCE_TYPE', value: "${env.INSTANCE_TYPE}"),
                                string(name: 'OLD_TARBALL', value: "${env.TARBALL}"),
                                string(name: 'NEW_TARBALL', value: ""),
                                string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                ]
                            }
                        }
                    }
                }
                stage('upgrade from minor version') {
                    steps {
                        script {
                            if (env.PREV_MIN_TARBALL != '') {
                                 build job: "psmdb-tarball-all-os", parameters: [
                                 string(name: 'INSTANCE_TYPE', value: "${env.INSTANCE_TYPE}"),
                                 string(name: 'NEW_TARBALL', value: "${env.TARBALL}"),
                                 string(name: 'OLD_TARBALL', value: "${env.PREV_MIN_TARBALL}"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                             }
                             else {
                                  echo 'skipped upgrade from minor version'
                             }
                        }
                    }
                }
                stage('downgrade to minor version') {
                    steps {
                        script {
                            if (env.PREV_MIN_TARBALL != '') {
                                 build job: "psmdb-tarball-all-os", parameters: [
                                 string(name: 'INSTANCE_TYPE', value: "${env.INSTANCE_TYPE}"),
                                 string(name: 'NEW_TARBALL', value: "${env.PREV_MIN_TARBALL}"),
                                 string(name: 'OLD_TARBALL', value: "${env.TARBALL}"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                             }
                             else {
                                  echo 'skipped downgrade to minor version'
                             }
                        }
                    }
                }
                stage('upgrade from major version') {
                    steps {
                        script {
                            if (env.PREV_MAJ_TARBALL != '') {
                                 build job: "psmdb-tarball-all-os", parameters: [
                                 string(name: 'INSTANCE_TYPE', value: "${env.INSTANCE_TYPE}"),
                                 string(name: 'NEW_TARBALL', value: "${env.TARBALL}"),
                                 string(name: 'OLD_TARBALL', value: "${env.PREV_MAJ_TARBALL}"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                             }
                             else {
                                  echo 'skipped upgrade from major version'
                             }
                        }
                    }
                }
                stage('downgrade to major version') {
                    steps {
                        script {
                            if (env.PREV_MAJ_TARBALL != '') {
                                 build job: "psmdb-tarball-all-os", parameters: [
                                 string(name: 'INSTANCE_TYPE', value: "${env.INSTANCE_TYPE}"),
                                 string(name: 'NEW_TARBALL', value: "${env.PREV_MAJ_TARBALL}"),
                                 string(name: 'OLD_TARBALL', value: "${env.TARBALL}"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                             }
                             else {
                                  echo 'skipped downgrade to major version'
                             }
                        }
                    }
                }
                stage('upgrade to next major version') {
                    steps {
                        script {
                            if (env.NEXT_MAJ_TARBALL != '') {
                                 build job: "psmdb-tarball-all-os", parameters: [
                                 string(name: 'INSTANCE_TYPE', value: "${env.INSTANCE_TYPE}"),
                                 string(name: 'NEW_TARBALL', value: "${env.NEXT_MAJ_TARBALL}"),
                                 string(name: 'OLD_TARBALL', value: "${env.TARBALL}"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                             }
                             else {
                                  echo 'skipped upgrade to next major version'
                             }
                        }
                    }
                }
                stage('downgrade from next major version') {
                    steps {
                        script {
                            if (env.NEXT_MAJ_TARBALL != '') {
                                 build job: "psmdb-tarball-all-os", parameters: [
                                 string(name: 'INSTANCE_TYPE', value: "${env.INSTANCE_TYPE}"),
                                 string(name: 'NEW_TARBALL', value: "${env.TARBALL}"),
                                 string(name: 'OLD_TARBALL', value: "${env.NEXT_MAJ_TARBALL}"),
                                 string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                                 ]
                             }
                             else {
                                  echo 'skipped downgrade from next major version'
                             }
                        }
                    }
                }
            }
        }
    }
}
