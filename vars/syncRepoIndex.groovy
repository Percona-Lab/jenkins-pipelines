def call() {
    sh '''
        set -o errexit
        set -o xtrace

        wget http://repo.percona.com/index.html -O ./index.html
        cmp -s ./index.html ./new-index.html && exit
	DTSTR=`date +%F_%T`
        #2021-10-11_13:02:16
        cat ./new-index.html
    '''
//    node('source-builder') {
//        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
//            sh """
//                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
//                    set -o errexit
//                    set -o xtrace
//
//                    pushd /srv/repo-copy
//                        wget https://raw.githubusercontent.com/Percona-Lab/release-aux/main/scripts/update-repo-index.sh -O ./update-repo-index.sh
//                        chmod u+x ./update-repo-index.sh
//                        ./update-repo-index.sh > new-index.html
//                        rm -f ./update-repo-index.sh
//                    popd
//
//                '
//            """
//        }
//        deleteDir()
//    }
}
