// Runs `command`, then `afterCommand` (shell) and `afterFunction` (closure). If any fails, runs
// `cleanupCommand` (shell) and/or `cleanupFunction` (closure) and starts over from `command`.
// Aborts are rethrown without retry or cleanup.
void retryCommand(Map cfg = [:]) {
    String command = cfg.command ?: error('command is required')
    Integer maxRetries = cfg.maxRetries ?: 3
    Integer delaySeconds = cfg.delaySeconds ?: 5

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            sh(script: command)
            if (cfg.afterCommand) {
                sh(script: cfg.afterCommand)
            }
            if (cfg.afterFunction) {
                cfg.afterFunction.call()
            }
            return
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            throw e
        } catch (Exception e) {
            echo "[HELPERS] Attempt ${attempt}/${maxRetries} failed: ${e.getMessage()}"
            if (cfg.cleanupCommand || cfg.cleanupFunction) {
                try {
                    if (cfg.cleanupCommand) {
                        sh(script: cfg.cleanupCommand)
                    }
                    if (cfg.cleanupFunction) {
                        cfg.cleanupFunction.call()
                    }
                } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException ie) {
                    throw ie
                } catch (Exception cleanupErr) {
                    echo "[HELPERS] Cleanup failed: ${cleanupErr.getMessage()}"
                }
            }
            if (attempt == maxRetries) {
                throw e
            }
            sleep(time: delaySeconds, unit: 'SECONDS')
        }
    }
}

return this
