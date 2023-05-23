def call(String TarballType) {
    node('master') {
        deleteDir()
        unstash "${TarballType}.tarball"
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            withEnv(['TARBALL_TYPE=' + TarballType]){
                sh '''
                    PATH_TO_BUILD=`cat uploadPath`
                    echo "TarballType: ${TARBALL_TYPE}"

                    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com "
                        mkdir -p ${PATH_TO_BUILD}/${TARBALL_TYPE}/tarball
                    "

                    scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} \
                        `find . -name '*.tar.*'` \
                        ${USER}@repo.ci.percona.com:${PATH_TO_BUILD}/${TARBALL_TYPE}/tarball/
                '''
            }
        }
        deleteDir()
    }
}
