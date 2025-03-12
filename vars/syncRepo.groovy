def call(String REPO_NAME, String PROBUILD) {
    node('jenkins') {
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
            sh """
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                    set -o errexit
                    set -o xtrace
                    if [ ${PROBUILD} = YES ]; then
                        PRO_FOLDER="private/"
                    else
                        PRO_FOLDER=""
                    fi
                    # Update /srv/repo-copy/version
                    date +%s > /srv/repo-copy/\${PRO_FOLDER}version

                    rsync -avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
                        /srv/repo-copy/\${PRO_FOLDER}${REPO_NAME}/ \
                        10.30.9.32:/www/repo.percona.com/htdocs/\${PRO_FOLDER}${REPO_NAME}/
                    rsync -avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
                        /srv/repo-copy/\${PRO_FOLDER}version \
                        10.30.9.32:/www/repo.percona.com/htdocs/
                '
            """
        }
    }
}
