String call() {
    // For this to work, devs should add keys to https://github.com/settings/keys
    String sshKeys = ''
    // github users
    final List additional_keys = ['talhabinrizwan', 'atymchuk', 'nailya', 'puneet0191', 'BupycHuk', ] 
    additional_keys.each { item ->
        response = httpRequest "https://github.com/${item}.keys"
        sshKeys += response.content + '\n'
    }
    return sshKeys
}
