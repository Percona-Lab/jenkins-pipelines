def call() {
    node('master') {
        unstash 'uploadPath'
        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'gpg_key', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                sh """
                    export path_to_build=`cat uploadPath`

                    eval $(gpg-agent --daemon)

                    echo ${SIGN_PASSWORD} | gpg --passphrase-fd 0
                """
            }
        }
    }
}
