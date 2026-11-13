// runRemoteJob.groovy — Trigger a parameterized job on another Jenkins master and wait for it
//
// Starts the remote job through its REST API (buildWithParameters), waits until the queued
// build starts and finishes, and fails the calling stage unless the remote result is SUCCESS.
// With allowUnstable: true, a remote UNSTABLE result marks the calling stage UNSTABLE instead.
// Parameters not passed use the remote job's defaults.
//
// The credential must be a "Secret text" holding "user:apitoken" for the remote Jenkins
// (e.g. JNKPERCONA_PS80_TOKEN for ps80.cd.percona.com).
//
// The remote build URL is stored in env.REMOTE_BUILD_URL (also when the remote build fails)
// and returned, so it can be used in notifications and build descriptions.
//
// Usage in pipelines (wrap the stage in a timeout, the step itself waits indefinitely):
//   runRemoteJob(server: 'https://ps80.cd.percona.com',
//                job: 'orchestrator_docker',
//                credentialsId: 'JNKPERCONA_PS80_TOKEN',
//                parameters: [DOCKER_ACC: 'perconalab', ORCHESTRATOR_VERSION: '3.2.6-24'],
//                allowUnstable: false,  // optional, default false
//                pollSeconds: 30)       // optional, default 30

def call(Map args = [:]) {
    def server        = args.get('server', '')
    def job           = args.get('job', '')
    def credentialsId = args.get('credentialsId', '')
    def parameters    = args.get('parameters', [:])
    def pollSeconds   = args.get('pollSeconds', 30)
    def allowUnstable = args.get('allowUnstable', false)

    if (!server || !job || !credentialsId) {
        error "runRemoteJob: 'server', 'job' and 'credentialsId' are required"
    }

    def envList = [
        "RJ_SERVER=${server}",
        "RJ_JOB=${job}",
        "RJ_POLL_SECONDS=${pollSeconds}",
    ] + paramsToEnv(parameters)

    def result = ''
    try {
        withEnv(envList) {
            withCredentials([string(credentialsId: credentialsId, variable: 'TOKEN')]) {
                sh '''#!/bin/bash
                    set -e
                    rm -f .remote-job-headers .remote-build-url .remote-build-result

                    ARGS=()
                    echo "Triggering ${RJ_SERVER}/job/${RJ_JOB} with parameters:"
                    for ((i = 0; i < RJ_PARAM_COUNT; i++)); do
                        v="RJ_PARAM_${i}"
                        echo "  ${!v}"
                        ARGS+=(--data-urlencode "${!v}")
                    done

                    HTTP_CODE=$(curl -sS -u "${TOKEN}" -X POST -D .remote-job-headers -o /dev/null -w '%{http_code}' \
                        "${RJ_SERVER}/job/${RJ_JOB}/buildWithParameters" "${ARGS[@]}")
                    if [ "${HTTP_CODE}" != "201" ]; then
                        echo "ERROR: failed to trigger remote job, HTTP code: ${HTTP_CODE}"
                        exit 1
                    fi

                    QUEUE_URL=$(grep -i '^location:' .remote-job-headers | tr -d '\\r' | awk '{print $2}')
                    rm -f .remote-job-headers
                    if [ -z "${QUEUE_URL}" ]; then
                        echo "ERROR: no queue item location returned by remote Jenkins"
                        exit 1
                    fi
                    case "${QUEUE_URL}" in */) ;; *) QUEUE_URL="${QUEUE_URL}/" ;; esac
                    echo "Remote job queued: ${QUEUE_URL}"

                    RJ_BUILD_URL=""
                    while [ -z "${RJ_BUILD_URL}" ]; do
                        sleep 10
                        QUEUE_JSON=$(curl -sS -u "${TOKEN}" "${QUEUE_URL}api/json?tree=cancelled,executable%5Burl%5D" || true)
                        if echo "${QUEUE_JSON}" | grep -q '"cancelled":true'; then
                            echo "ERROR: remote queue item was cancelled"
                            exit 1
                        fi
                        RJ_BUILD_URL=$(echo "${QUEUE_JSON}" | sed -n 's/.*"executable":{[^}]*"url":"\\([^"]*\\)".*/\\1/p')
                    done
                    echo "${RJ_BUILD_URL}" > .remote-build-url
                    echo "Remote build started: ${RJ_BUILD_URL}"

                    while true; do
                        sleep "${RJ_POLL_SECONDS}"
                        BUILD_JSON=$(curl -sS -u "${TOKEN}" "${RJ_BUILD_URL}api/json?tree=building,result" || true)
                        if echo "${BUILD_JSON}" | grep -q '"building":false'; then
                            break
                        fi
                        echo "Remote build still running: ${RJ_BUILD_URL}"
                    done

                    RESULT=$(echo "${BUILD_JSON}" | sed -n 's/.*"result":"\\([A-Z_]*\\)".*/\\1/p')
                    echo "Remote build ${RJ_BUILD_URL} finished with result: ${RESULT:-UNKNOWN}"
                    echo "${RESULT:-UNKNOWN}" > .remote-build-result
                '''
            }
        }
    } finally {
        if (fileExists('.remote-build-url')) {
            env.REMOTE_BUILD_URL = readFile('.remote-build-url').trim()
            sh 'rm -f .remote-build-url'
        }
        if (fileExists('.remote-build-result')) {
            result = readFile('.remote-build-result').trim()
            sh 'rm -f .remote-build-result'
        }
    }

    if (result == 'UNSTABLE' && allowUnstable) {
        unstable "Remote build ${env.REMOTE_BUILD_URL} finished UNSTABLE"
    } else if (result != 'SUCCESS') {
        error "Remote build ${env.REMOTE_BUILD_URL} finished with ${result ?: 'UNKNOWN'}"
    }
    return env.REMOTE_BUILD_URL
}

// Turns [K1: 'v1', K2: 'v2'] into RJ_PARAM_COUNT=2, RJ_PARAM_0=K1=v1, RJ_PARAM_1=K2=v2
// so values reach the shell through the environment instead of Groovy interpolation.
@NonCPS
private List paramsToEnv(Map parameters) {
    def envList = ["RJ_PARAM_COUNT=${parameters.size()}"]
    parameters.eachWithIndex { key, value, i ->
        envList << "RJ_PARAM_${i}=${key}=${value}"
    }
    return envList.collect { it.toString() }
}
