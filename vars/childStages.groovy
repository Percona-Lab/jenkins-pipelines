import com.cloudbees.workflow.rest.external.RunExt
import com.cloudbees.workflow.rest.external.StageNodeExt

import org.jenkinsci.plugins.workflow.job.WorkflowRun

// Stage breakdown of a build dispatched with `build job:`, so a parent can render
// a child's stages instead of a single opaque box.
//
// RunExt is the model behind /wfapi/describe, which is what the Stage View and
// Blue Ocean draw from, so a caller gets exactly the stages a human sees on the
// child — read in-process, with no HTTP call, no API token and no anonymous-read
// assumption. Reaching rawBuild needs to happen in the trusted global library;
// the same code inside a job's own Jenkinsfile is sandboxed and would sit behind
// per-signature script approval.
//
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
