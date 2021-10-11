def call() {
    sh '''
        set -o errexit
        set -o xtrace
        #sudo
        wget https://raw.githubusercontent.com/Percona-Lab/release-aux/main/scripts/update-repo-index.sh -O ./update-repo-index.sh
        #chmod u+x ./update-repo-index.sh
        bash -x ./update-repo-index.sh > ./new-index.html
    '''
}
