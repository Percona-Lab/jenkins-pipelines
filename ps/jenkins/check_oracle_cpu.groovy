library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def STATE = 'cpu-state.json'
def LEGACY_STATE = 'cpu-cves.json'
def LEGACY_SLACK = 'cpu-slack.json'
def BUGS = 'cpu-bug-cve.json'
def MANIFEST = 'cpu-notify.json'
def REPORT = 'cpu-run.json'
def STATUS = 'cpu-status.txt'
def POLL_RC = 'cpu-poll.rc'

def cpuThrowableText(err) {
    def sw = new StringWriter()
    def pw = new PrintWriter(sw)
    err.printStackTrace(pw)
    pw.flush()
    return sw.toString()
}

def cpuNote(Map event) {
    // reportPath is an argument. A method cannot see a script-local def.
    def reportPath = event.reportPath
    try {
        writeFile file: 'cpu-note.json', text: groovy.json.JsonOutput.toJson(event)
        sh "python3 ps/jenkins/cpu_cves.py note ${reportPath} cpu-note.json"
    } catch (Exception err) {
        if (err instanceof InterruptedException) {
            throw err
        }
        echo "WARNING cpu report note failed: ${err}"
    }
}

def cpuArchiveMapping(String reportPath, String bugArtifact, List names) {
    def remembered = names.findAll { fileExists(it) }
    if (!remembered || !remembered.contains(bugArtifact)) {
        return
    }
    def archived = remembered.join(',')
    archiveArtifacts artifacts: archived, allowEmptyArchive: true
    cpuNote([
        reportPath: reportPath,
        level: 'ok',
        outcome: 'published',
        area: 'archive',
        message: 'Bug to CVE map archived.',
    ])
}

def cpuReportDegraded(String reportPath) {
    if (!fileExists(reportPath)) {
        return false
    }
    // CPS drops a def that lives only inside try.
    def out = ''
    try {
        out = sh(
            script: """python3 -c 'import json
try:
    data = json.load(open("${reportPath}"))
except Exception:
    print("ERR")
else:
    print("1" if isinstance(data, dict) and data.get("degraded") else "0")'""",
            returnStdout: true
        ).trim()
    } catch (Exception err) {
        if (err instanceof InterruptedException) {
            throw err
        }
        echo "WARNING cpu run record is unreadable: ${err}"
        return false
    }
    if (!out || out.startsWith('ERR')) {
        echo 'WARNING cpu run record is unreadable.'
        return false
    }
    return out == '1'
}

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
            description: 'Reset CVE comparison for this run. Cached bug maps, pending Slack messages, and thread ids are kept. The newest advisory is reported as a first list.'
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
        // Production mode rejects copyArtifacts unless the source job names
        // the reader. This job copies its own last build. '*' also covers a
        // later job that reads the bug map.
        copyArtifactPermission('*')
    }
    stages {
        stage('Test') {
            steps {
                sh 'python3 -m unittest discover -s ps/jenkins/tests -t ps/jenkins'
            }
        }
        stage('Check advisories') {
            steps {
                sh "rm -rf ${STATE} ${LEGACY_STATE} ${LEGACY_SLACK} ${BUGS} ${MANIFEST} ${REPORT} ${STATUS} ${POLL_RC} cpu-description.txt cpu-note.json cpu-thread.json cpu-cves-diff.json cpu-notify cpu-degraded.txt cpu-publish cpu-events.jsonl cpu-event.json"
                script {
                    // SUCCESS or UNSTABLE. Every poll that had a mapping
                    // archived it, including an unchanged SUCCESS.
                    copyArtifacts(
                        projectName: env.JOB_NAME,
                        selector: [$class: 'StatusBuildSelector', stable: false],
                        filter: "${STATE},${LEGACY_STATE},${LEGACY_SLACK}",
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
                    set +e
                    python3 ps/jenkins/cpu_cves.py \\
                        --state ${STATE} \\
                        --bugs ${BUGS} \\
                        --notify-manifest ${MANIFEST} \\
                        --report ${REPORT} \\
                        --notify ${env.CPU_NOTIFY_MODE} \\
                        ${params.IGNORE_STATE ? '--ignore-state' : ''} \\
                        --count ${ADVISORY_COUNT}
                    echo \$? > ${POLL_RC}
                """
                script {
                    // Archive before the report is consulted. A partial
                    // cpu-run.json must not skip the mapping.
                    cpuArchiveMapping(REPORT, BUGS, [STATE, BUGS])
                    def pollRc = fileExists(POLL_RC) ? readFile(POLL_RC).trim() : '1'
                    if (pollRc != '0') {
                        error 'Oracle CPU poll produced no usable mapping.'
                    }
                    if (cpuReportDegraded(REPORT)) {
                        unstable('Oracle CPU collection degraded. Previous state kept for failed advisories.')
                    }
                    env.CPU_DIFF = '0'
                    def diffOut = ''
                    try {
                        diffOut = sh(
                            script: """python3 -c 'import json
data = json.load(open("${REPORT}"))
added = int(data.get("cve_added") or 0)
removed = int(data.get("cve_removed") or 0)
print("1" if added or removed else "0")'""",
                            returnStdout: true
                        ).trim()
                    } catch (Exception err) {
                        if (err instanceof InterruptedException) {
                            throw err
                        }
                        echo "WARNING cpu diff flag unreadable: ${err}"
                    }
                    if (diffOut == '1') {
                        env.CPU_DIFF = '1'
                    }
                }
            }
        }
        stage('Notify') {
            steps {
                script {
                    if (!fileExists(MANIFEST)) {
                        return
                    }
                    def parsed = new groovy.json.JsonSlurperClassic().parseText(readFile(MANIFEST))
                    def items = parsed.items ?: []
                    if (!items) {
                        return
                    }
                    def blockedSlugs = []
                    for (def item : items) {
                        def slug = item.slug ?: ''
                        def pendingId = item.pending_id ?: ''
                        def changed = item.changed ? true : false
                        def text = (item.text ?: '').trim()
                        if (!slug || !text) {
                            continue
                        }
                        if (blockedSlugs.contains(slug)) {
                            echo "WARNING cpu Slack skipped ${slug}; an older message for this advisory was not delivered."
                            cpuNote([
                                reportPath: REPORT,
                                level: 'warning',
                                outcome: 'failed',
                                area: 'slack',
                                slug: slug,
                                message: "Slack skipped ${slug}; an older message for this advisory was not delivered.",
                                fallback: 'message stays pending',
                                impact: 'it is sent after the older message succeeds',
                            ])
                            continue
                        }
                        def recorded = false
                        try {
                            def linkLines = []
                            linkLines << "Bug to CVE map: ${BUILD_URL}artifact/${BUGS}"
                            linkLines << "State: ${BUILD_URL}artifact/${STATE}"
                            def message = "[${JOB_NAME}]: Oracle CPU/CSPU CVE change\n${text}"
                            if (changed) {
                                message = "${message}\n\n${linkLines.join('\n')}"
                            }
                            def threadId = ''
                            if (fileExists(STATE)) {
                                def threadOut = ''
                                threadOut = sh(
                                    script: """python3 -c 'import json; d=json.load(open("${STATE}")); print(((d.get("threads") or {}).get("${slug}") or {}).get("threadId") or "")'""",
                                    returnStdout: true
                                ).trim()
                                threadId = threadOut
                            }
                            def target = threadId ? threadId : SLACK_CHANNEL
                            // failOnError true sets the build to FAILURE before
                            // throwing. A failed send returns null when
                            // failOnError is left false.
                            def response = null
                            def delivered = false
                            def deliveredOn = 0
                            def misses = []
                            for (attempt in [1, 2, 3]) {
                                response = slackSend(
                                    botUser: true,
                                    channel: target,
                                    color: '#00FF00',
                                    message: message,
                                    failOnError: false
                                )
                                if (response != null && (threadId || response.threadId)) {
                                    delivered = true
                                    deliveredOn = attempt
                                    break
                                }
                                misses << "attempt ${attempt}/3: slackSend returned null"
                                echo "WARNING cpu Slack attempt ${attempt}/3 failed for ${slug}: empty response"
                                response = null
                            }
                            if (!delivered) {
                                blockedSlugs.add(slug)
                                def failMessage = pendingId
                                    ? "Slack delivery failed for ${slug} after 3 attempts. Notification stays pending."
                                    : "Slack delivery failed for ${slug} after 3 attempts."
                                echo "WARNING cpu ${failMessage}"
                                cpuNote([
                                    reportPath: REPORT,
                                    level: 'warning',
                                    outcome: 'failed',
                                    area: 'slack',
                                    slug: slug,
                                    attempts: 3,
                                    message: failMessage,
                                    fallback: pendingId ? 'pending message kept' : 'not queued',
                                    impact: pendingId ? 'the next poll sends it again' : 'this unchanged post is not retried from pending',
                                    exception: misses.join('\n'),
                                ])
                                recorded = true
                                unstable(failMessage)
                            } else {
                                def ackCmd = "python3 ps/jenkins/cpu_cves.py ack ${STATE}"
                                if (!threadId) {
                                    writeFile file: 'cpu-thread.json', text: groovy.json.JsonOutput.toJson([
                                        channelId: response.channelId,
                                        ts: response.ts,
                                        threadId: response.threadId,
                                    ])
                                    ackCmd = "${ackCmd} --thread ${slug} cpu-thread.json"
                                    threadId = response.threadId
                                }
                                if (pendingId) {
                                    ackCmd = "${ackCmd} --pending '${pendingId}'"
                                }
                                if (pendingId || ackCmd.contains('--thread')) {
                                    sh ackCmd
                                    archiveArtifacts artifacts: STATE, allowEmptyArchive: false
                                }
                                def sentMessage = misses
                                    ? "Slack notification delivered for ${slug} on attempt ${deliveredOn}/3."
                                    : "Slack notification delivered for ${slug}."
                                cpuNote([
                                    reportPath: REPORT,
                                    level: misses ? 'info' : 'ok',
                                    outcome: 'delivered',
                                    area: 'slack',
                                    slug: slug,
                                    attempts: deliveredOn,
                                    message: sentMessage,
                                    exception: misses.join('\n'),
                                ])
                                if (changed) {
                                    try {
                                        retry(3) {
                                            slackUploadFile(
                                                channel: threadId,
                                                filePath: BUGS,
                                                initialComment: 'Bug to CVE map',
                                                failOnError: true
                                            )
                                        }
                                    } catch (Exception uploadErr) {
                                        if (uploadErr instanceof InterruptedException) {
                                            throw uploadErr
                                        }
                                        echo "WARNING cpu Slack file upload failed for ${slug}: ${uploadErr}"
                                        cpuNote([
                                            reportPath: REPORT,
                                            level: 'warning',
                                            outcome: 'upload-failed',
                                            area: 'slack-upload',
                                            slug: slug,
                                            attempts: 3,
                                            message: "Slack file upload failed for ${slug}. Artifact links are in the notification.",
                                            fallback: 'artifact links already sent',
                                            impact: 'the thread has no file',
                                            exception: cpuThrowableText(uploadErr),
                                        ])
                                        unstable("Slack file upload failed for ${slug}. Artifact links are in the notification.")
                                    }
                                }
                            }
                        } catch (Exception err) {
                            if (err instanceof InterruptedException) {
                                throw err
                            }
                            blockedSlugs.add(slug)
                            echo "WARNING cpu Slack failed for ${slug}: ${err}"
                            if (!recorded) {
                                cpuNote([
                                    reportPath: REPORT,
                                    level: 'warning',
                                    outcome: 'failed',
                                    area: 'slack',
                                    slug: slug,
                                    message: "Slack send failed for ${slug}: ${err}",
                                    fallback: 'pending message kept',
                                    impact: 'the next poll sends it again',
                                    exception: cpuThrowableText(err),
                                ])
                            }
                            unstable("Slack delivery failed for ${slug}. Message stays pending.")
                        }
                    }
                    cpuArchiveMapping(REPORT, BUGS, [STATE, BUGS])
                }
            }
        }
    }
    post {
        always {
            script {
                try {
                    def result = currentBuild.currentResult ?: currentBuild.result ?: 'SUCCESS'
                    sh "python3 ps/jenkins/cpu_cves.py status ${REPORT} ${STATE} ${STATUS} cpu-description.txt ${result}"
                } catch (Exception err) {
                    if (err instanceof InterruptedException) {
                        throw err
                    }
                    echo "WARNING cpu status summary failed: ${err}"
                }
                try {
                    if (fileExists('cpu-description.txt')) {
                        currentBuild.description = readFile('cpu-description.txt').trim()
                    }
                    if (fileExists(STATUS)) {
                        echo readFile(STATUS)
                        archiveArtifacts artifacts: STATUS, allowEmptyArchive: true
                    }
                } catch (Exception err) {
                    if (err instanceof InterruptedException) {
                        throw err
                    }
                    echo "WARNING cpu status publish failed: ${err}"
                }
            }
        }
        success {
            script {
                if (env.CPU_DIFF == '1') {
                    currentBuild.setKeepLog(true)
                }
            }
        }
        unstable {
            script {
                currentBuild.setKeepLog(true)
            }
        }
    }
}
