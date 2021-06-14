def call() {
    sh '''
        set -o errexit
        set -o xtrace
        sudo curl -L https://github.com/docker/compose/releases/download/1.29.0/docker-compose-`uname -s`-`uname -m` | sudo tee docker-compose > /dev/null
        md5sum docker-compose > checkmd5.md5
        md5sum -c --strict  checkmd5.md5
        sudo mv docker-compose /usr/bin/docker-compose
        sudo chmod +x /usr/bin/docker-compose
    '''
}