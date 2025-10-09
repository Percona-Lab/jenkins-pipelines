String call() {
    // For this to work, devs should add keys to https://github.com/settings/keys
    String sshKeys = ''
    // github users
    final List additional_keys = ['talhabinrizwan', 'nailya', 'BupycHuk', 'ademidoff' ] 
    additional_keys.each { item ->
        // First get the status code
        def exitCode = sh(script: "curl -sSf https://github.com/${item}.keys -o /tmp/ssh_keys_${item}", returnStatus: true)
        if (exitCode == 0) {
            // Success - read the keys
            def response = sh(script: "cat /tmp/ssh_keys_${item} 2>/dev/null && rm -f /tmp/ssh_keys_${item}", returnStdout: true).trim()
            if (response) {
                sshKeys += response + '\n'
            } else {
                echo "No SSH keys found for ${item}"
            }
        } else {
            // Failed - clean up and log
            sh(script: "rm -f /tmp/ssh_keys_${item}")
            echo "Failed to fetch SSH keys for ${item}, exit code: ${exitCode}"
        }
    }
    // Ensure we always return a string, even if empty
    return sshKeys ?: ''
}
