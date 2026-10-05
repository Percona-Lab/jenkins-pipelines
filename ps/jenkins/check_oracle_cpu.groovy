library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def STATE = 'cpu-cves.json'
def DIFF = 'cpu-cves-diff.json'
def BUGS = 'cpu-bug-cve.json'
def SLACK_STATE = 'cpu-slack.json'
def NOTIFY_DIR = 'cpu-notify'
def DEGRADED = 'cpu-degraded.txt'
def PUBLISH = 'cpu-publish'
def EVENTS = 'cpu-events.jsonl'
def RUN = 'cpu-run.json'
def STATUS = 'cpu-status.txt'

def cpuThrowableText(err) {
    // printStackTrace walks causes, suppressed exceptions, and cycles.
    def sw = new StringWriter()
    def pw = new PrintWriter(sw)
    err.printStackTrace(pw)
    pw.flush()
    return sw.toString()
}

def cpuEvent(Map event) {
    // eventsPath is inside the map. A method cannot see a script-local `def EVENTS`.
    // A failed diagnostic write must not skip thread save, ack, or later advisories.
    try {
        writeFile file: 'cpu-event.json', text: groovy.json.JsonOutput.toJson(event)
        sh "python3 ps/jenkins/cpu_cves.py event ${event.eventsPath} cpu-event.json"
    } catch (Exception err) {
        if (err instanceof InterruptedException) {
            throw err
        }
        echo "WARNING cpu event record failed: ${err}"
    }
}

def cpuRunFlags(String runPath) {
    // A partial or invalid run record must not fail the build. The bug
    // map is archived from the publish marker before this is called.
    if (!fileExists(runPath)) {
        return [degraded: false, publish: false]
    }
    // CPS does not keep a `def` from inside `try` visible after the block.
    // Reading it there looks up the script binding and fails the build.
    def out = ''
    try {
        out = sh(
            script: """python3 -c 'import json
try:
    data = json.load(open("${runPath}"))
except Exception:
    print("ERR")
else:
    if not isinstance(data, dict):
        print("ERR")
    else:
        degraded = "1" if data.get("degraded") else "0"
        publish = "1" if data.get("publish") else "0"
        print(degraded + " " + publish)'""",
            returnStdout: true
        ).trim()
    } catch (Exception err) {
        if (err instanceof InterruptedException) {
            throw err
        }
        echo "WARNING cpu run record is unreadable: ${err}"
        return [degraded: false, publish: false]
    }
    if (!out || out.startsWith('ERR')) {
        echo 'WARNING cpu run record is unreadable.'
        return [degraded: false, publish: false]
    }
    def parts = out.split(' ')
    return [degraded: parts[0] == '1', publish: parts.size() > 1 && parts[1] == '1']
}

def cpuArchiveCore(String eventsPath, String bugArtifact, List names) {
    def remembered = names.findAll { fileExists(it) }
    if (!remembered) {
        return
    }
    def archived = remembered.join(',')
    archiveArtifacts artifacts: archived, allowEmptyArchive: true
    cpuNoteMapArchived(eventsPath, bugArtifact, archived)
}

def cpuNoteMapArchived(String eventsPath, String bugArtifact, String artifacts) {
    if (artifacts.split(',').contains(bugArtifact)) {
        cpuEvent([
            eventsPath: eventsPath,
            level: 'ok',
            outcome: 'published',
            area: 'archive',
            message: 'Bug to CVE map archived.',
        ])
    }
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
        // Production mode rejects copyArtifacts unless the source job names
        // the reader. This job copies its own last build. '*' also covers a
        // later job that reads the bug map.
        copyArtifactPermission('*')
    }
    stages {
        stage('Check advisories') {
            steps {
                sh "rm -rf ${STATE} ${DIFF} ${BUGS} ${SLACK_STATE} ${NOTIFY_DIR} ${DEGRADED} ${PUBLISH} ${EVENTS} ${RUN} ${STATUS} cpu-description.txt"
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
                        --bugs ${BUGS} \\
                        --notify-dir ${NOTIFY_DIR} \\
                        --slack-state ${SLACK_STATE} \\
                        --notify ${env.CPU_NOTIFY_MODE} \\
                        ${params.IGNORE_STATE ? '--ignore-state' : ''} \\
                        --count ${ADVISORY_COUNT}
                """
                script {
                    def order = fileExists("${NOTIFY_DIR}/order.txt") ? readFile("${NOTIFY_DIR}/order.txt").trim() : ''
                    env.CPU_DIFF = fileExists(DIFF) ? '1' : '0'
                    env.CPU_NOTIFY = order ? '1' : '0'
                    // Archive before reading the diagnostic record. A partial
                    // cpu-run.json must not skip the mapping archive.
                    if (fileExists(PUBLISH)) {
                        cpuArchiveCore(EVENTS, BUGS, [STATE, BUGS, SLACK_STATE])
                    }
                    def runFlags = cpuRunFlags(RUN)
                    def degraded = fileExists(DEGRADED) || runFlags.degraded
                    env.CPU_DEGRADED = '0'
                    if (degraded) {
                        if (fileExists(DEGRADED)) {
                            echo readFile(DEGRADED)
                        } else {
                            echo 'WARNING cpu degraded marker file is missing. The run record still marks this poll degraded.'
                        }
                        unstable('Oracle CPU collection degraded. Previous state kept for failed advisories.')
                        env.CPU_DEGRADED = '1'
                    }
                    env.CPU_PUBLISH = (fileExists(PUBLISH) || runFlags.publish) ? '1' : '0'
                    if (env.CPU_PUBLISH == '1' && !fileExists(PUBLISH)) {
                        echo 'WARNING cpu publish marker file is missing. The run record still marks this mapping for archive.'
                        cpuArchiveCore(EVENTS, BUGS, [STATE, BUGS, SLACK_STATE])
                    }
                    if (env.CPU_DEGRADED == '1' && env.CPU_PUBLISH != '1') {
                        cpuArchiveCore(EVENTS, BUGS, [STATE, BUGS, SLACK_STATE])
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
                    // Fetched CVE state and still-pending messages go out
                    // together, before any send. A later failure must not
                    // leave the new baseline without the undelivered text.
                    def prepared = [STATE, BUGS, SLACK_STATE, DIFF].findAll { fileExists(it) }
                    if (prepared) {
                        def archived = prepared.join(',')
                        archiveArtifacts artifacts: archived, allowEmptyArchive: true
                        cpuNoteMapArchived(EVENTS, BUGS, archived)
                    }
                    def order = readFile("${NOTIFY_DIR}/order.txt").trim().split('\n')
                    def changed = readFile("${NOTIFY_DIR}/changed.txt").trim()
                    def changedKeys = changed ? changed.split('\n') : []
                    def blockedSlugs = []
                    for (def key : order) {
                        if (!key) {
                            continue
                        }
                        def slug = key
                        def pendingId = ''
                        def splitAt = key.indexOf('--')
                        if (splitAt > 0) {
                            slug = key.substring(0, splitAt)
                            pendingId = slug + ':' + key.substring(splitAt + 2)
                        }
                        if (blockedSlugs.contains(slug)) {
                            echo "WARNING cpu Slack skipped ${slug}; an older message for this advisory was not delivered."
                            cpuEvent([
                                eventsPath: EVENTS,
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
                        def text = readFile("${NOTIFY_DIR}/${key}.txt").trim()
                        def linkLines = []
                        if (fileExists(DIFF)) {
                            linkLines << "Full list and diff: ${BUILD_URL}artifact/${DIFF}"
                        }
                        linkLines << "Bug to CVE map: ${BUILD_URL}artifact/${BUGS}"
                        linkLines << "State: ${BUILD_URL}artifact/${STATE}"
                        def links = linkLines.join('\n')
                        def message = "[${JOB_NAME}]: Oracle CPU/CSPU CVE change\n${text}"
                        if (changedKeys.contains(key)) {
                            message = "${message}\n\n${links}"
                        }
                        def threadId = ''
                        if (fileExists(SLACK_STATE)) {
                            threadId = sh(
                                script: """python3 -c 'import json; d=json.load(open("${SLACK_STATE}")); print(((d.get("threads") or {}).get("${slug}") or {}).get("threadId") or "")'""",
                                returnStdout: true
                            ).trim()
                        }
                        def target = threadId ? threadId : SLACK_CHANNEL
                        // failOnError true sets the build to FAILURE before
                        // throwing. This job ignores FAILURE when restoring
                        // state, so a caught failure would drop the pending
                        // message. A failed send returns null and does not
                        // throw when failOnError is left false.
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
                            def failFallback = pendingId ? "pending message kept" : "not queued"
                            def failImpact = pendingId
                                ? "the next poll sends it again"
                                : "this unchanged post is not retried from pending"
                            echo "WARNING cpu ${failMessage}"
                            cpuEvent([
                                eventsPath: EVENTS,
                                level: 'warning',
                                outcome: 'failed',
                                area: 'slack',
                                slug: slug,
                                attempts: 3,
                                message: failMessage,
                                fallback: failFallback,
                                impact: failImpact,
                                exception: misses.join('\n'),
                            ])
                            recorded = true
                            unstable(failMessage)
                        } else {
                        if (!threadId) {
                            threadId = response.threadId
                            sh """python3 -c 'import json; from pathlib import Path; p=Path("${SLACK_STATE}"); data=json.loads(p.read_text()) if p.is_file() and p.stat().st_size else {}; threads=data.get("threads") or {}; threads["${slug}"]={"channelId":"${response.channelId}","ts":"${response.ts}","threadId":"${response.threadId}"}; data["threads"]=threads; p.write_text(json.dumps(data, indent=2)+"\\n")'"""
                            env.CPU_SLACK_SAVE = '1'
                            archiveArtifacts artifacts: SLACK_STATE, allowEmptyArchive: false
                        }
                        if (pendingId) {
                            sh "python3 ps/jenkins/cpu_cves.py ack ${SLACK_STATE} '${pendingId}'"
                            archiveArtifacts artifacts: SLACK_STATE, allowEmptyArchive: false
                        }
                        def sentMessage = misses
                            ? "Slack notification delivered for ${slug} on attempt ${deliveredOn}/3."
                            : "Slack notification delivered for ${slug}."
                        cpuEvent([
                            eventsPath: EVENTS,
                            level: misses ? 'info' : 'ok',
                            outcome: 'delivered',
                            area: 'slack',
                            slug: slug,
                            attempts: deliveredOn,
                            message: sentMessage,
                            exception: misses.join('\n'),
                        ])
                        if (changedKeys.contains(key)) {
                            // failOnError is false by default, so a failed upload
                            // returns and the step does not throw. Retry only
                            // sees a failure when the step throws. The text was
                            // already confirmed, so this does not stay pending.
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
                                // Abort and timeout throw InterruptedException.
                                // Swallowing that lets the build continue after
                                // the user or the job timer stopped it.
                                if (uploadErr instanceof InterruptedException) {
                                    throw uploadErr
                                }
                                echo "WARNING cpu Slack file upload failed for ${slug}: ${uploadErr}"
                                cpuEvent([
                                    eventsPath: EVENTS,
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
                                cpuEvent([
                                    eventsPath: EVENTS,
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
                    def remembered = [STATE, BUGS, SLACK_STATE, DIFF].findAll { fileExists(it) }
                    if (remembered) {
                        def archived = remembered.join(',')
                        archiveArtifacts artifacts: archived, allowEmptyArchive: true
                        cpuNoteMapArchived(EVENTS, BUGS, archived)
                    }
                }
            }
        }
        stage('Mark unchanged') {
            when {
                allOf {
                    environment name: 'CPU_NOTIFY', value: '0'
                    environment name: 'CPU_DEGRADED', value: '0'
                    environment name: 'CPU_PUBLISH', value: '0'
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
        always {
            script {
                // Mapping is already archived. A failure here must not
                // change the build result or hide that artifact.
                try {
                    def result = currentBuild.currentResult ?: currentBuild.result ?: 'SUCCESS'
                    sh "python3 ps/jenkins/cpu_cves.py status ${EVENTS} ${RUN} ${SLACK_STATE} ${STATUS} cpu-description.txt ${result}"
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
                // Last step of a real SUCCESS only. NOT_BUILT and FAILURE
                // do not keep the build. A diff is the artifact worth keeping.
                if (env.CPU_DIFF == '1' || env.CPU_PUBLISH == '1') {
                    currentBuild.setKeepLog(true)
                }
            }
        }
    }
}
