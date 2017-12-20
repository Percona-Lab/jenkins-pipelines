def call() {
    node('master') {
        unstash 'gitCommit'
        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                sh """
                    export path_to_build="UPLOAD/pmm/${JOB_NAME}/\$(cat shortCommit)-${BUILD_NUMBER}"

                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com " \
                        ls \${path_to_build}/binary/redhat/7/x86_64/*.rpm \
                            | xargs -n 1 signpackage --verbose --password ${SIGN_PASSWORD} --rpm
                    "
                """
            }
        }
    }
}
