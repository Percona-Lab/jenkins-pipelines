// Runs body, and runs it again when it failed because the cluster API went away under it (see
// waitForClusterApi). A failure while the API still answers is real and is rethrown at once, so
// a broken chart does not run three times. body must be safe to repeat.
def call(String cli, Closure body) {
    def attempts = 3
    for (int i = 1; i <= attempts; i++) {
        waitForClusterApi(cli)
        try {
            body()
            return
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            throw e   // aborted or timed out
        } catch (err) {
            def apiUp = sh(returnStatus: true, script: "${cli} get --raw /readyz --request-timeout=10s >/dev/null 2>&1") == 0
            if (apiUp || i == attempts) {
                throw err
            }
            echo "The cluster API went away during this stage, running it again (attempt ${i + 1}/${attempts})"
        }
    }
}

def call(Closure body) {
    call('kubectl', body)
}
