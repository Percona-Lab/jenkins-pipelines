// Helpers for ppg/ppg-release-watcher.groovy and ppg/ppg-release-qa.groovy.
//
// The watcher state (VERSIONS.yml) lives on its own branch of ppg-testing so
// every change is a commit: who launched what, what passed, what failed.
// Both jobs write it; commitState() re-runs its body on a fresh checkout when
// a push races with the other job, so no update is ever lost or merged by hand.

import groovy.transform.Field

@Field String TOOL_REPO  = 'https://github.com/Percona-QA/ppg-testing.git'
@Field String STATE_REPO = 'https://github.com/Percona-QA/ppg-testing.git'
// Same token other jobs use to push to Percona-QA repos (e.g. the VERSIONS
// file in Percona-QA/package-testing). Its GitHub user needs write access to
// Percona-QA/ppg-testing.
@Field String GIT_TOKEN_CREDS = 'GITHUB_API_TOKEN'

// Git auth via an HTTP header on each network command, so the token is never
// written to .git/config in the workspace (unlike a token-in-URL clone).
// The header value is built in the shell from $TOKEN, never by Groovy.
private String gitAuth() {
    return 'git -c http.https://github.com/.extraheader="AUTHORIZATION: basic $(printf \'x-access-token:%s\' "$TOKEN" | base64 -w0)"'
}

private void withGit(Closure body) {
    withCredentials([string(credentialsId: GIT_TOKEN_CREDS, variable: 'TOKEN')]) {
        body()
    }
}

// Check out ppg-testing (tool code + config) into ./rw-tool and set up a venv.
def setupTool(String testingBranch) {
    dir('rw-tool') {
        git poll: false, changelog: false, branch: testingBranch, url: TOOL_REPO
    }
    sh '''
        set -e
        if [ ! -x .rw-venv/bin/python ]; then
            python3 -m venv .rw-venv
            .rw-venv/bin/pip install -q --upgrade pip
        fi
        .rw-venv/bin/pip install -q pyyaml zstandard
    '''
}

// Fresh checkout of the state branch into ./rw-state. If the branch does not
// exist yet it is started locally (orphan) and appears on GitHub with the
// first push of VERSIONS.yml.
def checkoutState(String branch) {
    withGit {
        sh """
            set -e
            rm -rf rw-state && mkdir rw-state && cd rw-state
            git init -q
            git remote add origin ${STATE_REPO}
            git config user.name  'ppg-release-watch'
            git config user.email 'noreply@percona.com'
            if ${gitAuth()} ls-remote --exit-code --heads origin ${branch} >/dev/null; then
                ${gitAuth()} fetch -q --depth 50 origin ${branch}
                git checkout -q -B ${branch} FETCH_HEAD
            else
                echo "state branch ${branch} does not exist yet, starting it"
                git checkout -q --orphan ${branch}
            fi
        """
    }
}

// Run tools/release_watch.py with the shared state, cache and config.
def run(String args) {
    sh """
        ${env.WORKSPACE}/.rw-venv/bin/python ${env.WORKSPACE}/rw-tool/tools/release_watch.py \\
            --config ${env.WORKSPACE}/rw-tool/release_watch/config.yml \\
            --state ${env.WORKSPACE}/rw-state/VERSIONS.yml \\
            --cache ${env.WORKSPACE}/.rw-cache \\
            ${args}
    """
}

// Run body (which changes rw-state/VERSIONS.yml), commit and push. On a push
// race, reset to the remote and run body again. Returns true if pushed.
def commitState(String branch, String message, Closure body) {
    for (int i = 1; i <= 5; i++) {
        body()
        def status = 1
        withGit {
            status = sh(returnStatus: true, script: """
                set -e
                cd rw-state
                git add -A
                if git diff --cached --quiet; then echo 'state unchanged'; exit 0; fi
                git commit -q -m "${message.replace('"', '\\"')}"
                ${gitAuth()} push -q origin HEAD:${branch}
            """)
        }
        if (status == 0) {
            return true
        }
        echo "state push failed (attempt ${i}/5), refreshing and retrying"
        sleep(time: 5 * i, unit: 'SECONDS')
        checkoutState(branch)
    }
    error('could not push release-watch state after 5 attempts')
}
