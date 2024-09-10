pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm repository',
            name: 'PMM_BRANCH')
        choice(
            choices: ['no', 'yes'],
            description: "Build a Release Candidate?",
            name: 'RELEASE_CANDIDATE')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Prepare') {
            steps {
                checkout([$class: 'GitSCM', 
                          branches: [[name: "*/${PMM_BRANCH}"]],
                          extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]],
                          userRemoteConfigs: [[url: 'https://github.com/percona/pmm.git']]
                ])
            }
        }
        stage('Build Release Candidate Image') {
            when {
                expression { env.RELEASE_CANDIDATE == "yes" }
            }
            steps {
                dir("build") {
                    sh 'make pmm2-ami-el9-rc'
                }
                script {
                    env.AMI_ID = sh(script: "jq -r '.builds[-1].artifact_id' build/manifest.json | cut -d ':' -f2", returnStdout: true)
                }
            }
        }
        stage('Build Dev-Latest Image') {
            when {
                expression { env.RELEASE_CANDIDATE == "no" }
            }
            steps {
                dir("build") {
                    sh 'make pmm2-ami-el9'
                }
                script {
                    env.AMI_ID = sh(script: "jq -r '.builds[-1].artifact_id' build/manifest.json | cut -d ':' -f2", returnStdout: true)
                }
            }
        }
        stage('Run PMM AMI UI tests'){
            steps{
                script {
                    build job: 'pmm2-ami-test', parameters: [ string(name: 'AMI_ID', value: env.AMI_ID) ]
                }
            }
        }
    }
    post {
        success {
            script {
                if (params.RELEASE_CANDIDATE == "yes") {
                    currentBuild.description = "Release Candidate Build: ${env.AMI_ID}"
                    slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: ${BUILD_URL} Release Candidate build finished: ${env.AMI_ID}"
                } else {
                    currentBuild.description = "AMI Instance ID: ${env.AMI_ID}"
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build ${BUILD_URL} finished: ${env.AMI_ID}"
                }
            }
        }
        failure {
            echo "Pipeline failed"
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${BUILD_URL} failed"
        }
    }
}
