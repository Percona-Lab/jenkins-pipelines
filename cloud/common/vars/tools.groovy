void dockerBuildAndPush(Map cfg) {
    if (env.IMAGE_OPERATOR) {
        echo 'SKIP: Build and push are not needed, operator image was set!'
        return
    }

    withCredentials([usernamePassword(
        credentialsId: 'hub.docker.com',
        passwordVariable: 'PASS',
        usernameVariable: 'USER'
    )]) {
        sh '''
            echo "$PASS" | docker login docker.io \
                -u "$USER" \
                --password-stdin
        '''

        dockerBuildOperatorImage(
            cfg.operator,
            cfg.operatorImage,
            cfg.branch,
            cfg.sourceDir ?: 'source'
        )

        dockerPushImage(
            cfg.operatorImage,
            cfg.branch
        )

        sh 'docker logout docker.io'
    }
}

void dockerCopyImage(String sourceImage, List<String> targetImages) {
    if (!targetImages) {
        error('At least one target image is required')
    }

    withEnv([
        "DOCKER_SOURCE_IMAGE=${sourceImage}",
        "DOCKER_TARGET_TAGS=${targetImages.collect { "--tag ${it}" }.join(' ')}"
    ]) {
        sh '''
            set -eu
            docker buildx imagetools create \
                ${DOCKER_TARGET_TAGS} "${DOCKER_SOURCE_IMAGE}"
        '''
    }
}

void dockerBuildOperatorImage(String operator, String image, String tag, String sourceDir='source') {
    echo "=========================[ Building ${image}:${tag} Docker image ]========================="

    sh """
        cd "${sourceDir}"

        sg docker -c '
            docker buildx use multiarch 2>/dev/null || docker buildx create --name multiarch --use
            export IMAGE=${image}:${tag}

            if [[ "${operator}" == "pg" || "${operator}" == "pg-operator" ]]; then
                DOCKER_DEFAULT_PLATFORM=linux/amd64,linux/arm64 make build
            else
                DOCKER_DEFAULT_PLATFORM=linux/amd64,linux/arm64 e2e-tests/build
            fi
        '

        sudo rm -rf build
    """
}

void dockerCleanupVolumes() {
    sh '''
        sudo docker system prune --volumes -af
    '''
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

void dockerTagImage(String sourceImage, String targetImage) {
    withEnv([
        "DOCKER_SOURCE_IMAGE=${sourceImage}",
        "DOCKER_TARGET_IMAGE=${targetImage}"
    ]) {
        sh 'docker tag "${DOCKER_SOURCE_IMAGE}" "${DOCKER_TARGET_IMAGE}"'
    }
}

boolean gitBranchExists(String branchName) {
    boolean exists = false

    withEnv(["GIT_BRANCH_NAME=${branchName}"]) {
        exists = sh(
            script: 'git ls-remote --exit-code --heads origin "refs/heads/${GIT_BRANCH_NAME}" > /dev/null 2>&1',
            returnStatus: true
        ) == 0
    }

    return exists
}

void gitClone(Map cfg, String source = 'source') {
    def branch = cfg.branch
    def repo = cfg.repo

    if (source.startsWith('/') || source.tokenize('/').contains('..')) {
        error("Invalid Git source directory: ${source}")
    }

    echo '=========================[ Cloning sources ]========================='
    echo "Using branch: ${branch}"
    echo "Source directory: ${source}"

    dir(source) {
        sh 'sudo chown -R "$(id -u):$(id -g)" .'
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

void gitCheckoutTag(String tagName) {
    sh """
        set -eu
        git checkout --detach refs/tags/'${tagName}'
    """
}

void gitCleanupLocalChanges() {
    sh '''
        set -eu

        git reset --hard HEAD
        git clean -fdx
    '''
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

void gitDeleteRemoteBranch(String branchName) {
    withEnv(["GIT_BRANCH_NAME=${branchName}"]) {
        sh 'git push origin --delete "${GIT_BRANCH_NAME}" || true'
    }
}

void gitFetchBranch(String branchName) {
    withEnv(["GIT_BRANCH_NAME=${branchName}"]) {
        sh '''
            git fetch origin \
                "refs/heads/${GIT_BRANCH_NAME}:refs/remotes/origin/${GIT_BRANCH_NAME}"

            git checkout --detach \
                "refs/remotes/origin/${GIT_BRANCH_NAME}"
        '''
    }
}

void gitVerifyLocalBranchFromRemote(String branchName) {
    withEnv(["GIT_BRANCH_NAME=${branchName}"]) {
        sh '''
            set -eu

            expected_commit=$(git rev-parse \
                "refs/remotes/origin/${GIT_BRANCH_NAME}")
            current_commit=$(git rev-parse HEAD)

            if [ "${current_commit}" != "${expected_commit}" ]; then
                echo "Expected ${expected_commit}, found ${current_commit}" >&2
                exit 1
            fi

            if ! git diff --quiet || ! git diff --cached --quiet; then
                echo 'Workspace is dirty after checking out the remote branch' >&2
                git status --short --untracked-files=no >&2
                exit 1
            fi
        '''
    }
}

void gitFetchTag(String tagName) {
    withEnv(["GIT_TAG_NAME=${tagName}"]) {
        sh '''
            git fetch origin \
                "refs/tags/${GIT_TAG_NAME}:refs/tags/${GIT_TAG_NAME}"
        '''
    }
}

void gitFetchTags() {
    sh 'git fetch --force --tags origin'
}

String gitHead() {
    sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
}

String gitTagCommit(String tagName) {
    withEnv(["GIT_TAG_NAME=${tagName}"]) {
        return sh(
            script: 'git rev-list -n 1 "refs/tags/${GIT_TAG_NAME}"',
            returnStdout: true
        ).trim()
    }
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
                pr_url=$(printf '%s' "${response}" | jq -r '.[0].html_url // empty')

                if [ -z "${pr_url}" ]; then
                    payload=$(jq -n \
                        --arg title "${GITHUB_PR_TITLE}" \
                        --arg head "${GITHUB_PR_HEAD}" \
                        --arg base "${GITHUB_PR_BASE}" \
                        --arg body "${GITHUB_PR_BODY}" \
                        '{title: $title, head: $head, base: $base, body: $body}')
                    response=$(github_api -X POST -H "Content-Type: application/json" \
                        -d "${payload}" "${api}")
                    pr_url=$(printf '%s' "${response}" | jq -r '.html_url // empty')
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

String githubPullRequestStatus(String repository, String pullRequestUrl) {
    def status

    withEnv([
        "GITHUB_PR_REPOSITORY=${repository}",
        "GITHUB_PR_URL=${pullRequestUrl}"
    ]) {
        status = sh(
            script: '''
                set -eu
                set +x
                pr_number="${GITHUB_PR_URL##*/}"
                api="https://api.github.com/repos/${GITHUB_PR_REPOSITORY}/pulls/${pr_number}"

                response=$(curl -fsS \
                    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
                    -H "Accept: application/vnd.github+json" \
                    -H "X-GitHub-Api-Version: 2022-11-28" \
                    "${api}") || {
                        printf 'unknown'
                        exit 0
                    }

                status=$(printf '%s' "${response}" | jq -r '
                        if .merged == true then "merged"
                        elif .state == "closed" then "closed"
                        elif .state == "open" then "open"
                        else "unknown"
                        end
                    ') || status=unknown

                printf '%s' "${status}"
            ''',
            returnStdout: true
        ).trim()
    }

    return status
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
            script: 'git ls-remote --exit-code --tags origin "refs/tags/${GIT_TAG_NAME}" > /dev/null 2>&1',
            returnStatus: true
        ) == 0
    }

    return exists
}

String jenkinsAgentLabel(def params, String awsLabel = 'docker', String hetznerLabel = 'docker-x64-min') {
    return params.JENKINS_AGENT == 'Hetzner' ? hetznerLabel : awsLabel
}

void kubectlCleanupCluster(String kubeconfig) {
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

void kubectlCleanupFailedNamespaces(Map testVariables, String testName, String clusterSuffix) {
    def clusterName = "${testVariables.cluster_name}-${clusterSuffix}"
    def kubeconfig = "${testVariables.kubeconfigPath ?: '/tmp'}/${clusterName}"

    echo "Cleaning failed test namespaces for ${testName} on ${clusterName}"

    withEnv([
        "FAILED_TEST_NAME=${testName}",
        "KUBECONFIG=${kubeconfig}"
    ]) {
        sh '''
            set +e

            if [ ! -s "$KUBECONFIG" ] || ! kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
                echo "Skipping failed test namespace cleanup: Kubernetes API is not reachable for $KUBECONFIG"
                exit 0
            fi

            kubectl get namespaces --request-timeout=10s --no-headers \
                | awk '{print $1}' \
                | while read -r namespace; do
                    case "$namespace" in
                        "$FAILED_TEST_NAME"-*|kuttl*)
                            # Finalizers on the operator resources (not on the namespace) are what keep it Terminating.
                            echo "Removing finalizers from resources in namespace: $namespace"
                            kubectl api-resources --verbs=list --namespaced -o name --request-timeout=10s \
                                | while read -r resource; do
                                    kubectl get "$resource" -n "$namespace" -o name --ignore-not-found --request-timeout=10s 2>/dev/null \
                                        | while read -r object; do
                                            kubectl patch "$object" -n "$namespace" --type=merge -p '{"metadata":{"finalizers":[]}}' --request-timeout=10s || true
                                        done
                                done

                            echo "Deleting namespace: $namespace"
                            kubectl delete namespace "$namespace" --force --grace-period=0 --wait=false --request-timeout=10s || true
                            ;;
                    esac
                done
        '''
    }
}

// Deletes the namespaces a finished test left behind when it ran with SKIP_DELETE=1, next to other
// tests on the same cluster. Only <test>-<hex id> namespaces match, so a test whose name starts with
// this one keeps its namespaces. Only the operator's own resources get their finalizers removed:
// they are what keeps a namespace Terminating, and listing every resource type takes minutes.
void kubectlDeleteTestNamespaces(String kubeconfig, String testName) {
    withEnv([
        "TEST_NAME=${testName}",
        "KUBECONFIG=${kubeconfig}"
    ]) {
        sh '''
            set +e

            if [ ! -s "$KUBECONFIG" ] || ! kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
                echo "Skipping namespace cleanup: Kubernetes API is not reachable for $KUBECONFIG"
                exit 0
            fi

            resources=$(kubectl api-resources --api-group=psmdb.percona.com --namespaced -o name --request-timeout=10s | paste -sd, -)

            kubectl get namespaces --request-timeout=10s --no-headers -o custom-columns=NAME:.metadata.name \
                | grep -E "^${TEST_NAME}-[0-9a-f]+(-.*)?\\$" \
                | while read -r namespace; do
                    echo "Deleting namespace: $namespace"
                    if [ -n "$resources" ]; then
                        kubectl get "$resources" -n "$namespace" -o name --request-timeout=10s 2>/dev/null \
                            | while read -r object; do
                                kubectl patch "$object" -n "$namespace" --type=merge -p '{"metadata":{"finalizers":[]}}' --request-timeout=10s
                            done
                    fi
                    kubectl delete namespace "$namespace" --wait=false --request-timeout=10s
                done

            # Cluster-wide operators of a shared cluster (SHARED_CLUSTER=1) are named after their
            # <test>-<hex>-op namespace.
            kubectl get clusterrole,clusterrolebinding --request-timeout=10s -o name \
                | grep -E -- "-${TEST_NAME}-[0-9a-f]+-op\\$" \
                | xargs -r kubectl delete --ignore-not-found --wait=false --request-timeout=10s
            exit 0
        '''
    }
}

// Removes the cluster-wide operator of a test that ran alone: it watches every namespace, so it
// would also reconcile the clusters of the tests that share the cluster next.
void kubectlDeleteClusterWideOperator(String kubeconfig, String operatorNamespace) {
    withEnv([
        "KUBECONFIG=${kubeconfig}",
        "OPERATOR_NS=${operatorNamespace}"
    ]) {
        sh '''
            set +e
            kubectl delete namespace "$OPERATOR_NS" --ignore-not-found --timeout=120s
            kubectl delete clusterrolebinding service-account-percona-server-mongodb-operator --ignore-not-found --request-timeout=30s
            kubectl delete clusterrole percona-server-mongodb-operator --ignore-not-found --request-timeout=30s
            exit 0
        '''
    }
}

// Removes cert-manager after a test that ran alone on a shared cluster. Other tests expect the
// operator to issue its own certificates, which it does only without cert-manager. Bash tests leave
// cert-manager behind with SKIP_DELETE=1. Selects by the label of the upstream manifests, so it
// needs no version.
void kubectlDeleteCertManager(String kubeconfig) {
    withEnv(["KUBECONFIG=${kubeconfig}"]) {
        sh '''
            set +e
            if ! kubectl get namespace cert-manager --request-timeout=10s >/dev/null 2>&1 \
                && [ -z "$(kubectl get crd -l app.kubernetes.io/instance=cert-manager -o name --request-timeout=10s 2>/dev/null)" ]; then
                exit 0
            fi
            echo "Removing cert-manager left by the previous test"
            selector=app.kubernetes.io/instance=cert-manager
            kubectl delete mutatingwebhookconfiguration,validatingwebhookconfiguration -l "$selector" --ignore-not-found --request-timeout=30s
            kubectl delete clusterrole,clusterrolebinding -l "$selector" --ignore-not-found --request-timeout=30s
            kubectl delete role,rolebinding -n kube-system -l "$selector" --ignore-not-found --request-timeout=30s
            # Wait: the operator decides how to issue certificates by whether these CRDs exist.
            kubectl delete crd -l "$selector" --ignore-not-found --timeout=180s
            kubectl delete namespace cert-manager --ignore-not-found --timeout=180s
            exit 0
        '''
    }
}

// Cluster load sampling. A background loop on the agent appends `kubectl top nodes` to a samples
// file every intervalSeconds: <epoch> <node> <cpu cores> <cpu%> <memory> <memory%>.
// GKE has metrics-server by default; on a cluster without it the file stays empty.
void startClusterLoadSampler(String kubeconfig, String samplesFile, Integer intervalSeconds) {
    // Written once here: the slots of a cluster read it at the same time.
    writeFile file: "${samplesFile}.awk", text: clusterLoadAwk()
    withEnv([
        "KUBECONFIG=${kubeconfig}",
        "LOAD_SAMPLES=${samplesFile}",
        "LOAD_INTERVAL=${intervalSeconds}"
    ]) {
        // JENKINS_NODE_COOKIE and BUILD_ID keep Jenkins from killing the loop when this step ends.
        sh '''
            rm -f "$LOAD_SAMPLES" "$LOAD_SAMPLES.pid"
            touch "$LOAD_SAMPLES"
            JENKINS_NODE_COOKIE=dontKillMe BUILD_ID=dontKillMe nohup bash -c '
                while true; do
                    now=$(date +%s)
                    kubectl top nodes --no-headers --request-timeout=20s 2>/dev/null \
                        | sed "s/^/$now /" >> "$LOAD_SAMPLES"
                    sleep "$LOAD_INTERVAL"
                done
            ' </dev/null >/dev/null 2>&1 &
            echo $! > "$LOAD_SAMPLES.pid"
        '''
    }
}

void stopClusterLoadSampler(String samplesFile) {
    withEnv(["LOAD_SAMPLES=${samplesFile}"]) {
        sh '''
            if [ -f "$LOAD_SAMPLES.pid" ]; then
                kill "$(cat "$LOAD_SAMPLES.pid")" 2>/dev/null || true
                rm -f "$LOAD_SAMPLES.pid"
            fi
        '''
    }
}

// Reduces the samples to one line per timestamp (the busiest node) and prints key=value lines.
// Time above a limit counts whole sample intervals; *_longest is the longest unbroken stretch.
String clusterLoadAwk() {
    return '''
BEGIN { cpu_peak = -1; mem_peak = -1; cluster_peak = -1 }
function flush() {
    if (ts == "") return
    n++
    if (maxc >= cpu_limit) { cpu_over += interval; cpu_run += interval } else cpu_run = 0
    if (cpu_run > cpu_longest) cpu_longest = cpu_run
    if (maxm >= mem_limit) { mem_over += interval; mem_run += interval } else mem_run = 0
    if (mem_run > mem_longest) mem_longest = mem_run
    mean = sumc / cnt
    mean_total += mean
    if (mean > cluster_peak) cluster_peak = mean
    if (maxc > cpu_peak) { cpu_peak = maxc; cpu_node = maxcn; cpu_at = ts }
    if (maxm > mem_peak) { mem_peak = maxm; mem_node = maxmn; mem_at = ts }
}
$4 ~ /%$/ && $6 ~ /%$/ && $1 >= from && (to == 0 || $1 <= to) {
    if ($1 != ts) { flush(); ts = $1; maxc = -1; maxm = -1; sumc = 0; cnt = 0 }
    c = $4 + 0
    m = $6 + 0
    if (c > maxc) { maxc = c; maxcn = $2 }
    if (m > maxm) { maxm = m; maxmn = $2 }
    sumc += c
    cnt++
}
END {
    flush()
    if (n == 0) { print "samples=0"; exit }
    printf "samples=%d\\n", n
    printf "cpu_peak=%d\\ncpu_node=%s\\ncpu_at=%s\\n", cpu_peak, cpu_node, cpu_at
    printf "cpu_over=%d\\ncpu_longest=%d\\n", cpu_over, cpu_longest
    printf "mem_peak=%d\\nmem_node=%s\\nmem_at=%s\\n", mem_peak, mem_node, mem_at
    printf "mem_over=%d\\nmem_longest=%d\\n", mem_over, mem_longest
    printf "cluster_cpu_peak=%d\\ncluster_cpu_mean=%d\\n", cluster_peak, mean_total / n
}
'''
}

// Summary of the samples between two epochs (0 = no limit). Empty map values when no samples.
Map clusterLoadSummary(String samplesFile, Map limits, def from = 0, def to = 0) {
    def awkFile = "${samplesFile}.awk"
    def output = sh(
        script: """
            if [ ! -s '${samplesFile}' ]; then echo samples=0; exit 0; fi
            awk -v cpu_limit=${limits.cpu} -v mem_limit=${limits.memory} \
                -v interval=${limits.interval} -v from=${from} -v to=${to} \
                -f '${awkFile}' '${samplesFile}'
        """,
        returnStdout: true
    ).trim()

    def summary = [samples: '0']
    for (line in output.split('\n')) {
        def parts = line.split('=', 2)
        if (parts.size() == 2) {
            summary[parts[0]] = parts[1]
        }
    }
    return summary
}

// Adds the cluster load while each test case ran to its Allure result: a "Cluster load"
// parameter with the busiest node's peaks, and an attachment with the per-node samples.
// The window starts one interval before the test case, so a short case still gets a sample.
void addClusterLoadToAllure(String resultsDir, String samplesFile, Map limits) {
    withEnv([
        "RESULTS=${resultsDir}",
        "LOAD_SAMPLES=${samplesFile}",
        "LOAD_AWK=${samplesFile}.awk",
        "CPU_LIMIT=${limits.cpu}",
        "MEM_LIMIT=${limits.memory}",
        "INTERVAL=${limits.interval}"
    ]) {
        sh '''
            [ -s "$LOAD_SAMPLES" ] || exit 0
            for result in "$RESULTS"/*-result.json; do
                [ -f "$result" ] || continue
                start=$(jq -r '((.start // 0) / 1000 | floor)' "$result")
                stop=$(jq -r '((.stop // 0) / 1000 | ceil)' "$result")
                from=$((start - INTERVAL))
                summary=$(awk -v cpu_limit="$CPU_LIMIT" -v mem_limit="$MEM_LIMIT" -v interval="$INTERVAL" \
                    -v from="$from" -v to="$stop" -f "$LOAD_AWK" "$LOAD_SAMPLES")
                get() { printf '%s\\n' "$summary" | sed -n "s/^$1=//p"; }
                [ "$(get samples)" != 0 ] || continue

                value="busiest node CPU $(get cpu_peak)%, memory $(get mem_peak)%"
                [ "$(get cpu_over)" = 0 ] || value="$value; CPU ${CPU_LIMIT}%+ for $(get cpu_over)s"
                [ "$(get mem_over)" = 0 ] || value="$value; memory ${MEM_LIMIT}%+ for $(get mem_over)s"

                attachment="$(basename "$result" -result.json)-cluster-load-attachment.txt"
                {
                    echo "Cluster load while the test case ran ($value)."
                    echo "Samples every ${INTERVAL}s from kubectl top nodes, seconds from the test case start."
                    echo
                    echo "second node cpu cpu% memory memory%"
                    awk -v from="$from" -v to="$stop" -v start="$start" \
                        '$1 >= from && $1 <= to { $1 = $1 - start; print }' "$LOAD_SAMPLES"
                } > "$RESULTS/$attachment"

                # excluded: the value differs between attempts, and Allure groups a retry with its
                # earlier attempts only when their parameters match.
                jq --arg value "$value" --arg source "$attachment" '
                    .parameters = ((.parameters // []) + [{"name": "Cluster load", "value": $value, "excluded": true}])
                    | .attachments = ((.attachments // []) + [{"name": "Cluster load", "source": $source, "type": "text/plain"}])
                ' "$result" > "$result.tmp" && mv "$result.tmp" "$result"
            done
        '''
    }
}

// Sets the Allure host label to the cluster and thread to the slot in the results this attempt
// wrote (newer than the marker file). The timeline groups tests by these labels; pytest sets them
// to the agent's hostname and its own process. A problem here only prints a warning.
void labelAllureResults(String resultsDir, String marker, String host, String thread) {
    try {
        withEnv(["RESULTS=${resultsDir}", "MARKER=${marker}", "LABEL_HOST=${host}", "LABEL_THREAD=${thread}"]) {
            sh '''
                [ -d "$RESULTS" ] && [ -f "$MARKER" ] || exit 0
                find "$RESULTS" -maxdepth 1 -name '*-result.json' -newer "$MARKER" | while read -r result; do
                    jq --arg host "$LABEL_HOST" --arg thread "$LABEL_THREAD" '
                        .labels = ([(.labels // [])[] | select(.name != "host" and .name != "thread")]
                            + [{"name": "host", "value": $host}, {"name": "thread", "value": $thread}])
                    ' "$result" > "$result.tmp" && mv "$result.tmp" "$result"
                done
                rm -f "$MARKER"
            '''
        }
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException exc) {
        throw exc
    } catch (labelErr) {
        echo "Warning: failed to label the Allure results in ${resultsDir}: ${labelErr}"
    }
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
