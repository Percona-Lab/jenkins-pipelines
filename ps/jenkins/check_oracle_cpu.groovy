library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def STATE = 'cpu-cves.json'
def DIFF = 'cpu-cves-diff.json'
def BUGS = 'cpu-bug-cve.json'
def SLACK = 'cpu-cves-slack.txt'
def SLACK_STATE = 'cpu-slack.json'
def SEED = 'cpu-cves-seed'
def NOTIFY_DIR = 'cpu-notify'
def DEGRADED = 'cpu-degraded.txt'
// Ten advisories, not five. A fix can land in a later tag for a CVE
// published in an older CPU or CSPU, and the stored JSON stays small.
def ADVISORY_COUNT = '10'
// Temporary test channel. Jenkins CI is a member. Put #eol-dev back after the test.
def SLACK_CHANNEL = '#marcinbabij-notifications'

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        booleanParam(
            name: 'IGNORE_STATE',
            defaultValue: false,
            description: 'Do not read the saved CVE state. This run is treated as the first one.'
        )
        choice(
            name: 'NOTIFY',
            choices: ['none', 'latest', 'all'],
            description: 'Also post unchanged advisories. latest is the newest report only. all posts every watched report, each in its own Slack thread.'
        )
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Check advisories') {
            steps {
                checkout scm
                sh "rm -rf ${STATE} ${DIFF} ${BUGS} ${SLACK} ${SLACK_STATE} ${SEED} ${NOTIFY_DIR} ${DEGRADED}"
                script {
                    // SUCCESS or UNSTABLE, not NOT_BUILT or FAILURE. A degraded
                    // poll is UNSTABLE and still holds the advisory state.
                    // IGNORE_STATE skips the CVE baseline only.
                    def filter = params.IGNORE_STATE ? SLACK_STATE : "${STATE},${SLACK_STATE}"
                    copyArtifacts(
                        projectName: env.JOB_NAME,
                        selector: [$class: 'StatusBuildSelector', stable: false],
                        filter: filter,
                        optional: true,
                        flatten: true,
                        fingerprintArtifacts: false
                    )
                }
                script {
                    def notifyMode = params.NOTIFY ?: 'none'
                    if (!(notifyMode in ['none', 'latest', 'all'])) {
                        notifyMode = 'none'
                    }
                    env.CPU_NOTIFY_MODE = notifyMode
                }
                sh """
                    set -o errexit
                    python3 ps/jenkins/cpu_cves.py \\
                        --state ${STATE} \\
                        --diff ${DIFF} \\
                        --slack ${SLACK} \\
                        --seed-marker ${SEED} \\
                        --bugs ${BUGS} \\
                        --notify-dir ${NOTIFY_DIR} \\
                        --notify ${env.CPU_NOTIFY_MODE} \\
                        ${params.IGNORE_STATE ? '--ignore-state' : ''} \\
                        --count ${ADVISORY_COUNT}
                """
                script {
                    def order = fileExists("${NOTIFY_DIR}/order.txt") ? readFile("${NOTIFY_DIR}/order.txt").trim() : ''
                    env.CPU_DIFF = fileExists(DIFF) ? '1' : '0'
                    env.CPU_NOTIFY = order ? '1' : '0'
                    env.CPU_DEGRADED = '0'
                    if (fileExists(DEGRADED)) {
                        echo readFile(DEGRADED)
                        unstable('Oracle CPU collection degraded. Previous state kept for failed advisories.')
                        env.CPU_DEGRADED = '1'
                        def remembered = [STATE, BUGS].findAll { fileExists(it) }
                        if (remembered) {
                            archiveArtifacts artifacts: remembered.join(','), allowEmptyArchive: true
                        }
                    }
                }
            }
        }
        stage('Notify') {
            when {
                environment name: 'CPU_NOTIFY', value: '1'
            }
            steps {
                script {
                    if (env.CPU_DIFF == '1') {
                        archiveArtifacts artifacts: "${DIFF},${STATE},${BUGS}", allowEmptyArchive: false
                    }
                    def order = readFile("${NOTIFY_DIR}/order.txt").trim().split('\n')
                    def changed = readFile("${NOTIFY_DIR}/changed.txt").trim()
                    def changedSlugs = changed ? changed.split('\n') : []
                    for (def slug : order) {
                        if (!slug) {
                            continue
                        }
                        try {
                        def text = readFile("${NOTIFY_DIR}/${slug}.txt").trim()
                        def message = "[${JOB_NAME}]: Oracle CPU/CSPU CVE change\n${text}"
                        def threadId = ''
                        if (fileExists(SLACK_STATE)) {
                            threadId = sh(
                                script: """python3 -c 'import json; d=json.load(open("${SLACK_STATE}")); print(((d.get("threads") or {}).get("${slug}") or {}).get("threadId") or "")'""",
                                returnStdout: true
                            ).trim()
                        }
                        def target = threadId ? threadId : SLACK_CHANNEL
                        def response = slackSend(
                            botUser: true,
                            channel: target,
                            color: '#00FF00',
                            message: message
                        )
                        if (!threadId) {
                            if (!response?.threadId) {
                                error("slackSend did not return a thread id for ${slug}")
                            }
                            threadId = response.threadId
                            sh """python3 -c 'import json; from pathlib import Path; p=Path("${SLACK_STATE}"); data=json.loads(p.read_text()) if p.is_file() and p.stat().st_size else {}; threads=data.get("threads") or {}; threads["${slug}"]={"channelId":"${response.channelId}","ts":"${response.ts}","threadId":"${response.threadId}"}; data["threads"]=threads; p.write_text(json.dumps(data, indent=2)+"\\n")'"""
                            env.CPU_SLACK_SAVE = '1'
                            // Archive now. A later advisory must not be able to
                            // drop a thread id that Slack already accepted.
                            archiveArtifacts artifacts: SLACK_STATE, allowEmptyArchive: false
                        }
                        if (changedSlugs.contains(slug)) {
                            // slackUploadFile sometimes throws IllegalStateException:
                            // Response content has been already consumed, in
                            // SlackUploadFileRunner.uploadFile, after a non-200
                            // file POST. No open jenkinsci/slack-plugin issue
                            // describes that. Nearest: issue 1074. A later ps3
                            // build did attach the file, so retry, then fall
                            // back to the artifact links with no file.
                            def links = """Full list and diff: ${BUILD_URL}artifact/${DIFF}
Bug to CVE map: ${BUILD_URL}artifact/${BUGS}
State: ${BUILD_URL}artifact/${STATE}"""
                            def uploaded = false
                            catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                                retry(3) {
                                    slackUploadFile(
                                        channel: threadId,
                                        filePath: BUGS,
                                        initialComment: links
                                    )
                                }
                                uploaded = true
                            }
                            if (!uploaded) {
                                slackSend(
                                    botUser: true,
                                    channel: threadId,
                                    color: '#00FF00',
                                    message: links
                                )
                            }
                        }
                        } catch (Exception err) {
                            echo "WARNING cpu Slack failed for ${slug}: ${err}"
                            unstable("Slack delivery failed for ${slug}. Successful thread ids are kept.")
                        }
                    }
                    def remembered = [STATE, BUGS, SLACK_STATE, DIFF].findAll { fileExists(it) }
                    if (remembered) {
                        archiveArtifacts artifacts: remembered.join(','), allowEmptyArchive: true
                    }
                }
            }
        }
        stage('Mark unchanged') {
            when {
                allOf {
                    environment name: 'CPU_NOTIFY', value: '0'
                    environment name: 'CPU_DEGRADED', value: '0'
                }
            }
            steps {
                script {
                    currentBuild.result = 'NOT_BUILT'
                }
            }
        }
    }
    post {
        success {
            script {
                // Last step of a real SUCCESS only. NOT_BUILT and FAILURE
                // do not keep the build. A diff is the artifact worth keeping.
                if (env.CPU_DIFF == '1') {
                    currentBuild.setKeepLog(true)
                }
            }
        }
    }
}
