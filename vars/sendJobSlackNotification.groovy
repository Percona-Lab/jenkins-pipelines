def call(Map cfg = [:]) {
    def tests = (cfg.tests ?: []) as List
    def channel = cfg.channel ?: '#cloud-dev-ci'
    def gitBranch = cfg.gitBranch ?: env.GIT_BRANCH
    def platformVer = cfg.platformVer ?: env.PLATFORM_VER
    def platformChannel = cfg.platformChannel ?: cfg.gkeReleaseChannel ?: env.GKE_RELEASE_CHANNEL
    def clusterWide = cfg.clusterWide ?: env.CLUSTER_WIDE
    def pillarVersion = cfg.pillarVersion ?: env.PILLAR_VERSION

    def failedTests = tests.findAll { it["result"] == "failure" }
    def passedCount = tests.count { it["result"] == "passed" }
    def failedCount = failedTests.size()
    def skippedCount = tests.count { it["result"] == "skipped" }
    def total = tests.size()

    def duration = (currentBuild.durationString ?: "N/A").replace(' and counting', '')
    def isRelease = ("$pillarVersion" != "none")
    def cw = ("$clusterWide" == "YES") ? "cluster-wide" : "non-cluster-wide"
    def color = (failedCount > 0) ? '#FF0000' : '#36A64F'
    def status = (failedCount > 0) ? 'FAILED' : 'SUCCESS'

    def upstreamCause = currentBuild.getBuildCauses('hudson.model.Cause$UpstreamCause')
    def userCause = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')

    def triggerDetails = null
    if (upstreamCause) {
        triggerDetails = "${upstreamCause[0].upstreamProject} #${upstreamCause[0].upstreamBuild}"
    } else if (userCause) {
        triggerDetails = userCause[0].userName
    }

    def isWeeklyTriggered = false
    if (upstreamCause) {
        def upstreamName = upstreamCause[0].upstreamProject?.toLowerCase() ?: ''
        isWeeklyTriggered = upstreamName.contains('weekly') || upstreamName.contains('scheduler')
    }

    def message = "*<${env.BUILD_URL}|${env.JOB_NAME} #${env.BUILD_NUMBER}>* - ${status}\n"
    def platformDetails = platformChannel ? "${platformVer} (${platformChannel})" : "${platformVer}"
    message += "*Branch:* `${gitBranch}` | *Platform:* `${platformDetails}` | *Mode:* `${cw}`\n"

    if (triggerDetails) {
        message += "*Triggered by:* ${triggerDetails}\n"
    }

    if (isWeeklyTriggered) {
        message += "*Trigger type:* weekly schedule\n"
    }

    if (isRelease) {
        message += "*Release run* (pillar ${pillarVersion})\n"
    }

    message += "*Tests:* ${passedCount} passed, ${failedCount} failed, ${skippedCount} skipped / ${total} total\n"
    message += "*Duration:* ${duration}\n"

    if (failedCount > 0) {
        message += "\n*Failed tests:*\n"
        failedTests.each { t ->
            def mins = 0.0
            try {
                mins = ((t["time"] ?: 0) as Double) / 60
            } catch (ignored) {
                mins = 0.0
            }
            message += "- `${t['name']}` on ${t['cluster']} (${String.format('%.1f', mins)} min)\n"
        }
    }

    try {
        slackSend channel: channel, color: color, message: message
    } catch (err) {
        echo "Slack notification failed: ${err}"
    }
}

return this
