def call(String CLOUD_NAME = 'default') {
    def nodeLabel = (CLOUD_NAME == 'Hetzner') ? 'launcher-x64' : 'micro-amazon'
    node(nodeLabel) {
        unstash 'uploadPath'
        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                sh """
                    export path_to_build=`cat uploadPath`

                    cat /etc/hosts > hosts
                    echo '10.30.6.9 repo.ci.percona.com' >> hosts
                    sudo cp ./hosts /etc || true

                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com " \
                        ls \${path_to_build}/binary/redhat/*/*/*.rpm \
                            | xargs -n 1 signpackage --verbose --password ${SIGN_PASSWORD} --rpm
                    "
                """
            }
        }
    }
}
