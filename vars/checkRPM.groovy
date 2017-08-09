def call(String RPM_NAME = 'unknown-rpm') {
    node('master') {
        script {
            EXISTS = sh(
                    script: """
                        ssh -i ~/.ssh/id_rsa uploader@repo.ci.percona.com \
                            ls "/srv/repo-copy/${DESTINATION}/7/RPMS/x86_64/${RPM_NAME}" \
                            | wc -l || :
                    """,
                returnStdout: true
            ).trim()
            echo "EXISTS: ${EXISTS}"
            if (EXISTS != "0") {
                echo "WARNING: RPM package is already exists, skip building."
                currentBuild.result = 'UNSTABLE'
            }
        }
    }
}
