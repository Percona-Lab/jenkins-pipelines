// valkeyPublish — push this build's packages to the public repo, and when the
// push fails (ssh drop, reprepro/createrepo/rsync error, publish lock not
// acquired) wait and run it again instead of failing the job.
//
// A rerun is safe: it publishes the same files from the same build, so the
// repo gets byte-identical packages and checksums don't change. It also
// finishes a push that stopped halfway, e.g. RPMs copied but createrepo not run.
// An aborted build is not retried.
//
// Unless allowRepublish is set, the push refuses (under the publish lock,
// before copying anything) to replace an already-published package with a
// different build of the same version-release. That closes the gap the
// pre-flight check leaves: two runs that both passed it before either one
// published, or a run restarted from the publish stage. That refusal is
// final and is not retried.
def call(String cloud, String repo, String component, Boolean allowRepublish = false,
         int attempts = 3, int waitMinutes = 10) {
    for (int i = 1; i <= attempts; i++) {
        try {
            sync2ProdAutoBuild(cloud, repo, component, true, !allowRepublish)
            return
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            throw e
        } catch (Exception e) {
            String msg = e.message ?: e.toString()
            if (msg.contains('exit code 3')) {
                error("Not publishing to ${repo}/${component}: a different build of this version-release is already published. Raise the release number, or set ALLOW_REPUBLISH.")
            }
            if (i == attempts) {
                echo "Publish to ${repo}/${component} failed ${attempts} times, giving up"
                throw e
            }
            echo "Publish attempt ${i}/${attempts} to ${repo}/${component} failed: ${msg}. Retrying in ${waitMinutes} min"
            sleep(time: waitMinutes, unit: 'MINUTES')
        }
    }
}
