void runPmm2AmiUITests(String AMI_ID) {
    stagingJob = build job: 'pmm2-ami-test', parameters: [
        string(name: 'AMI_ID', value: AMI_ID),
    ]
}

String AMI_ID = ''

pipeline {
    agent {
        label 'agent-amd64'
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
                          userRemoteConfigs: [[url: 'https://github.com/percona/pmm.git']]])
            }
        }

        stage('Build Image Release Candidate') {
            when {
                expression { env.RELEASE_CANDIDATE == "yes" }
            }
            steps {
                dir("build") {
                    sh 'make pmm2-ami-rc'
                }
                script {
                    AMI_ID = sh(script: 'jq -r \'.builds[-1].artifact_id\' manifest.json | cut -d ":" -f2', returnStdout: true)
                }
            }
        }
        stage('Build Image Dev-Latest') {
            when {
                expression { env.RELEASE_CANDIDATE == "no" }
            }
            steps {
                dir("build") {
                    sh 'make pmm2-ami'
                }
                script {
                    AMI_ID = sh(script: 'jq -r \'.builds[-1].artifact_id\' manifest.json | cut -d ":" -f2', returnStdout: true)
                }
            }
        }
    }
    post {
        success {
            script {
                runPmm2AmiUITests(AMI_ID)
                if ("${RELEASE_CANDIDATE}" == "yes")
                {
                    currentBuild.description = "Release Candidate Build: ${AMI_ID}"
                    slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${AMI_ID}]: ${BUILD_URL} Release Candidate build finished - ${AMI_ID}"
                }
                else
                {
                    currentBuild.description = AMI_ID
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${AMI_ID}]: build finished - ${AMI_ID}"
                }
                env.AMI_ID = AMI_ID // TODO use env everywhere
            }
        }
        failure {
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${AMI_ID}]: build failed"
        }
    }
}
