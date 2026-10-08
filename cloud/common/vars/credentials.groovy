def withGitHubCredentials(Closure body) {
    withCredentials([
        string(
            credentialsId: 'GITHUB_API_TOKEN',
            variable: 'GITHUB_TOKEN'
        )
    ]) {
        def originalRemote = sh(
            script: 'git remote get-url origin',
            returnStdout: true
        ).trim()

        try {
            sh """
                set +x
                git config user.email "jenkins@percona.com"
                git config user.name "JNKPercona"
                git remote set-url origin https://x-access-token:\${GITHUB_TOKEN}@github.com/${env.REPO_PATH}.git
            """

            body()
        } finally {
            withEnv(["GIT_ORIGINAL_REMOTE=${originalRemote}"]) {
                sh '''
                    set +x
                    git remote set-url origin "${GIT_ORIGINAL_REMOTE}"
                '''
            }
        }
    }
}

void withDockerCredentials(Closure body) {
    def dockerConfig = "${pwd(tmp: true)}/docker-config"

    dir(dockerConfig) {
        deleteDir()
    }

    withCredentials([usernamePassword(
        credentialsId: 'hub.docker.com',
        passwordVariable: 'PASS',
        usernameVariable: 'USER'
    )]) {
        withEnv(["DOCKER_CONFIG=${dockerConfig}"]) {
            try {
                sh '''
                    set +x
                    printf '%s' "${PASS}" | docker login docker.io \
                        --username "${USER}" \
                        --password-stdin
                '''

                body()
            } finally {
                sh(script: 'docker logout docker.io', returnStatus: true)
                dir(dockerConfig) {
                    deleteDir()
                }
            }
        }
    }
}

return this
