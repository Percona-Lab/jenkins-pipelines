def call(String REPO_NAME, String REPO_TYPE, String REPO_COMPONENTS, String CENTOS_VERSIONS, String DEB_CODE_NAMES, String LIMIT) {
    node('source-builder') {
        deleteDir()

        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
            sh """
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                    set -o errexit
                    set -o xtrace

                    pushd /srv/repo-copy
                        wget https://raw.githubusercontent.com/vorsel/aux/master/scripts/create-repos/create_repo.sh -O ./create_repo.sh
                        chmod u+x ./create_repo.sh
                        ./create_repo.sh ${REPO_NAME} ${REPO_TYPE} ${REPO_COMPONENTS} ${CENTOS_VERSIONS} ${DEB_CODE_NAMES} ${LIMIT}
                        rm -f ./create_repo.sh
                    popd

                '
            """
        }
        deleteDir()
    }
}
