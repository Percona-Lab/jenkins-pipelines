def call(String DESTINATION, String RPM_NAME) {
    node('master') {
        script {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                EXISTS = sh(
                        script: """
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                                ls "/srv/repo-copy/${DESTINATION}/7/RPMS/x86_64/${RPM_NAME}" \
                                | wc -l || :
                        """,
                    returnStdout: true
                ).trim()
            }
            echo "EXISTS: ${EXISTS}"
            if (EXISTS != "0") {
                echo "WARNING: RPM package is already exists, skip building."
                currentBuild.result = 'UNSTABLE'
            }
        }
    }
}
