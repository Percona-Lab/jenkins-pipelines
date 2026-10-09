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

def cpuReadJson(String path) {
    if (!fileExists(path)) {
        return null
    }
    try {
        // returnPojo avoids net.sf.json types and the forbidden JsonSlurper.
        return readJSON(file: path, returnPojo: true)
    } catch (Exception err) {
        if (err instanceof InterruptedException) {
            throw err
        }
        echo "WARNING cpu ${path} is unreadable: ${err}"
        return null
    }
}

def cpuNotifyCount(Object manifest) {
    if (!(manifest instanceof Map)) {
        return -1
    }
    def items = manifest.items
    if (!(items instanceof List)) {
        return -1
    }
    return items.size()
}

def cpuPollDecision(String reportPath, String manifestPath) {
    // Missing comparison data is not "unchanged". Callers archive.
    // The manifest is read on its own so a bad report cannot hide Slack.
    def report = cpuReadJson(reportPath)
    def notify = cpuNotifyCount(cpuReadJson(manifestPath))
    if (!(report instanceof Map)) {
        echo 'WARNING cpu run record is unreadable.'
        return [readable: false, usable: true, degraded: true, changed: null, notify: notify]
    }
    def changed = null
    if (report.state_changed == true) {
        changed = true
    } else if (report.state_changed == false) {
        changed = false
    }
    return [
        readable: true,
        usable: report.usable == true,
        degraded: report.degraded == true,
        changed: changed,
        notify: notify,
    ]
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
        // Each Oracle read aborts after 120 s of socket silence. A slow
        // body can still run without end, and disableConcurrentBuilds
        // then holds every later cron trigger until that build finishes.
        timeout(time: 30, unit: 'MINUTES')
        // removeLastBuild stays false. LogRotator then keeps the last
        // successful build and the last stable build, including their
        // artifacts, after NOT_BUILT polls rotate the rest.
        // setKeepLog would also keep artifacts and skip that rotation.
        buildDiscarder(logRotator(numToKeepStr: '100', artifactNumToKeepStr: '10'))
        timestamps()
        // Production mode rejects copyArtifacts unless the source job names
        // the reader. This job copies its own last build. '*' also covers a
        // later job that reads the bug map.
        copyArtifactPermission('*')
    }
    stages {
        stage('Test') {
            steps {
                // post reads these files. A failed test must not summarize
                // the previous build's workspace copy.
                sh "rm -rf ${STATE} ${LEGACY_STATE} ${LEGACY_SLACK} ${BUGS} ${MANIFEST} ${REPORT} ${STATUS} ${POLL_RC} cpu-description.txt cpu-note.json cpu-thread.json"
                sh 'python3 -m unittest discover -s ps/jenkins/tests -t ps/jenkins'
            }
        }
        stage('Check advisories') {
            steps {
                script {
                    // SUCCESS or UNSTABLE. NOT_BUILT is skipped, so the
                    // copy is the newest build that archived a checkpoint.
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
                    def haveBugs = fileExists(BUGS)
                    def haveState = fileExists(STATE)
                    def remembered = haveState ? [STATE, BUGS] : [BUGS]
                    def pollRc = fileExists(POLL_RC) ? readFile(POLL_RC).trim() : '1'
                    // Archive the mapping before the checkpoint check. A failed
                    // state replace can leave cpu-bug-cve.json and no state file.
                    if (pollRc != '0' && haveBugs) {
                        cpuArchiveMapping(REPORT, BUGS, remembered)
                        unstable('Oracle CPU poll failed after writing a mapping. Mapping archived.')
                    } else if (pollRc != '0') {
                        error 'Oracle CPU poll produced no usable mapping.'
                    }
                    def decision = cpuPollDecision(REPORT, MANIFEST)
                    env.CPU_UNCHANGED = '0'
                    env.CPU_NOTIFY = '0'
                    if (!decision.readable || decision.degraded) {
                        unstable('Oracle CPU collection degraded. Previous state kept for failed advisories.')
                    }
                    // false/0 is the only unchanged poll. A missing comparison
                    // or an unreadable manifest still archives.
                    def unchanged = pollRc == '0' && decision.readable && !decision.degraded && decision.changed == false && decision.notify == 0
                    if (unchanged) {
                        env.CPU_UNCHANGED = '1'
                    } else if (!haveBugs) {
                        error 'Oracle CPU poll has no mapping to archive.'
                    } else if (pollRc == '0') {
                        cpuArchiveMapping(REPORT, BUGS, remembered)
                    }
                    if (decision.notify > 0) {
                        env.CPU_NOTIFY = '1'
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
                    def manifest = cpuReadJson(MANIFEST)
                    def items = (manifest instanceof Map) ? manifest.items : null
                    if (!(items instanceof List)) {
                        unstable('Oracle CPU notify manifest is unreadable.')
                        return
                    }
                    def threads = [:]
                    if (fileExists(STATE)) {
                        def state = cpuReadJson(STATE)
                        // A failed read is not an empty thread map. Starting
                        // new roots and then acking would replace saved ids.
                        if (!(state instanceof Map)) {
                            unstable('Oracle CPU checkpoint is unreadable. Slack deferred.')
                            return
                        }
                        if (state.threads instanceof Map) {
                            threads = state.threads
                        }
                    }
                    def blockedSlugs = []
                    for (def item : items) {
                        if (!(item instanceof Map)) {
                            continue
                        }
                        def slug = (item.slug ?: '').toString().trim()
                        def pendingId = (item.pending_id ?: '').toString().trim()
                        def changed = item.changed == true
                        def text = (item.text ?: '').toString().trim()
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
                            // Permalink. After this build finishes, a SUCCESS
                            // or UNSTABLE result becomes this file. NOT_BUILT
                            // does not move it.
                            linkLines << "Bug to CVE map: ${JOB_URL}lastSuccessfulBuild/artifact/${BUGS}"
                            linkLines << "State: ${JOB_URL}lastSuccessfulBuild/artifact/${STATE}"
                            def message = "[${JOB_NAME}]: Oracle CPU/CSPU CVE change\n${text}"
                            if (changed) {
                                message = "${message}\n\n${linkLines.join('\n')}"
                            }
                            def threadId = ''
                            def thread = threads[slug]
                            if (thread instanceof Map && thread.threadId) {
                                threadId = thread.threadId.toString()
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
                                    threads[slug] = [threadId: threadId]
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
        stage('Mark unchanged') {
            steps {
                script {
                    def result = currentBuild.currentResult ?: currentBuild.result ?: 'SUCCESS'
                    if (env.CPU_UNCHANGED == '1' && result == 'SUCCESS') {
                        currentBuild.result = 'NOT_BUILT'
                    }
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
                    def resultNow = currentBuild.currentResult ?: currentBuild.result ?: 'SUCCESS'
                    if (resultNow == 'NOT_BUILT') {
                        def mapUrl = "${env.JOB_URL}lastSuccessfulBuild/artifact/${BUGS}"
                        currentBuild.description = "No new CVEs or mapping changes. <a href=\"${mapUrl}\">Bug to CVE map</a>"
                    } else if (fileExists('cpu-description.txt')) {
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
    }
}
