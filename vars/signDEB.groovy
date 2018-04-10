def call() {
    node('master') {
        unstash 'uploadPath'
        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                sh """
                    export path_to_build=`cat uploadPath`

                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com " \
                        ls \${path_to_build}/binary/debian/*/*/*.deb \
                            | xargs -n 1 signpackage --verbose --password ${SIGN_PASSWORD} --deb
                    "
                """
            }
        }
    }
}
