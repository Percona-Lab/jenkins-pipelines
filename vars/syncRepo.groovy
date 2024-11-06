def call(String REPO_NAME) {
    node('jenkins') {
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
            sh """
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                    set -o errexit
                    set -o xtrace

                    # Update /srv/repo-copy/version
                    date +%s > /srv/repo-copy/version

                    rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                        /srv/repo-copy/${REPO_NAME}/ \
                        10.30.9.32:/www/repo.percona.com/htdocs/${REPO_NAME}/
                    rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                        /srv/repo-copy/version \
                        10.30.9.32:/www/repo.percona.com/htdocs/
                '
            """
        }
    }
}
