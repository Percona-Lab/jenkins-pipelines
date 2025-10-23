import groovy.json.JsonOutput

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void addComment(String COMMENT) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
        payload = [
            body: "${COMMENT}",
        ]
        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))

        sh '''
            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
            curl -X POST \
                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                -d @body.json \
                "https://api.github.com/repos/${REPO}/issues/${CHANGE_ID}/comments"
        '''
    }
}

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'PMM_BRANCH')
        string(
            defaultValue: '',
            description: 'URL for pmm-submodules repository PR',
            name: 'CHANGE_URL')
        string(
            defaultValue: '',
            description: 'ID for pmm-submodules repository PR',
            name: 'CHANGE_ID')
        string(
            // Starts with 'PR-', e.g., PR-2345
            defaultValue: '',
            description: 'Change Request Number for pmm-submodules repository PR',
            name: 'BRANCH_NAME')
        booleanParam(
            defaultValue: false,
            description: 'Build GSSAPI dynamic client tarballs for OL8 and OL9 (amd64)',
            name: 'GSSAPI_DYNAMIC_TARBALLS')
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: false,
                    branch: PMM_BRANCH,
                    url: 'http://github.com/Percona-Lab/pmm-submodules'

                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                sh '''
                    set -o errexit
                    git submodule update --init --jobs 10
                    git submodule status

                    ${PATH_TO_SCRIPTS}/build-submodules
                '''
                }
                script {
                    env.PMM_VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                    env.FB_COMMIT = sh(returnStdout: true, script: "cat fbCommitSha").trim()
                    env.SHORTENED_COMMIT = env.FB_COMMIT.substring(0, 7)
                }
                withCredentials([string(credentialsId: 'LAUNCHABLE_TOKEN', variable: 'LAUNCHABLE_TOKEN')]) {
                sh '''
                    set -o errexit
                    pip3 install --user --upgrade launchable~=1.0 || true
                    launchable verify || true

                    echo "$(pwd)"
                    echo "$(git status)" || true

                    launchable record build --name "pmm3-submodules-${PMM_VERSION}-${FB_COMMIT}"
                '''
                }
                stash includes: 'apiBranch', name: 'apiBranch'
                stash includes: 'apiURL', name: 'apiURL'
                stash includes: 'pmmQABranch', name: 'pmmQABranch'
                stash includes: 'apiCommitSha', name: 'apiCommitSha'
                stash includes: 'pmmQACommitSha', name: 'pmmQACommitSha'
                stash includes: 'pmmUITestBranch', name: 'pmmUITestBranch'
                stash includes: 'pmmUITestsCommitSha', name: 'pmmUITestsCommitSha'
                stash includes: 'fbCommitSha', name: 'fbCommitSha'
                slackSend channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: v3 build started, URL: ${BUILD_URL}"
            }
        }
    }
}
