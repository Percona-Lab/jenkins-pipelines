def call(String REPO_NAME) {
    node('source-builder') {
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
            sh """
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                    set -o errexit
                    set -o xtrace

                    rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                        /srv/repo-copy/${REPO_NAME}/ \
                        10.10.9.209:/www/repo.percona.com/htdocs/${REPO_NAME}/

                    # Clean CDN cache for repo.percona.com
                    bash -xe /usr/local/bin/clear_cdn_cache.sh
                '
            """
        }
    }
}
