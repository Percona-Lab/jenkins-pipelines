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

void withNkpVpnCredentials(Closure body) {
    withCredentials([usernamePassword(
        credentialsId: 'nkp-vpn',
        usernameVariable: 'NKP_VPN_USER',
        passwordVariable: 'NKP_VPN_PASSWORD'
    )]) {
        body()
    }
}

void withNkpKubeconfigCredentials(String clusterName, Closure body) {
    def kubeconfigCredentials = [
        'percona-cluster-1': 'nkp-percona1-kubeconfig',
        'percona-cluster-2': 'nkp-percona2-kubeconfig'
    ]
    def credentialsId = kubeconfigCredentials[clusterName]
    if (!credentialsId) {
        error("No NKP kubeconfig credential configured for ${clusterName}")
    }

    withCredentials([file(
        credentialsId: credentialsId,
        variable: 'NKP_KUBECONFIG_FILE'
    )]) {
        body()
    }
}

return this
