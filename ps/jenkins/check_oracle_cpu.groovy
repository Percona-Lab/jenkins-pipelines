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

def cpuPollDecision(String reportPath, String manifestPath) {
    // Missing comparison data is not "unchanged". Callers archive.
    def out = ''
    try {
        out = sh(
            script: """python3 -c 'import json
def load(path):
    try:
        return json.load(open(path))
    except Exception:
        return None
report = load("${reportPath}")
manifest = load("${manifestPath}")
items = manifest.get("items") if isinstance(manifest, dict) else None
notify = str(len(items)) if isinstance(items, list) else "x"
if not isinstance(report, dict):
    print("ERR " + notify)
else:
    usable = "1" if report.get("usable") else "0"
    degraded = "1" if report.get("degraded") else "0"
    changed = report.get("state_changed")
    if changed is True:
        changed_s = "1"
    elif changed is False:
        changed_s = "0"
    else:
        changed_s = "x"
    print(usable + " " + degraded + " " + changed_s + " " + notify)'""",
            returnStdout: true
        ).trim()
    } catch (Exception err) {
        if (err instanceof InterruptedException) {
            throw err
        }
        echo "WARNING cpu run record is unreadable: ${err}"
        return [readable: false, usable: true, degraded: true, changed: null, notify: -1]
    }
    if (out.startsWith('ERR')) {
        echo 'WARNING cpu run record is unreadable.'
        def notify = -1
        def errParts = out.split(' ')
        if (errParts.size() > 1 && errParts[1].isInteger()) {
            notify = errParts[1].toInteger()
        }
        return [readable: false, usable: true, degraded: true, changed: null, notify: notify]
    }
    if (!out) {
        echo 'WARNING cpu run record is unreadable.'
        return [readable: false, usable: true, degraded: true, changed: null, notify: -1]
    }
    def parts = out.split(' ')
    if (parts.size() < 4) {
        echo 'WARNING cpu run record is unreadable.'
        return [readable: false, usable: true, degraded: true, changed: null, notify: -1]
    }
    def changed = null
    if (parts[2] == '1') {
        changed = true
    } else if (parts[2] == '0') {
        changed = false
    }
    def notify = -1
    if (parts[3].isInteger()) {
        notify = parts[3].toInteger()
    }
    return [
        readable: true,
        usable: parts[0] == '1',
        degraded: parts[1] == '1',
        changed: changed,
        notify: notify,
    ]
}

def cpuLoadNotifyItem(String manifestPath, int index) {
    sh """python3 -c 'import json
item = (json.load(open("${manifestPath}")).get("items") or [])[${index}]
open("cpu-item-slug.txt","w").write(str(item.get("slug") or ""))
open("cpu-item-pending.txt","w").write(str(item.get("pending_id") or ""))
open("cpu-item-changed.txt","w").write("1" if item.get("changed") else "0")
open("cpu-item-text.txt","w").write(str(item.get("text") or ""))'"""
    return [
        slug: readFile('cpu-item-slug.txt').trim(),
        pendingId: readFile('cpu-item-pending.txt').trim(),
        changed: readFile('cpu-item-changed.txt').trim() == '1',
        text: readFile('cpu-item-text.txt').trim(),
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
                sh 'python3 -m unittest discover -s ps/jenkins/tests -t ps/jenkins'
            }
        }
        stage('Check advisories') {
            steps {
                sh "rm -rf ${STATE} ${LEGACY_STATE} ${LEGACY_SLACK} ${BUGS} ${MANIFEST} ${REPORT} ${STATUS} ${POLL_RC} cpu-description.txt cpu-note.json cpu-thread.json cpu-item-slug.txt cpu-item-pending.txt cpu-item-changed.txt cpu-item-text.txt cpu-cves-diff.json cpu-notify cpu-degraded.txt cpu-publish cpu-events.jsonl cpu-event.json"
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
                    def haveCheckpoint = fileExists(STATE) && fileExists(BUGS)
                    def pollRc = fileExists(POLL_RC) ? readFile(POLL_RC).trim() : '1'
                    // A late failure can leave both files and a non-zero exit.
                    // Archive before that exit decides the build.
                    if (pollRc != '0' && haveCheckpoint) {
                        cpuArchiveMapping(REPORT, BUGS, [STATE, BUGS])
                        unstable('Oracle CPU poll failed after writing a checkpoint. Mapping archived.')
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
                    } else if (!haveCheckpoint) {
                        error 'Oracle CPU poll has no checkpoint to archive.'
                    } else if (pollRc == '0') {
                        cpuArchiveMapping(REPORT, BUGS, [STATE, BUGS])
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
                    def countOut = ''
                    try {
                        countOut = sh(
                            script: """python3 -c 'import json; print(len(json.load(open("${MANIFEST}")).get("items") or []))'""",
                            returnStdout: true
                        ).trim()
                    } catch (Exception err) {
                        if (err instanceof InterruptedException) {
                            throw err
                        }
                        echo "WARNING cpu notify manifest is unreadable: ${err}"
                        unstable('Oracle CPU notify manifest is unreadable.')
                        return
                    }
                    if (!countOut.isInteger()) {
                        unstable('Oracle CPU notify manifest is unreadable.')
                        return
                    }
                    def count = countOut.toInteger()
                    def blockedSlugs = []
                    for (int index = 0; index < count; index++) {
                        def item = cpuLoadNotifyItem(MANIFEST, index)
                        def slug = item.slug
                        def pendingId = item.pendingId
                        def changed = item.changed
                        def text = item.text
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
