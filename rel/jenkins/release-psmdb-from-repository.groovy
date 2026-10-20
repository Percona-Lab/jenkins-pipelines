library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Server, tools and mongosh builds, in the order they are pushed: the server goes first,
// so the gates of the other two can pass in the same run.
// gate: none = push as is, meta = only where the server is already in that repo/component,
// tools = the same and the server must not pin the tools version any more.
// The scripts that run on repo.ci are in resources/psmdb/release/.
def buildsToPush() {
    def all = [
        [kind: 'server', path: params.PSMDB_PATH, gate: 'none'],
        [kind: 'tools', path: params.TOOLS_PATH, gate: 'tools'],
        [kind: 'mongosh', path: params.MONGOSH_PATH, gate: 'meta'],
    ]
    def builds = []
    for (b in all) {
        if (b.path?.trim()) {
            builds << [kind: b.kind, path: b.path.trim().replaceAll('/+$', ''), gate: b.gate]
        }
    }
    return builds
}

// Runs a script from resources/psmdb/release/ on repo.ci and returns its exit code.
// vars become exported variables there; the script gets them first, then its own text,
// over ssh's stdin, so SIGN_PASSWORD never lands in a file or an argument list.
def runOnRepoCi(String script, Map vars) {
    writeFile file: script, text: libraryResource("psmdb/release/${script}")
    def exports = ["SCRIPT=${script}", "REMOTE_VARS=SIGN_PASSWORD ${vars.keySet().join(' ')}"]
    for (e in vars) {
        exports << "${e.key}=${e.value}"
    }
    def rc = 0
    withCredentials([
        string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD'),
        sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')
    ]) {
        withEnv(exports) {
            rc = sh(returnStatus: true, script: '''#!/bin/bash
                set +x
                set -o errexit
                { for v in ${REMOTE_VARS}; do printf 'export %s=%q\\n' "${v}" "${!v}"; done; cat "${SCRIPT}"; } \\
                    | ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "${KEY_PATH}" "${USER}@repo.ci.percona.com" bash -s
            ''')
        }
    }
    sh "rm -f ${script}"
    return rc
}

// Seconds from now to START_AT (UTC). It needs a node only for the second this takes,
// the waiting itself is done without one.
def secondsUntil(String startAt) {
    def secs = 0
    node('jenkins') {
        withEnv(["START_AT=${startAt}"]) {
            secs = sh(returnStdout: true, script: '''#!/bin/bash
                set -o errexit
                echo $(( $(date -u -d "${START_AT} UTC" +%s) - $(date +%s) ))
            ''').trim().toInteger()
        }
    }
    return secs
}

pipeline {
    // the stages that need a node ask for it themselves, so waiting for START_AT holds no executor
    agent none
    parameters {
        string(
            defaultValue: '',
            description: 'Server build, e.g. experimental/BUILDS/percona-server-mongodb-8.0/percona-server-mongodb-8.0.12-4/release-8.0.12-4/7f5db1b/32. Empty = do not push',
            name: 'PSMDB_PATH')
        string(
            defaultValue: '',
            description: 'mongo-tools build, same form as PSMDB_PATH. Pushed only where the server is already in the repo and does not pin the tools version. Empty = do not push',
            name: 'TOOLS_PATH')
        string(
            defaultValue: '',
            description: 'mongosh build, e.g. experimental/BUILDS/percona-mongodb-mongosh/percona-mongodb-mongosh-2.10.0/v2.10.0/0bed8dc0/44. Pushed only where the server is already in the repo. Empty = do not push',
            name: 'MONGOSH_PATH')
        string(
            defaultValue: '',
            description: 'Space-separated PSMDB repos to push to, e.g. psmdb-80 psmdb-83. Tools and mongosh are checked against the server in each of them separately',
            name: 'REPOSITORIES')
        choice(
            choices: 'TESTING\nRELEASE\nEXPERIMENTAL\nLABORATORY',
            description: 'repo component to push to',
            name: 'COMPONENT')
        choice(
            choices: 'YES\nNO',
            description: 'Push RHEL 10 packages',
            name: 'PUSHRHEL10')
        choice(
            choices: 'NO\nYES',
            description: 'Enable if focal packages have to be pushed',
            name: 'PUSHFOCAL')
        choice(
            choices: 'YES\nNO',
            description: 'Push trixie packages',
            name: 'PUSHTRIXIE')
        choice(
            choices: 'YES\nNO',
            description: 'Push resolute packages',
            name: 'PUSHRESOLUTE')
        string(
            defaultValue: '',
            description: 'Optional start time in UTC, YYYY-MM-DD HH:MM, e.g. 2026-10-21 04:00. The build waits for it without holding an executor, then pushes. Empty = now. The gates are checked when it starts',
            name: 'START_AT')
        booleanParam(name: 'REMOVE_BEFORE_PUSH', defaultValue: false, description: 'replace DEB packages of the same version that are already in the repo, not allowed for RELEASE')
        booleanParam(name: 'REMOVE_LOCKFILE', defaultValue: false, description: 'remove the reprepro lockfile after an unsuccessful DEB push')
        booleanParam(name: 'SKIP_RPM_PUSH', defaultValue: false, description: 'Skip push to RPM repository')
        booleanParam(name: 'SKIP_DEB_PUSH', defaultValue: false, description: 'Skip push to DEB repository')
        booleanParam(name: 'SKIP_REPO_SYNC', defaultValue: false, description: 'Skip sync repos to production')
        booleanParam(name: 'SKIP_PACKAGES_SYNC', defaultValue: false, description: 'Skip sync packages to production download and the crawl (RELEASE only). For tools and mongosh only what passed the repo gate is published there')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
    }
    stages {
        stage('Check parameters') {
            steps {
                script {
                    def builds = buildsToPush()
                    if (!builds) {
                        error('give at least one of PSMDB_PATH, TOOLS_PATH, MONGOSH_PATH')
                    }
                    for (b in builds) {
                        if (!(b.path ==~ '^[A-Za-z0-9][A-Za-z0-9._+/-]*$') || b.path.contains('..')) {
                            error("bad ${b.kind} path: ${b.path}")
                        }
                    }
                    def repos = params.REPOSITORIES.tokenize(' ,')
                    if (!repos) {
                        error('REPOSITORIES is empty')
                    }
                    for (repo in repos) {
                        if (!(repo ==~ '^psmdb-[0-9][0-9a-z.]*$')) {
                            error("bad repository: ${repo}")
                        }
                    }
                    if (params.COMPONENT == 'RELEASE' && params.REMOVE_BEFORE_PUSH) {
                        error('it is not allowed to remove packages from RELEASE repository')
                    }
                    if (params.SKIP_RPM_PUSH && params.SKIP_DEB_PUSH) {
                        error('both pushes are skipped, nothing to do')
                    }
                    def startAt = params.START_AT.trim()
                    if (startAt && !(startAt ==~ '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$')) {
                        error("START_AT must look like 2026-10-21 04:00 (UTC), got: ${startAt}")
                    }
                    currentBuild.description = "${repos.join(' ')}/${params.COMPONENT}: ${builds.collect { it.kind }.join(', ')}" +
                        (startAt ? ", starts ${startAt} UTC" : '')
                }
            }
        }
        stage('Wait for START_AT') {
            when {
                expression { return params.START_AT.trim() }
            }
            steps {
                script {
                    def secs = secondsUntil(params.START_AT.trim())
                    // a date in the past is most likely a typo, and it would push at once
                    if (secs < -900) {
                        error("START_AT ${params.START_AT} UTC was ${(-secs).intdiv(60)} minutes ago")
                    }
                    if (secs > 7 * 24 * 3600) {
                        error("START_AT ${params.START_AT} UTC is more than 7 days ahead")
                    }
                    if (secs > 0) {
                        echo "Waiting ${secs} seconds for ${params.START_AT} UTC"
                        sleep time: secs, unit: 'SECONDS'
                    }
                }
            }
        }
        stage('Push to repositories') {
            agent {
                label 'jenkins'
            }
            steps {
                script {
                    def builds = buildsToPush()
                    def repos = params.REPOSITORIES.tokenize(' ,')
                    def report = []
                    def touched = []
                    def results = [:]
                    for (String repo : repos) {
                        for (b in builds) {
                            def rc = runOnRepoCi('push-to-repo.sh', [
                                REPO: repo,
                                COMPONENT: params.COMPONENT,
                                PATH_TO_BUILD: b.path,
                                GATE: b.gate,
                                RUN_ID: "build-${env.BUILD_NUMBER}",
                                PUSHRHEL10: params.PUSHRHEL10,
                                PUSHFOCAL: params.PUSHFOCAL,
                                PUSHTRIXIE: params.PUSHTRIXIE,
                                PUSHRESOLUTE: params.PUSHRESOLUTE,
                                REMOVE_BEFORE_PUSH: params.REMOVE_BEFORE_PUSH,
                                REMOVE_LOCKFILE: params.REMOVE_LOCKFILE,
                                SKIP_RPM_PUSH: params.SKIP_RPM_PUSH,
                                SKIP_DEB_PUSH: params.SKIP_DEB_PUSH,
                            ])
                            // 42 and 43 are the script's own codes for held back / nothing pushed
                            def result = (rc == 0) ? 'pushed' : (rc == 42) ? 'partly held back' : (rc == 43) ? 'held back' : null
                            if (result == null) {
                                error("push of ${b.kind} to ${repo}/${params.COMPONENT} failed, rc=${rc}")
                            }
                            if (rc != 43) {
                                touched << repo
                            }
                            results[repo + '/' + b.kind] = rc
                            report << "${repo}/${params.COMPONENT} ${b.kind}: ${result}"
                        }
                    }
                    env.TOUCHED_REPOS = touched.unique().join(' ')
                    echo "Result per repo:\n${report.join('\n')}"

                    // a gated build held back everywhere is a failure, held back somewhere is unstable.
                    // That is decided in the last stage, so what did get pushed is still synced.
                    def verdict = ''
                    for (b in builds) {
                        if (b.gate == 'none') {
                            continue
                        }
                        def codes = repos.collect { results[it + '/' + b.kind] }
                        if (codes.every { it == 43 }) {
                            verdict = "error: ${b.kind} was not pushed anywhere, publish the PSMDB build first"
                            break
                        }
                        if (codes.any { it != 0 }) {
                            verdict = "unstable: the gate held ${b.kind} back in some repos, see the SKIP lines"
                        }
                    }
                    env.GATE_VERDICT = verdict
                }
            }
        }
        stage('Sync repos to production') {
            agent {
                label 'jenkins'
            }
            when {
                beforeAgent true
                expression { return !params.SKIP_REPO_SYNC && env.TOUCHED_REPOS }
            }
            steps {
                script {
                    for (repo in env.TOUCHED_REPOS.tokenize(' ')) {
                        def rc = runOnRepoCi('sync-repo-to-prod.sh', [REPO: repo, COMPONENT: params.COMPONENT])
                        if (rc != 0) {
                            error("sync of ${repo} to production failed, rc=${rc}")
                        }
                    }
                }
            }
        }
        stage('Sync packages to production download') {
            agent {
                label 'jenkins'
            }
            when {
                beforeAgent true
                expression { return params.COMPONENT == 'RELEASE' && !params.SKIP_PACKAGES_SYNC }
            }
            steps {
                script {
                    for (b in buildsToPush()) {
                        def rc = runOnRepoCi('sync-downloads.sh', [PATH_TO_BUILD: b.path, GATED: b.gate != 'none', RUN_ID: "build-${env.BUILD_NUMBER}"])
                        if (rc != 0) {
                            error("sync of ${b.path} to downloads failed, rc=${rc}")
                        }
                    }
                }
            }
        }
        stage('Refresh downloads area') {
            agent {
                label 'jenkins'
            }
            when {
                beforeAgent true
                expression { return params.COMPONENT == 'RELEASE' && !params.SKIP_PACKAGES_SYNC }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''#!/bin/bash
                        set -o errexit
                        CRAWL_RESPONSE=$(ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "${KEY_PATH}" "${USER}@repo.ci.percona.com" curl -sk https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory)
                        echo "Crawl response: ${CRAWL_RESPONSE}"
                        if ! grep -qE '"status":"(running|queued)"' <<< "${CRAWL_RESPONSE}"; then
                            echo "ERROR: crawl_directory did not return running or queued status"
                            exit 1
                        fi
                    '''
                }
            }
        }
        stage('Gate result') {
            when {
                expression { return env.GATE_VERDICT }
            }
            steps {
                script {
                    if (env.GATE_VERDICT.startsWith('error: ')) {
                        error(env.GATE_VERDICT.substring(7))
                    }
                    unstable(env.GATE_VERDICT.substring(10))
                }
            }
        }
    }
}
