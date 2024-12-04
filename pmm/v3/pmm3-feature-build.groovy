import groovy.json.JsonOutput

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void addComment(String COMMENT) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
        payload = [body: "${COMMENT}"]
        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))

        sh '''
            PR_NUMBER=$(gh pr view --json number -q .number)
            curl -X POST \
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
            description: 'Tag/Branch for percona/pmm repository',
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
                // withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
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
                slackSend channel: '@alex', color: '#0000FF', message: "[${JOB_NAME}]: v3 build started, URL: ${BUILD_URL}"
            }
        }
        stage('Build the client') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                  withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {

                    sh '''
                        set -o errexit
                        cat <<-EOF > ci.yml
                      	deps:
                      		- name: pmm
                      		  branch: PMM-13487-build-pmm-locally
                    	EOF

                        export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

                        PR_NUMBER=$(git ls-remote origin 'refs/pull/*/head' | grep ${FB_COMMIT} | awk -F'/' '{print $3}' | tee PR_NUMBER)
                        export DOCKER_CLIENT_TAG=perconalab/pmm-client-fb:FB-${PR_NUMBER}-${FB_COMMIT:0:7}

                        ./build.sh --client-only
                    '''
                  }
                }
                // stash includes: '.modules/build/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                // archiveArtifacts '.modules/build/docker/CLIENT_TAG'
            }
        }
    }
    post {
        success {
            script {
                // unstash 'CLIENT_IMAGE'
                def IMAGE = sh(returnStdout: true, script: "cat .modules/build/docker/CLIENT_TAG").trim()
                slackSend channel: '@alex', color: '#00FF00', message: "[${JOB_NAME}]: build finished, image: ${IMAGE}, URL: ${BUILD_URL}"
                if (currentBuild.result.equals("SUCCESS")) {
                    addComment("Client image has been built: ${IMAGE}")
                }
            }
        }
        always {
            script {
                if (currentBuild.result != 'SUCCESS') {
                    slackSend channel: '@alex', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
                }
            }
        }
    }
}
