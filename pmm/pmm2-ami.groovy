void runPmm2AmiUITests(String AMI_ID) {
    stagingJob = build job: 'pmm2-ami-test', parameters: [
        string(name: 'AMI_ID', value: AMI_ID),
    ]
}

pipeline {
    environment {
        specName = 'PMM2-AMI'
    }
    agent {
        label 'awscli'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-images repository',
            name: 'GIT_BRANCH')
        choice(
            choices: ['no', 'yes'],
            description: "Build Release Candidate?",
            name: 'RELEASE_CANDIDATE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
                git poll: true, branch: GIT_BRANCH, url: "https://github.com/Percona-Lab/percona-images.git"
                sh """
                    make clean
                    make deps
                """
            }
        }

        stage('Build Image Release Candidate') {
            when {
                expression { env.RELEASE_CANDIDATE == "yes" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o pipefail
                        ~/bin/packer build \
                        -var 'pmm_client_repos=original testing' \
                        -var 'pmm_client_repo_name=percona-testing-x86_64' \
                        -var 'pmm2_server_repo=testing' \
                        -only amazon-ebs -color=false packer/pmm2.json \
                            | tee build.log
                    """
                }
                sh 'tail build.log | grep us-east-1 | cut -d " " -f 2 > IMAGE'
                stash includes: 'IMAGE', name: 'IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }
        stage('Build Image Dev-Latest') {
            when {
                expression { env.RELEASE_CANDIDATE == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o pipefail
                        ~/bin/packer build \
                        -var 'pmm_client_repos=original experimental' \
                        -var 'pmm_client_repo_name=percona-experimental-x86_64' \
                        -var 'pmm2_server_repo=experimental' \
                        -only amazon-ebs -color=false packer/pmm2.json \
                            | tee build.log
                    """
                }
                sh 'tail build.log | grep us-east-1 | cut -d " " -f 2 > IMAGE'
                stash includes: 'IMAGE', name: 'IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }
    }

    post {
        always {
            deleteDir()
        }
        success {
            script {
                unstash 'IMAGE'
                def IMAGE = sh(returnStdout: true, script: "cat IMAGE").trim()
                runPmm2AmiUITests(IMAGE)
                if ("${RELEASE_CANDIDATE}" == "yes")
                {
                    currentBuild.description = "Release Candidate Build"
                    slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${specName}]: ${BUILD_URL} Release Candidate build finished - ${IMAGE}"
                }
                else
                {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
                }
            }
        }
        failure {
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}
