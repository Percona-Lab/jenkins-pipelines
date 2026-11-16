void retryCommand(Map cfg = [:]) {
    String command = cfg.command ?: error("command is required")
    String afterCommand = cfg.afterCommand ?: null
    String cleanupCommand = cfg.cleanupCommand ?: null
    Closure cleanupFunction = cfg.cleanupFunction ?: null
    Integer maxRetries = cfg.maxRetries ?: 3
    Integer delaySeconds = cfg.delaySeconds ?: 5
    
    Integer commandRetryCount = 0
    Integer afterRetryCount = 0
    Boolean commandSuccess = false
    Boolean afterSuccess = false
    Boolean shouldCleanup = false
    
    try {
        // Retry main command
        while (commandRetryCount < maxRetries && !commandSuccess) {
            try {
                sh(script: command)
                commandSuccess = true
                echo "[HELPERS] Command succeeded on attempt ${commandRetryCount + 1}"
            } catch (Exception e) {
                commandRetryCount++
                shouldCleanup = true
                if (commandRetryCount >= maxRetries) {
                    echo "[HELPERS] Command failed after ${maxRetries} attempts"
                    throw e
                }
                echo "[HELPERS] Attempt ${commandRetryCount} failed, retrying in ${delaySeconds}s..."
                sleep(time: delaySeconds, unit: 'SECONDS')
            }
        }
        
        // Retry afterCommand if it exists
        if (afterCommand) {
            while (afterRetryCount < maxRetries && !afterSuccess) {
                try {
                    sh(script: afterCommand)
                    afterSuccess = true
                    echo "[HELPERS] AfterCommand succeeded on attempt ${afterRetryCount + 1}"
                } catch (Exception e) {
                    afterRetryCount++
                    shouldCleanup = true
                    if (afterRetryCount >= maxRetries) {
                        echo "[HELPERS] AfterCommand failed after ${maxRetries} attempts"
                        throw e
                    }
                    echo "[HELPERS] AfterCommand attempt ${afterRetryCount} failed, retrying in ${delaySeconds}s..."
                    sleep(time: delaySeconds, unit: 'SECONDS')
                }
            }
        }
    } finally {
        if (shouldCleanup) {
            if (cleanupCommand) {
                echo "[HELPERS] Running cleanup command..."
                sh(script: cleanupCommand)
            }
            
            if (cleanupFunction) {
                echo "[HELPERS] Running cleanup function..."
                cleanupFunction.call()
            }
        }
    }
}

void retry(Closure closure, Integer maxRetries = 3, Integer delaySeconds = 5) {
    Integer retryCount = 0
    Boolean success = false
    
    while (retryCount < maxRetries && !success) {
        try {
            closure.call()
            success = true
            echo "[HELPERS] Operation succeeded on attempt ${retryCount + 1}"
        } catch (Exception e) {
            retryCount++
            if (retryCount >= maxRetries) {
                echo "[HELPERS] Operation failed after ${maxRetries} attempts"
                throw e
            }
            echo "[HELPERS] Attempt ${retryCount} failed, retrying in ${delaySeconds}s..."
            sleep(time: delaySeconds, unit: 'SECONDS')
        }
    }
}

@com.cloudbees.groovy.cps.NonCPS
void retryFunctionCall(Map cfg = [:]) {
    def closure = cfg.closure ?: error("closure is required")
    def maxRetries = cfg.maxRetries ?: 3
    def delaySeconds = cfg.delaySeconds ?: 5
    def quiet = cfg.quiet ?: false
    
    Integer retryCount = 0
    Boolean success = false
    
    while (retryCount < maxRetries && !success) {
        try {
            closure.call()
            success = true
            if (!quiet) echo "[HELPERS] Operation succeeded on attempt ${retryCount + 1}"
        } catch (Exception e) {
            retryCount++
            if (retryCount >= maxRetries) {
                if (!quiet) echo "[HELPERS] Operation failed after ${maxRetries} attempts"
                throw e
            }
            if (!quiet) echo "[HELPERS] Attempt ${retryCount} failed, retrying in ${delaySeconds}s..."
            sleep(time: delaySeconds, unit: 'SECONDS')
        }
    }
}

return this
