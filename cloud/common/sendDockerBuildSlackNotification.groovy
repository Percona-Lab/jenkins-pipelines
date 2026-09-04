def call(Map cfg = [:]) {
    def channel = cfg.channel ?: '#cloud-dev-ci'
    def failedImages = (cfg.failedImages ?: []) as List
    def gitBranch = cfg.gitBranch ?: params.GIT_BRANCH ?: env.GIT_BRANCH
    def dockerBranch = cfg.dockerBranch ?: params.GIT_PD_BRANCH
    def trivySummary = cfg.trivySummary ?: ''

    def buildResult = (currentBuild.currentResult ?: currentBuild.result ?: 'FAILURE')
    def status = cfg.status ?: buildResult
    def color = cfg.color ?: ((status == 'SUCCESS') ? '#36A64F' : (status == 'UNSTABLE' ? '#F6F930' : '#FF0000'))

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
    if (gitBranch && dockerBranch) {
        message += "*Operator branch:* `${gitBranch}` | *Docker branch:* `${dockerBranch}`\n"
    } else if (gitBranch) {
        message += "*Branch:* `${gitBranch}`\n"
    }
    if (triggerDetails) {
        message += "*Triggered by:* ${triggerDetails}\n"
    }

    if (failedImages) {
        message += "\n*Failed images:*\n"
        failedImages.each { img ->
            message += "- `${img}`\n"
        }
    }

    if (trivySummary) {
        message += trivySummary
    }

    try {
        slackSend channel: channel, color: color, message: message
    } catch (err) {
        echo "Slack notification failed: ${err}"
    }
}

return this
