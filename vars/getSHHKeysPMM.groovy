String call() {
    // You need to add your keys to https://github.com/settings/keys
    String sshKeys = ''
    final List additional_keys = ['nikita-b', 'nailya', 'puneet0191', 'BupycHuk', ] // github users
    additional_keys.each { item ->
        response = httpRequest "https://github.com/${item}.keys"
        sshKeys += response.content + '\n'
    }
    return sshKeys
}
