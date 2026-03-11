def call(Map cfg = [:]) {
    def tests = (cfg.tests ?: []) as List
    def channel = cfg.channel ?: '#cloud-dev-ci'
    def gitBranch = cfg.gitBranch ?: env.GIT_BRANCH
    def platformVer = cfg.platformVer ?: env.PLATFORM_VER
    def platformChannel = cfg.platformChannel ?: cfg.gkeReleaseChannel ?: env.GKE_RELEASE_CHANNEL
    def clusterWide = cfg.clusterWide ?: env.CLUSTER_WIDE
    def image = cfg.image ?: env.IMAGE_PXC ?: env.IMAGE_MYSQL ?: env.IMAGE_MONGOD ?: env.IMAGE_POSTGRESQL ?: env.IMAGE
    def operatorImage = cfg.operatorImage ?: cfg.imageOperator ?: env.IMAGE_OPERATOR

    def failedTests = tests.findAll { it["result"] == "failure" }
    def passedCount = tests.count { it["result"] == "passed" }
    def failedCount = failedTests.size()
    def skippedCount = tests.count { it["result"] == "skipped" }
    def total = tests.size()

    def duration = (currentBuild.durationString ?: "N/A").replace(' and counting', '')
    def cw = ("$clusterWide" == "YES") ? "cluster-wide" : "non-cluster-wide"
    def buildResult = (currentBuild.currentResult ?: currentBuild.result ?: 'SUCCESS')
    def status = (failedCount > 0 && buildResult == 'SUCCESS') ? 'FAILED' : buildResult
    def color = (status == 'SUCCESS') ? '#36A64F' : (status == 'UNSTABLE' ? '#DAA038' : '#FF0000')

    def upstreamCause = currentBuild.getBuildCauses('org.jenkinsci.plugins.workflow.support.steps.build.BuildUpstreamCause')
    if (!upstreamCause) {
        upstreamCause = currentBuild.getBuildCauses('hudson.model.Cause$UpstreamCause')
    }
    def userCause = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
    def timerCause = currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause')

    def triggerDetails = null
    if (upstreamCause) {
        def upstreamProject = upstreamCause[0].upstreamProject ?: 'unknown job'
        def upstreamBuild = upstreamCause[0].upstreamBuild
        triggerDetails = upstreamBuild ? "${upstreamProject} #${upstreamBuild}" : upstreamProject
    } else if (userCause) {
        triggerDetails = userCause[0].userName ?: userCause[0].userId
    } else if (timerCause) {
        triggerDetails = 'cron schedule'
    }

    def message = "*<${env.BUILD_URL}|${env.JOB_NAME} #${env.BUILD_NUMBER}>* - ${status}\n"
    def platformDetails = platformChannel ? "${platformVer} (${platformChannel})" : "${platformVer}"
    message += "*Branch:* `${gitBranch}` | *Platform:* `${platformDetails}` | *Mode:* `${cw}`\n"
    def buildDetails = []
    if (image) {
        buildDetails << "*Image:* `${image}`"
    }
    if (operatorImage) {
        buildDetails << "*Operator image:* `${operatorImage}`"
    }
    if (buildDetails) {
        message += buildDetails.join(' | ') + "\n"
    }

    if (triggerDetails) {
        message += "*Triggered by:* ${triggerDetails}\n"
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
