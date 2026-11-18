// Every wrapper returns the value of its body, so callers can do
// `def prUrl = withGitHubCredentials { ... }` or `return withGCPCredentials { ... }`.

def withGitHubCredentials(Closure body) {
    def result = null
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

            result = body()
        } finally {
            withEnv(["GIT_ORIGINAL_REMOTE=${originalRemote}"]) {
                sh '''
                    set +x
                    git remote set-url origin "${GIT_ORIGINAL_REMOTE}"
                '''
            }
        }
    }
    return result
}

def withDockerCredentials(Closure body) {
    def result = null
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

                result = body()
            } finally {
                sh(script: 'docker logout docker.io', returnStatus: true)
                dir(dockerConfig) {
                    deleteDir()
                }
            }
        }
    }
    return result
}

def withAWSCredentials(Closure body) {
    def result = null
    withCredentials([aws(
        credentialsId: 'eks-cicd',
        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
    )]) {
        result = body()
    }
    return result
}

def withAWSLatestVersionCredentials(Closure body) {
    def result = null
    withCredentials([aws(
        credentialsId: 'AMI/OVF',
        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
    )]) {
        result = body()
    }
    return result
}

def withDOKSCredentials(Closure body) {
    def result = null
    withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
        result = body()
    }
    return result
}

def withAzureCredentials(Closure body) {
    def result = null
    withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
        result = body()
    }
    return result
}

def withGCPCredentials(Closure body) {
    def result = null
    withCredentials([
        string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'),
        file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')
    ]) {
        result = body()
    }
    return result
}

def withGCPProjectOnlyCredentials(Closure body) {
    def result = null
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT')]) {
        result = body()
    }
    return result
}

def withOpenShiftCredentials(Closure body) {
    def result = null
    withCredentials([
        aws(credentialsId: 'openshift-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
        file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'),
        file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE'),
        usernamePassword(credentialsId: 'docker.io', passwordVariable: 'DOCKER_READ_PASS', usernameVariable: 'DOCKER_READ_USER')
    ]) {
        sshagent(['aws-openshift-41-key']) {
            result = body()
        }
    }
    return result
}

def withGCPAuth(Closure body) {
    def result = null
    withGCPCredentials {
        sh '''
            gcloud auth activate-service-account --key-file "$CLIENT_SECRET_FILE"
            gcloud config set project "$GCP_PROJECT"
        '''
        result = body()
    }
    return result
}

def withAzureAuth(Closure body) {
    def result = null
    withAzureCredentials {
        sh '''
            az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID" --allow-no-subscriptions
            az account set -s "$AZURE_SUBSCRIPTION_ID"
        '''
        result = body()
    }
    return result
}

void activateGoogleCredentials() {
    withGCPCredentials {
        sh '''
            gcloud auth activate-service-account --key-file "$CLIENT_SECRET_FILE"
            gcloud config set project "$GCP_PROJECT"
        '''
    }
}

void activateAzureCredentials() {
    withAzureCredentials {
        sh '''
            az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID" --allow-no-subscriptions
            az account set -s "$AZURE_SUBSCRIPTION_ID"
        '''
    }
}

return this
