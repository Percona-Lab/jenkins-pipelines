import groovy.json.JsonOutput

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void addIssueComment(String PR_NUMBER, String COMMENT) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
        writeFile(file: 'body.json', text: JsonOutput.toJson([ body: "${COMMENT}" ]))

        sh '''
            curl -s -X POST \
                -H "Accept: application/vnd.github+json" \
                -H "X-GitHub-Api-Version: 2022-11-28" \
                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                -d @body.json \
                "https://api.github.com/repos/percona/pmm/issues/${PR_NUMBER}/comments"
        '''
    }
}

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: 'PMM-13487-build-pmm-locally',
            description: 'A branch name in percona/pmm repository',
            name: 'PMM_BRANCH')
    }
    environment {
        PATH_TO_SCRIPTS = 'build/scripts'
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: false,
                    changelog: false,
                    branch: PMM_BRANCH,
                    url: 'http://github.com/percona/pmm'

                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                    '''
                }                    
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        set -o errexit
                        export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

                        ./build.sh --init
                    '''
                }
                script {
                    env.PMM_VERSION = sh(returnStdout: true, script: "cat .modules/VERSION").trim()
                    env.FB_COMMIT = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
                // stash includes: 'apiBranch', name: 'apiBranch'
                // stash includes: 'apiURL', name: 'apiURL'
                // stash includes: 'pmmQABranch', name: 'pmmQABranch'
                // stash includes: 'apiCommitSha', name: 'apiCommitSha'
                // stash includes: 'pmmQACommitSha', name: 'pmmQACommitSha'
                // stash includes: 'pmmUITestBranch', name: 'pmmUITestBranch'
                // stash includes: 'pmmUITestsCommitSha', name: 'pmmUITestsCommitSha'
                // stash includes: 'fbCommitSha', name: 'fbCommitSha'
            }
        }
        stage('Build PMM') {
            steps {
                withCredentials([
                  aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
                  sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')
                ]) {
                    sh '''
                        set -o errexit
                        export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

                        ./build.sh

                        docker push $(cat .modules/build/docker/CLIENT_TAG)
                        docker push $(cat .modules/build/docker/TAG)
                    '''
                }
                script {
                    if (!fileExists('.modules/build/docker/CLIENT_TAG')) {
                      error "Client image could not be built."
                    }
                    if (!fileExists('.modules/build/docker/TAG')) {
                      error "Server image could not be built."
                    }
                    env.CLIENT_URL = sh(returnStdout: true, script: "cat .modules/build/S3_TARBALL_URL")
                    env.PR_NUMBER = sh(returnStdout: true, script: "cat .modules/build/PR_NUMBER")
                    env.CLIENT_IMAGE = sh(returnStdout: true, script: "cat .modules/build/docker/CLIENT_TAG")
                    env.SERVER_IMAGE = sh(returnStdout: true, script: "cat .modules/build/docker/TAG")
                }
            }
        }
    }
    post {
        success {
            script {
                slackSend channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build finished, image: ${SERVER_IMAGE}, URL: ${BUILD_URL}"
                def STAGING_URL = "https://pmm.cd.percona.com/job/pmm3-aws-staging-start/parambuild/"
                def MESSAGE = "Server docker: ${SERVER_IMAGE}\nClient docker: ${CLIENT_IMAGE}\nClient tarball: ${CLIENT_URL}\n"
                MESSAGE += "Staging instance: ${STAGING_URL}?DOCKER_VERSION=${SERVER_IMAGE}&CLIENT_VERSION=${CLIENT_URL}"
                addIssueComment(env.PR_NUMBER, MESSAGE)
            }
        }
        failure {
            script {
                slackSend channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
            }
        }
    }
}
