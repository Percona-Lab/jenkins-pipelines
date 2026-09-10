void dockerBuildAndPush(Map cfg) {
    if (env.IMAGE_OPERATOR) {
        echo "SKIP: Build and push are not needed, operator image was set!"
        return
    }

    withCredentials([usernamePassword(
        credentialsId: 'hub.docker.com',
        passwordVariable: 'PASS',
        usernameVariable: 'USER'
    )]) {
        dockerBuildOperatorImage(
            cfg.operator,
            cfg.operatorImage,
            cfg.branch
        )

        dockerPushImage(
            cfg.operatorImage,
            cfg.branch
        )
    }
}

void dockerBuildImage(String dockerfile, String image) {
    withEnv([
        "DOCKERFILE=${dockerfile}",
        "DOCKER_IMAGE=${image}"
    ]) {
        sh 'docker build --pull -f "${DOCKERFILE}" -t "${DOCKER_IMAGE}" .'
    }
}

void dockerBuildOperatorImage(String operator, String image, String tag, String sourceDir='source') {
    echo "=========================[ Building ${image}:${tag} Docker image ]========================="

    sh """
        cd ${sourceDir}

        sg docker -c '
            docker buildx use multiarch 2>/dev/null || docker buildx create --name multiarch --use
            export IMAGE=${image}:${tag}

            if [[ "${operator}" == "pg-operator" ]]; then
                DOCKER_DEFAULT_PLATFORM=linux/amd64,linux/arm64 make build
            else
                DOCKER_DEFAULT_PLATFORM=linux/amd64,linux/arm64 e2e-tests/build
            fi
        '

        sudo rm -rf build
    """
}

void dockerCleanupVolumes() {
    sh """
        sudo docker system prune --volumes -af
    """
}

void dockerPushImage(String image, String tag) {
    echo "=========================[ Pushing ${image}:${tag} Docker image ]========================="
    sh """
        sg docker -c '
            echo "\$PASS" | docker login -u "\$USER" --password-stdin
            docker push ${image}:${tag}
            docker logout
        '
    """
}

void dockerPushImage(String image) {
    withEnv(["DOCKER_IMAGE=${image}"]) {
        sh 'docker push "${DOCKER_IMAGE}"'
    }
}

void dockerTagImage(String sourceImage, String targetImage) {
    withEnv([
        "DOCKER_SOURCE_IMAGE=${sourceImage}",
        "DOCKER_TARGET_IMAGE=${targetImage}"
    ]) {
        sh 'docker tag "${DOCKER_SOURCE_IMAGE}" "${DOCKER_TARGET_IMAGE}"'
    }
}

void gitCheckoutTag(String tagName) {
    sh """
        set -eu
        git checkout --detach 'refs/tags/${tagName}'
    """
}

void gitClone(Map cfg, String source = 'source') {
    def branch = cfg.branch
    def repo = cfg.repo

    if (source.startsWith('/') || source.tokenize('/').contains('..')) {
        error("Invalid Git source directory: ${source}")
    }

    echo "=========================[ Cloning sources ]========================="
    echo "Using branch: ${branch}"
    echo "Source directory: ${source}"

    dir(source) {
        deleteDir()
    }

    withEnv([
        "GIT_BRANCH_NAME=${branch}",
        "GIT_REPO_URL=${repo}",
        "GIT_SOURCE_DIR=${source}"
    ]) {
        sh '''
            set -e
            sudo git config --global --add safe.directory '*'
            git clone -b "$GIT_BRANCH_NAME" "$GIT_REPO_URL" "$GIT_SOURCE_DIR"
        '''
    }

    stash name: "${source}FILES", includes: "${source}/**", useDefaultExcludes: false
}

void gitCreateBranch(String branchName) {
    sh """
        set -eu
        git checkout -b '${branchName}'
    """
}

void gitCreateTag(String tagName, String message, boolean force = false) {
    withEnv([
        "GIT_TAG_NAME=${tagName}",
        "GIT_TAG_MESSAGE=${message}"
    ]) {
        if (force) {
            sh 'git tag -fa "${GIT_TAG_NAME}" -m "${GIT_TAG_MESSAGE}"'
        } else {
            sh 'git tag -a "${GIT_TAG_NAME}" -m "${GIT_TAG_MESSAGE}"'
        }
    }
}

void gitFetchTags() {
    sh 'git fetch --force --tags origin'
}

String gitHead() {
    sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
}

String githubCreatePullRequest(String repository, String headNamespace, String headBranch, String baseBranch, String title, String body) {
    def head = headNamespace ? "${headNamespace}:${headBranch}" : headBranch

    withEnv([
        "GITHUB_PR_REPOSITORY=${repository}",
        "GITHUB_PR_HEAD=${head}",
        "GITHUB_PR_BASE=${baseBranch}",
        "GITHUB_PR_TITLE=${title}",
        "GITHUB_PR_BODY=${body}"
    ]) {
        sh(
            script: '''
                set -eu
                set +x
                api="https://api.github.com/repos/${GITHUB_PR_REPOSITORY}/pulls"

                github_api() {
                    curl -fsS -H "Authorization: Bearer ${GITHUB_TOKEN}" \
                        -H "Accept: application/vnd.github+json" \
                        -H "X-GitHub-Api-Version: 2022-11-28" "$@"
                }

                response=$(github_api --get --data-urlencode state=open \
                    --data-urlencode "head=${GITHUB_PR_HEAD}" \
                    --data-urlencode "base=${GITHUB_PR_BASE}" "${api}")
                pr_url=$(printf '%s' "${response}" | grep -m 1 '"html_url"' | cut -d '"' -f 4 || true)

                if [ -z "${pr_url}" ]; then
                    payload=$(printf '{"title":"%s","head":"%s","base":"%s","body":"%s"}' \
                        "${GITHUB_PR_TITLE}" "${GITHUB_PR_HEAD}" \
                        "${GITHUB_PR_BASE}" "${GITHUB_PR_BODY}")
                    response=$(github_api -X POST -H "Content-Type: application/json" \
                        -d "${payload}" "${api}")
                    pr_url=$(printf '%s' "${response}" | grep -m 1 '"html_url"' | cut -d '"' -f 4 || true)
                    [ -n "${pr_url}" ] || {
                        echo "ERROR: GitHub did not return the PR URL" >&2
                        exit 1
                    }
                fi

                printf '%s' "${pr_url}"
            ''',
            returnStdout: true
        ).trim()
    }
}

void gitPushBranch(String branchName) {
    sh """
        set -eu

        branch_ref='refs/heads/${branchName}'

        if git ls-remote --exit-code --heads origin "\${branch_ref}" >/dev/null 2>&1; then
            echo "Deleting remote branch ${branchName}"
            git push origin ":\${branch_ref}"
        fi

        git push -u origin "\${branch_ref}:\${branch_ref}"
    """
}

void gitPushTag(String tagName, boolean force = false) {
    withEnv(["GIT_TAG_NAME=${tagName}"]) {
        if (force) {
            sh 'git push --force origin "refs/tags/${GIT_TAG_NAME}:refs/tags/${GIT_TAG_NAME}"'
        } else {
            sh 'git push origin "refs/tags/${GIT_TAG_NAME}:refs/tags/${GIT_TAG_NAME}"'
        }
    }
}

void gitResetWorkspace() {
    sh '''
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
    '''
}

boolean gitTagExists(String tagName) {
    boolean exists = false

    withEnv(["GIT_TAG_NAME=${tagName}"]) {
        exists = sh(
            script: 'git show-ref --verify --quiet "refs/tags/${GIT_TAG_NAME}"',
            returnStatus: true
        ) == 0
    }

    return exists
}

String jenkinsAgentLabel(def params, String awsLabel = 'docker', String hetznerLabel = 'docker-x64-min') {
    return params.JENKINS_AGENT == 'Hetzner' ? hetznerLabel : awsLabel
}

void kubernetesCleanupCluster(String kubeconfig) {
    sh """
        export KUBECONFIG=${kubeconfig}

        if [ -s "\$KUBECONFIG" ] && kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
            for namespace in \$(kubectl get namespaces --request-timeout=5s --no-headers \
                | awk '{print \$1}' \
                | grep -vE "^kube-|^gke-|^cattle-|^openshift" \
                | sed '/-operator/ s/^/1-/' \
                | sort \
                | sed 's/^1-//'); do

                echo "Cleaning namespace: \$namespace"

                kubectl delete deployments --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete sts --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete replicasets --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete services --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete pods --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
            done
        else
            echo "Skipping namespace cleanup: Kubernetes API is not reachable for ${kubeconfig}"
        fi
    """
}

void kubernetesCleanupFailedTestNamespaces(Map testVariables, String testName, String clusterSuffix) {
    def clusterName = "${testVariables.cluster_name}-${clusterSuffix}"
    def kubeconfig = "${testVariables.kubeconfigPath}/${clusterName}"

    echo "Cleaning failed test namespaces for ${testName} on ${clusterName}"

    sh """
        set +e
        export FAILED_TEST_NAME='${testName}'
        export KUBECONFIG='${kubeconfig}'
        if [ ! -s "\$KUBECONFIG" ] || ! kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
            echo "Skipping failed test namespace cleanup: Kubernetes API is not reachable for \$KUBECONFIG"
            exit 0
        fi
        kubectl get namespaces --request-timeout=10s --no-headers \
            | awk '{print \$1}' \
            | while read -r namespace; do
                case "\$namespace" in
                    "\$FAILED_TEST_NAME"-*|kuttl*)
                        echo "Removing finalizers from resources in namespace: \$namespace"
                        kubectl api-resources --verbs=list --namespaced -o name --request-timeout=10s \
                            | while read -r resource; do
                                kubectl get "\$resource" -n "\$namespace" -o name --ignore-not-found --request-timeout=10s 2>/dev/null \
                                    | while read -r object; do
                                        kubectl patch "\$object" -n "\$namespace" --type=merge -p '{"metadata":{"finalizers":[]}}' --request-timeout=10s || true
                                    done
                            done
                        echo "Deleting namespace: \$namespace"
                        kubectl delete namespace "\$namespace" --force --grace-period=0 --wait=false --request-timeout=10s || true
                        ;;
                esac
            done
    """
}

void stashClonedGitFiles() {
    stash includes: 'source/**', name: 'sourceFILES', useDefaultExcludes: false
}

void trivyScanImage(String image) {
    withEnv(["TRIVY_IMAGE=${image}"]) {
        sh '''
            trivy --version
            trivy image --ignore-unfixed --severity HIGH,CRITICAL \
              --format json --output trivy.json "${TRIVY_IMAGE}"
        '''
    }
}

void trivyVerifyImage(String image) {
    withEnv(["TRIVY_IMAGE=${image}"]) {
        sh 'trivy image --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 "${TRIVY_IMAGE}"'
    }
}

void unstashClonedGitFiles() {
    deleteDir()
    checkout scm
    unstash 'sourceFILES'
}

return this
