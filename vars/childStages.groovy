import com.cloudbees.workflow.rest.external.RunExt
import com.cloudbees.workflow.rest.external.StageNodeExt

import org.jenkinsci.plugins.workflow.job.WorkflowRun

// Stage breakdown of a build dispatched with `build job:` — the model behind
// /wfapi/describe, read in-process.
//
// This library is loaded with `library ... retriever: modernSCM(...)`, which
// runs it inside the script sandbox like the calling Jenkinsfile, so the calls
// below need a one-time script approval on the controller. The sandbox rejects
// one signature per run, so approve them all at once in the Script Console:
//
//   def sa = org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval.get()
//   [
//       'method org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper getRawBuild',
//       'staticMethod com.cloudbees.workflow.rest.external.RunExt create org.jenkinsci.plugins.workflow.job.WorkflowRun',
//       'method com.cloudbees.workflow.rest.external.RunExt getStages',
//       'method com.cloudbees.workflow.rest.external.FlowNodeExt getName',
//       'method com.cloudbees.workflow.rest.external.FlowNodeExt getStatus',
//       'method com.cloudbees.workflow.rest.external.FlowNodeExt getDurationMillis',
//   ].each { sa.approveSignature(it) }
//
// Until then a caller gets a RejectedAccessException and has to render without.
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
