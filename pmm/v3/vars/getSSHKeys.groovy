String call() {
    // For this to work, devs should add keys to https://github.com/settings/keys
    String sshKeys = ''
    // github users
    final List additional_keys = ['talhabinrizwan', 'nailya', 'puneet0191', 'BupycHuk', ] 
    additional_keys.each { item ->
        try {
            def response = sh(script: "curl -sSf https://github.com/${item}.keys", returnStdout: true).trim()
            if (response) {
                sshKeys += response + '\n'
            }
        } catch (Exception e) {
            echo "Failed to fetch SSH keys for ${item}: ${e.getMessage()}"
            // Continue to next item
        }
    }
    // Ensure we always return a string, even if empty
    return sshKeys ?: ''
}
