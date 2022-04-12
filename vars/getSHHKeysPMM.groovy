String call() {
    String sshKeys = ''
    final List additional_keys = ['nikita-b', 'nailya', 'puneet0191'] // github users
    additional_keys.each { item ->
        response = httpRequest "https://github.com/${item}.keys"
        sshKeys += response.content + '\n'
    }
    return sshKeys
}
