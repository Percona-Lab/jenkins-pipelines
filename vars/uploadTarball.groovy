def call(String TarballType) {
    node('master') {
        deleteDir()
        unstash "${TarballType}.tarball"
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                exit 1
                export path_to_build=`cat uploadPath`

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    mkdir -p \${path_to_build}/${TarballType}/tarball

                scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                    `find . -name '*.tar.*'` \
                    ${USER}@repo.ci.percona.com:\${path_to_build}/${TarballType}/tarball/

            """
        }
        deleteDir()
    }
}
