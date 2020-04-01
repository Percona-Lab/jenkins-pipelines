def call(String FOLDER_NAME, String AWS_STASH_PATH, String TarballType) {
    node('master') {
        deleteDir()
        popArtifactFolder(FOLDER_NAME, AWS_STASH_PATH)
        //unstash "${TarballType}.tarball"
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
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
