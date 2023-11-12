def call(String[] ITEMS_TO_ADD) {
    def argsStr = ''
    if (ITEMS_TO_ADD) {
        argsStr = ITEMS_TO_ADD.join(' ')
        argsStr += ' '
    }
    sh '''
        set -o errexit
        set -o xtrace
        curl -o update-repo-index.sh https://raw.githubusercontent.com/Percona-Lab/release-aux/main/scripts/update-repo-index.sh
        bash -x ./update-repo-index.sh ''' + argsStr + '''> ./new-index.html
    '''
}
