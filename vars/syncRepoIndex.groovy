def call() {
    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh '''
            set -o errexit
            set -o xtrace

            # We redirect output in previous stage, so file will exist anycase.
            # Skip sync if file does not exist or has zero size
            [ -s ./new-index.html ] || exit 1

            wget http://repo.percona.com/index.html -O ./index.html
            # TODO Should we raise error here?
            cmp -s ./index.html ./new-index.html && exit 0
            #cat ./new-index.html

            # TODO as we have agreement that we do not remove automatically anything from index.html,
            # possibly we should check if new file is smaller than current

            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} ./new-index.html ${USER}@repo.ci.percona.com:/srv/repo-copy/
            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com '\
                set -o errexit
                set -o xtrace

                # TODO Possibly we should pass BACKUPS_COUNT as pipeline argument
                BACKUPS_COUNT=10
                pushd /srv/repo-copy
                    [ -e new-index.html ] || exit 1
                    CUR_BACKUPS_COUNT=`ls -1 index.html_????-??-??_??:??:?? | wc -l`
                    while [ "$CUR_BACKUPS_COUNT" -ge "$BACKUPS_COUNT" ]
                    do
                        rm -f `ls -1r index.html_????-??-??_??:??:??`
                        CUR_BACKUPS_COUNT=`ls -1 index.html_????-??-??_??:??:?? | wc -l`
                    done
                    mv index.html index.html_`date +%F_%T`
                    mv new-index.html index.html
                    echo "Calling index.html sync script"
                    #ls -la
                    #cat rsync-index.sh
                    ./rsync-index.sh
                popd
            '
        '''
    }
}
