String call() {
    // For this to work, devs should add keys to https://github.com/settings/keys
    String sshKeys = ''
    // github users
    final List additional_keys = ['talhabinrizwan', 'nailya', 'BupycHuk', 'ademidoff' ] 
    additional_keys.each { item ->
        try {
            response = httpRequest "https://github.com/${item}.keys"
            sshKeys += response.content + '\n'
        } catch (Exception e) {
            echo "Failed to fetch SSH keys for ${item}: ${e.getMessage()}"
            // Continue to next item
        }
    }
    return sshKeys
}
