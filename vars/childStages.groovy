import com.cloudbees.workflow.rest.external.RunExt
import com.cloudbees.workflow.rest.external.StageNodeExt

import org.jenkinsci.plugins.workflow.job.WorkflowRun

// Returns a list of [name, status, durationMillis]. Status is StatusExt: SUCCESS,
// FAILED, UNSTABLE, ABORTED, NOT_EXECUTED, IN_PROGRESS or PAUSED_PENDING_INPUT.
// A freestyle or otherwise non-Pipeline child has no stages and yields [].
@NonCPS
def call(run) {
    def build = run?.rawBuild
    if (!(build instanceof WorkflowRun)) {
        return []
    }
    return RunExt.create(build).stages.collect { StageNodeExt stage ->
        [
            name          : stage.name,
            status        : stage.status.toString(),
            durationMillis: stage.durationMillis,
        ]
    }
}
