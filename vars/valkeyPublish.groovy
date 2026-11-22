// valkeyPublish — push this build's packages to the public repo, and when the
// push fails (ssh drop, reprepro/createrepo/rsync error, publish lock not
// acquired) wait and run it again instead of failing the job.
//
// A rerun is safe: it publishes the same files from the same build, so the
// repo gets byte-identical packages and checksums don't change. It also
// finishes a push that stopped halfway, e.g. RPMs copied but createrepo not run.
// An aborted build is not retried.
def call(String cloud, String repo, String component, int attempts = 3, int waitMinutes = 10) {
    for (int i = 1; i <= attempts; i++) {
        try {
            sync2ProdAutoBuild(cloud, repo, component, true)
            return
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            throw e
        } catch (Exception e) {
            if (i == attempts) {
                echo "Publish to ${repo}/${component} failed ${attempts} times, giving up"
                throw e
            }
            echo "Publish attempt ${i}/${attempts} to ${repo}/${component} failed: ${e.message}. Retrying in ${waitMinutes} min"
            sleep(time: waitMinutes, unit: 'MINUTES')
        }
    }
}
