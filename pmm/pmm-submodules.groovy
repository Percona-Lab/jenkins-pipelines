library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        choice(
            // default is el9
            choices: ['el9', 'el7'],
            description: 'Select the OS to build for',
            name: 'BUILD_OS')
        booleanParam(
            defaultValue: false,
            description: 'Build GSSAPI dynamic client tarballs for OL8 and OL9 (amd64)',
            name: 'GSSAPI_DYNAMIC_TARBALLS')
    }
    environment {
        PMM_VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
    }
    stages {
        stage('Trigger PMM2 Submodules pipeline') {
            when {
                expression {
                    env.PMM_VERSION =~ '^2.'
                }
            }
            steps {
                build job: 'pmm2-submodules', parameters: [
                    string(name: 'PMM_BRANCH', value: "${CHANGE_BRANCH}"),
                    string(name: 'CHANGE_URL', value: "${CHANGE_URL}"),
                    string(name: 'CHANGE_ID', value: "${CHANGE_ID}"),
                    string(name: 'BRANCH_NAME', value: "${BRANCH_NAME}"),
                    string(name: 'BUILD_OS', value: params.BUILD_OS),
                ]
            }
        }
        stage('Trigger PMM3 Submodules pipeline') {
            when {
                expression {
                    env.PMM_VERSION =~ '^3.'
                }
            }
            steps {
                build job: 'pmm3-submodules', parameters: [
                    string(name: 'PMM_BRANCH', value: "${CHANGE_BRANCH}"),
                    string(name: 'CHANGE_URL', value: "${CHANGE_URL}"),
                    string(name: 'CHANGE_ID', value: "${CHANGE_ID}"),
                    string(name: 'BRANCH_NAME', value: "${BRANCH_NAME}"),
                    booleanParam(name: 'GSSAPI_DYNAMIC_TARBALLS', value: params.GSSAPI_DYNAMIC_TARBALLS),
                ]
            }
        }
    }
    post {
        cleanup {
            script {
                // Read more why: https://stackoverflow.com/questions/57602575/required-context-class-hudson-filepath-is-missing-perhaps-you-forgot-to-surround
                if (getContext(hudson.FilePath)) {
                  deleteDir()
                }
            }
        }
    }    
}
