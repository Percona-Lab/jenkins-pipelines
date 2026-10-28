library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

// Checks what users get after a PMM 3 release: PMM_VERSION, which pmm3-release passes when
// it starts this job, or the newest percona/pmm-server tag on Docker Hub. What people publish
// after the GA job is waited for, up to PUBLISH_WAIT_MINUTES, before it is checked.
// Each branch of 'Post-release checks' reports on its own:
//
//   Release notes        docs release notes and the percona/pmm GitHub release
//   Downloads and repos  percona.com/downloads files and the pmm3-client yum/apt repos
//   GSSAPI tarballs      the private OL8/OL9 dynamic client tarballs: checksums, then a
//                        smoke run of their binaries on Oracle Linux 8 and 9
//   Docker               fresh install through percona.com/get/pmm; when it passes, fires the
//                        RELEASE_PRS_ROUTINE_ID Routine to open the release PRs,
//                        then waits for the release in the PROD Version Service, then
//                        previous -> latest upgrade with the @post-release specs run before it
//
// Ends with a one-line result in #pmm-internal.
//   Helm                 waits for the bumped pmm and pmm-ha charts, then runs
//                        k8s/helm-post-release.bats from pmm-qa on minikube: released chart
//                        install, previous -> latest chart upgrade
//   HA                   waits for the bumped charts, then two pmm3-ha-upgrade-tests builds on
//                        CLUSTER_TYPE: the released pmm-ha chart installed, and the previous
//                        release's chart upgraded to it

void startPmmServer(String name, String image, String volume) {
    sh """
        docker run --detach --restart always \
            --network="pmm-qa" \
            -e PMM_DEBUG=1 \
            -e PMM_ENABLE_UPDATES=1 \
            -e PMM_ENABLE_TELEMETRY=0 \
            -e PMM_ENABLE_INTERNAL_PG_QAN=1 \
            -e GF_SECURITY_ADMIN_PASSWORD="\${ADMIN_PASSWORD}" \
            --publish 443:8443 \
            --volume ${volume}:/srv \
            --name ${name} \
            ${image}
    """
}

void waitForPmmReady(String name) {
    sh """
        if ! timeout 100 bash -c 'until [ "\$(curl -ks -o /dev/null -w "%{http_code}" https://127.0.0.1/v1/server/readyz)" = "200" ]; do sleep 5; done'; then
            echo "PMM Server did not become ready within the timeout, dumping logs" >&2
            docker logs ${name} || true
            exit 1
        fi
    """
}

// Polls a check script every 5 minutes; the GitHub API allows 60 unauthenticated calls an hour.
void waitForPublication(String what, String check) {
    echo "Waiting up to ${params.PUBLISH_WAIT_MINUTES ?: '240'} minutes for ${what}"
    timeout(time: (params.PUBLISH_WAIT_MINUTES ?: '240').toInteger(), unit: 'MINUTES') {
        waitUntil(quiet: true) {
            if (sh(returnStatus: true, script: check) == 0) {
                return true
            }
            sleep(time: 5, unit: 'MINUTES')
            return false
        }
    }
}

void waitForReleasedCharts() {
    waitForPublication("percona/pmm and percona/pmm-ha charts that ship ${env.PMM_SERVER_LATEST}", '''
        helm repo update percona > /dev/null
        for chart in pmm pmm-ha; do
            helm search repo "percona/${chart}" --versions --output json \
                | jq -e --arg name "percona/${chart}" --arg v "${PMM_SERVER_LATEST}" \
                    'any(.[]; .name == $name and .app_version == $v)' > /dev/null || exit 1
        done
    ''')
}

void verifyPmmVersion(String expected, String password = '${ADMIN_PASSWORD}') {
    sh """
        VERSION=\$(curl -sk --user admin:${password} https://127.0.0.1/v1/server/version | jq -r '.version' | awk -F "-" '{print \$1}')
        echo "PMM Server version: \${VERSION}, expected: ${expected}"
        if [[ "\${VERSION}" != "${expected}" ]]; then
            echo "Version mismatch" >&2
            exit 1
        fi
    """
}

pipeline {
    agent {
        label params.USE_ONDEMAND != false ? 'agent-amd64-ondemand' : 'agent-amd64'
    }
    parameters {
        booleanParam(
            defaultValue: true,
            description: 'Use on-demand agents instead of spot, here and in the HA builds',
            name: 'USE_ONDEMAND')
        string(
            defaultValue: 'admin',
            description: 'PMM Server admin password.',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: '',
            description: 'Released PMM version to check, e.g. 3.11.0. Empty takes the newest 3.x.y percona/pmm-server tag on Docker Hub; pmm3-release passes its VERSION.',
            name: 'PMM_VERSION')
        string(
            defaultValue: 'main',
            description: 'pmm-qa git branch.',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: '240',
            description: 'Minutes each check waits for what people publish after the GA job: the docs release notes and GitHub release, the bumped pmm and pmm-ha charts, and the PROD Version Service entry, whose PR is merged once the smoke checks of this job pass.',
            name: 'PUBLISH_WAIT_MINUTES')
        choice(
            choices: ['OpenShift', 'EKS'],
            description: 'Kubernetes platform of the HA builds',
            name: 'CLUSTER_TYPE')
    }
    options {
        skipDefaultCheckout()
        // The waits for publications, then the HA clusters.
        timeout(time: 8, unit: 'HOURS')
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    env.ADMIN_PASSWORD = params.ADMIN_PASSWORD
                    def tags = sh(
                        script: '''set -e
json=$(curl -sf "https://registry.hub.docker.com/v2/repositories/percona/pmm-server/tags?page_size=100")
echo "$json" | jq -e '.results' >/dev/null
echo "$json" | jq -r '.results[].name' | grep -E '^3\\.[0-9]+\\.[0-9]+$' | sort -V || true''',
                        returnStdout: true
                    ).trim().split('\n').findAll { it }
                    def latestVersion = params.PMM_VERSION?.trim() ?: tags[-1]
                    def latestIndex = tags.indexOf(latestVersion)
                    if (latestIndex < 1) {
                        error("percona/pmm-server:${latestVersion} and a release before it must be on Docker Hub; found ${tags}")
                    }
                    def prevVersion = tags[latestIndex - 1]
                    env.PMM_SERVER_LATEST = latestVersion
                    env.PMM_SERVER_PREVIOUS = prevVersion
                    env.DOCKER_TAG = "percona/pmm-server:${prevVersion}"
                    env.DOCKER_TAG_UPGRADE = "percona/pmm-server:${latestVersion}"
                    currentBuild.description = "Post-release tests: ${prevVersion} -> ${latestVersion}"
                }
                sh '''
                    sudo rm -rf /srv/pmm-qa || :
                    sudo mkdir -p /srv/pmm-qa
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                        sudo chown -R ec2-user:ec2-user .
                    popd
                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium || :

                    bash /srv/pmm-qa/k8s/install_k8s_tools.sh --helm --sudo > /dev/null
                    helm repo add percona https://percona.github.io/percona-helm-charts/ --force-update
                '''
            }
        }
        stage('Post-release checks') {
            parallel {
                stage('Release notes') {
                    steps {
                        waitForPublication('the release notes and the GitHub release', '''
                            curl -sf "https://docs.percona.com/percona-monitoring-and-management/3/release-notes/${PMM_SERVER_LATEST}.html" \
                                | grep -qF "<title>PMM ${PMM_SERVER_LATEST} " || exit 1
                            curl -sf "https://api.github.com/repos/percona/pmm/releases/tags/v${PMM_SERVER_LATEST}" \
                                | jq -e '.draft == false and .prerelease == false and (.body // "" | length) > 0' > /dev/null
                        ''')
                        sh '''
                            notes="https://docs.percona.com/percona-monitoring-and-management/3/release-notes/${PMM_SERVER_LATEST}.html"
                            # No -L: a missing page redirects to a 404 page that answers 200.
                            title=$(curl -sf "${notes}" | grep -oE "<title>[^<]*</title>" | head -1)
                            echo "${notes}: ${title}"
                            echo "${title}" | grep -qF "PMM ${PMM_SERVER_LATEST} " \
                                || { echo "Release notes for ${PMM_SERVER_LATEST} are not published" >&2; exit 1; }

                            release=$(curl -sf "https://api.github.com/repos/percona/pmm/releases/tags/v${PMM_SERVER_LATEST}") \
                                || { echo "GitHub release v${PMM_SERVER_LATEST} is not published in percona/pmm" >&2; exit 1; }
                            echo "${release}" | jq '{tag_name, name, draft, prerelease, published_at, html_url}'
                            echo "${release}" | jq -e '.draft == false and .prerelease == false and (.body // "" | length) > 0' >/dev/null \
                                || { echo "GitHub release v${PMM_SERVER_LATEST} is a draft, a prerelease or has no notes" >&2; exit 1; }
                        '''
                    }
                }
                stage('Downloads and repos') {
                    steps {
                        sh '''
                            V=${PMM_SERVER_LATEST}
                            failed=0
                            check() {
                                local code
                                code=$(curl -s -o /dev/null -w "%{http_code}" -I "$1")
                                echo "${code} $1"
                                [ "${code}" = "200" ] || failed=1
                            }

                            downloads="https://downloads.percona.com/downloads/pmm3/${V}"
                            for f in \
                                binary/tarball/pmm-client-${V}-x86_64.tar.gz \
                                binary/tarball/pmm-client-${V}-x86_64.tar.gz.sha256sum \
                                binary/tarball/pmm-client-${V}-aarch64.tar.gz \
                                binary/tarball/pmm-client-${V}-aarch64.tar.gz.sha256sum \
                                docker/pmm-server-${V}.docker \
                                docker/pmm-server-${V}.sha256sum \
                                docker/pmm-server-${V}-amd64.docker \
                                docker/pmm-server-${V}-arm64.docker \
                                docker/pmm-client-${V}-amd64.docker \
                                docker/pmm-client-${V}-arm64.docker; do
                                check "${downloads}/${f}"
                            done

                            for el in 8 9 10; do
                                for arch in x86_64 aarch64; do
                                    check "https://repo.percona.com/pmm3-client/yum/release/${el}/RPMS/${arch}/pmm-client-${V}-1.el${el}.${arch}.rpm"
                                done
                            done

                            pool=$(curl -sfL https://repo.percona.com/pmm3-client/apt/pool/main/p/pmm-client/)
                            for arch in amd64 arm64; do
                                debs=$(echo "${pool}" | grep -oE "pmm-client_${V}-[^\\"<]*_${arch}\\.deb" | sort -u)
                                echo "apt ${arch}: $(echo ${debs})"
                                [ -n "${debs}" ] || { echo "No pmm-client ${V} ${arch} .deb in the apt pool" >&2; failed=1; }
                            done

                            exit ${failed}
                        '''
                    }
                }
                stage('GSSAPI tarballs') {
                    steps {
                        // Private downloads area: reachable only the way pmm3-release uploads to it.
                        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                            sh '''
                                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                                    "ssh -p 2222 -o StrictHostKeyChecking=no jenkins-deploy.jenkins-deploy.web.r.int.percona.com bash -s -- ${PMM_SERVER_LATEST}" <<'ENDSSH'
                                    cd /data/downloads/private/pmm3-client-gssapi-tarballs
                                    failed=0
                                    for os in ol8 ol9; do
                                        tarballs=$(ls pmm-client-${1}-*${os}*.tar.gz 2>/dev/null)
                                        if [ "$(echo "${tarballs}" | grep -c .)" != "1" ]; then
                                            echo "Expected one ${1} ${os} tarball, found: ${tarballs:-none}" >&2
                                            failed=1
                                            continue
                                        fi
                                        tarball=${tarballs}
                                        if [ ! -s "${tarball}.sha256sum" ]; then
                                            echo "Missing ${tarball}.sha256sum" >&2
                                            failed=1
                                            continue
                                        fi
                                        expected=$(awk '{print $1}' "${tarball}.sha256sum")
                                        actual=$(sha256sum "${tarball}" | awk '{print $1}')
                                        if [ "${expected}" != "${actual}" ]; then
                                            echo "${tarball}: sha256 ${actual}, .sha256sum says ${expected}" >&2
                                            failed=1
                                            continue
                                        fi
                                        if ! tar -tzf "${tarball}" | grep -c '/bin/pmm-agent$' >/dev/null; then
                                            echo "${tarball} has no bin/pmm-agent" >&2
                                            failed=1
                                            continue
                                        fi
                                        ls -l "${tarball}"
                                    done
                                    exit ${failed}
ENDSSH

                                for os in ol8 ol9; do
                                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                                        "ssh -p 2222 -o StrictHostKeyChecking=no jenkins-deploy.jenkins-deploy.web.r.int.percona.com 'cat /data/downloads/private/pmm3-client-gssapi-tarballs/pmm-client-${PMM_SERVER_LATEST}-*${os}*.tar.gz'" \
                                        > gssapi-${os}.tar.gz
                                done
                            '''
                        }
                        sh '''
                            for os in ol8 ol9; do
                                echo "--- ${os} ---"
                                docker run --rm \
                                    -e VERSION="${PMM_SERVER_LATEST}" \
                                    -v "$(pwd)/gssapi-${os}.tar.gz:/tmp/client.tar.gz:ro" \
                                    "oraclelinux:${os#ol}" bash -ec '
                                        dnf install -y -q tar gzip findutils krb5-libs cyrus-sasl-lib cyrus-sasl-gssapi >/dev/null
                                        mkdir /tmp/client
                                        tar -xzf /tmp/client.tar.gz -C /tmp/client
                                        bin=$(dirname "$(find /tmp/client -path "*/bin/pmm-agent" | head -1)")
                                        command -v ldd > /dev/null
                                        missing=$(for b in "${bin}"/*; do ldd "${b}" 2>/dev/null | grep "not found" | sed "s|^|$(basename "${b}"): |"; done)
                                        if [ -n "${missing}" ]; then
                                            echo "Unresolved shared libraries:" >&2
                                            echo "${missing}" >&2
                                            exit 1
                                        fi
                                        for b in pmm-admin pmm-agent; do
                                            out=$("${bin}/${b}" --version 2>&1)
                                            echo "${out}"
                                            echo "${out}" | grep -qF "${VERSION}" || { echo "${b} does not report ${VERSION}" >&2; exit 1; }
                                        done
                                    '
                            done
                        '''
                    }
                    post {
                        always {
                            sh 'rm -f gssapi-ol8.tar.gz gssapi-ol9.tar.gz'
                        }
                    }
                }
                stage('Docker') {
                    stages {
                        stage('Fresh install through get/pmm') {
                            steps {
                                sh 'docker network create pmm-qa'
                                // A failure here must not stop the upgrade check below.
                                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                                    sh '''
                                        served() { curl -fsSL https://www.percona.com/get/pmm | sha256sum | awk '{print $1}'; }
                                        expected=$(curl -fsSL https://raw.githubusercontent.com/percona/pmm/main/get-pmm.sh | sha256sum | awk '{print $1}')
                                        if [ "$(served)" != "${expected}" ]; then
                                            echo "percona.com/get/pmm serves a stale get-pmm.sh, refreshing its cache"
                                            curl -fsS https://percona.com/get/make.php > /dev/null
                                            if [ "$(served)" != "${expected}" ]; then
                                                echo "percona.com/get/pmm still differs from get-pmm.sh on percona/pmm main" >&2
                                                exit 1
                                            fi
                                        fi

                                        # get-pmm.sh installs the floating :3 tag, which the release moves.
                                        hub=https://registry.hub.docker.com/v2/repositories/percona/pmm-server/tags
                                        floating=$(curl -fsS "${hub}/3" | jq -r .digest)
                                        released=$(curl -fsS "${hub}/${PMM_SERVER_LATEST}" | jq -r .digest)
                                        echo "percona/pmm-server:3 ${floating}, :${PMM_SERVER_LATEST} ${released}"
                                        if [ "${floating}" != "${released}" ]; then
                                            echo "percona/pmm-server:3 does not point at ${PMM_SERVER_LATEST}" >&2
                                            exit 1
                                        fi

                                        curl -fsSL https://www.percona.com/get/pmm -o get-pmm.sh
                                        bash get-pmm.sh --no-color --name pmm-server-fresh --volume-name pmm-fresh-volume --network-name pmm-qa
                                    '''
                                    waitForContainer('pmm-server-fresh', 'pmm-managed entered RUNNING state')
                                    waitForPmmReady('pmm-server-fresh')
                                    // get-pmm.sh leaves the default admin password.
                                    verifyPmmVersion(env.PMM_SERVER_LATEST, 'admin')
                                    sh '''
                                        updates=$(curl -sk --user admin:admin "https://127.0.0.1/v1/server/updates?force=true")
                                        echo "${updates}" | jq '{update_available, installed: .installed.version, latest: .latest.version}'
                                        if ! echo "${updates}" | jq -e '.update_available == false' >/dev/null; then
                                            echo "A fresh ${PMM_SERVER_LATEST} install should have no update available" >&2
                                            exit 1
                                        fi
                                    '''
                                    env.FRESH_INSTALL_OK = 'true'
                                }
                            }
                            post {
                                always {
                                    sh '''
                                        docker logs pmm-server-fresh > pmm-server-fresh.log 2>&1 || true
                                        docker rm -f pmm-server-fresh || true
                                        docker volume rm pmm-fresh-volume || true
                                    '''
                                }
                            }
                        }
                        stage('Open the release PRs') {
                            when { expression { env.FRESH_INSTALL_OK == 'true' } }
                            steps {
                                script {
                                    // Each command runs a pmm-ai skill in the RELEASE_PRS_ROUTINE Routine, the way
                                    // people opened these PRs together once the GA images were public. Without its
                                    // credentials in Jenkins the PRs are opened by hand; the waits cover both.
                                    // The Helm bump command joins this list once its skill exists.
                                    def commands = []
                                    def inVersionService = sh(returnStatus: true, script: '''
                                        curl -fsS https://check.percona.com/metadata/v2/pmm-server \
                                            | jq -e --arg v "${PMM_SERVER_LATEST}" 'any(.versions[]; .version == $v)' > /dev/null
                                    ''') == 0
                                    if (!inVersionService) {
                                        def notes = "https://raw.githubusercontent.com/percona/pmm/v${env.PMM_SERVER_LATEST}/documentation/docs/release-notes/${env.PMM_SERVER_LATEST}.md"
                                        commands.add("/pmm-release:version-pr-ga ${env.PMM_SERVER_LATEST} ${notes}")
                                    }
                                    commands.each { command ->
                                        try {
                                            writeFile file: 'release-pr.json', text: groovy.json.JsonOutput.toJson([text: command])
                                            withCredentials([
                                                string(credentialsId: 'RELEASE_PRS_ROUTINE_ID', variable: 'ROUTINE_ID'),
                                                string(credentialsId: 'RELEASE_PRS_ROUTINE_TOKEN', variable: 'ROUTINE_TOKEN')
                                            ]) {
                                                sh '''
                                                    set -euo pipefail
                                                    curl -fsS --connect-timeout 10 --max-time 60 -X POST \
                                                        "https://api.anthropic.com/v1/claude_code/routines/${ROUTINE_ID}/fire" \
                                                        -H "Authorization: Bearer ${ROUTINE_TOKEN}" \
                                                        -H "anthropic-version: 2023-06-01" \
                                                        -H "anthropic-beta: experimental-cc-routine-2026-04-01" \
                                                        -H "Content-Type: application/json" \
                                                        --data @release-pr.json
                                                '''
                                            }
                                            echo "Started: ${command}"
                                        } catch (Throwable e) {
                                            echo "Could not start '${command}', open that PR by hand: ${e.message}"
                                        }
                                    }
                                }
                            }
                        }
                        stage('Wait for the PROD Version Service') {
                            steps {
                                // percona-version-service's production PR is merged after the smoke checks
                                // pass; PMM-T2200 finds the update through it, so the upgrade waits for it.
                                waitForPublication('the release in the PROD Version Service', '''
                                    curl -fsS https://check.percona.com/metadata/v2/pmm-server \
                                        | jq -e --arg v "${PMM_SERVER_LATEST}" 'any(.versions[]; .version == $v)' > /dev/null
                                ''')
                                sh '''
                                    entry=$(curl -fsS https://check.percona.com/metadata/v2/pmm-server \
                                        | jq -c --arg v "${PMM_SERVER_LATEST}" '.versions[] | select(.version == $v) | .imageInfo')
                                    echo "${entry}" | jq .
                                    digest=$(curl -fsS "https://registry.hub.docker.com/v2/repositories/percona/pmm-server/tags/${PMM_SERVER_LATEST}" | jq -r .digest)
                                    if ! echo "${entry}" | jq -e --arg image "${DOCKER_TAG_UPGRADE}" --arg digest "${digest}" \
                                        '.imagePath == $image and .imageHash == $digest' > /dev/null; then
                                        echo "Version Service entry does not match ${DOCKER_TAG_UPGRADE} ${digest} on Docker Hub" >&2
                                        exit 1
                                    fi
                                    code=$(curl -s -o /dev/null -w "%{http_code}" "https://check.percona.com/release-notes/v1/pmm/${PMM_SERVER_LATEST}")
                                    if [ "${code}" != "200" ]; then
                                        echo "Version Service has no release notes for ${PMM_SERVER_LATEST} (HTTP ${code})" >&2
                                        exit 1
                                    fi
                                '''
                            }
                        }
                        stage('Start previous release') {
                            steps {
                                sh 'docker volume create pmm-volume'
                                startPmmServer('pmm-server', env.DOCKER_TAG, 'pmm-volume')
                                waitForContainer('pmm-server', 'pmm-managed entered RUNNING state')
                                script {
                                    env.SERVER_IP = "127.0.0.1"
                                    env.PMM_UI_URL = "https://${env.SERVER_IP}/"
                                    env.PMM_URL = "https://admin:${env.ADMIN_PASSWORD}@${env.SERVER_IP}"
                                }
                                waitForPmmReady('pmm-server')
                            }
                        }
                        stage('Install dependencies') {
                            steps {
                                sh '''
                                    pushd /srv/pmm-qa/e2e_tests
                                        npm ci
                                        npx playwright install chromium
                                    popd
                                '''
                            }
                        }
                        stage('Run pre-upgrade post-release tests') {
                            steps {
                                sh '''
                                    pushd /srv/pmm-qa/e2e_tests
                                        export CI=true
                                        npx playwright test --grep "@post-release|@downloads"
                                    popd
                                '''
                            }
                        }
                        stage('Docker way upgrade') {
                            steps {
                                sh '''
                                    docker stop pmm-server
                                    docker rm pmm-server
                                    docker pull ${DOCKER_TAG_UPGRADE}
                                '''
                                startPmmServer('pmm-server', env.DOCKER_TAG_UPGRADE, 'pmm-volume')
                                sh 'docker ps -a'
                                waitForPmmReady('pmm-server')
                                verifyPmmVersion(env.PMM_SERVER_LATEST)
                            }
                        }
                    }
                }
                stage('Helm') {
                    stages {
                        stage('Wait for the bumped charts') {
                            steps {
                                waitForReleasedCharts()
                            }
                        }
                        stage('Helm on minikube') {
                            agent {
                                label params.USE_ONDEMAND != false ? 'agent-amd64-ondemand' : 'agent-amd64'
                            }
                            options {
                                timeout(time: 60, unit: 'MINUTES')
                            }
                            steps {
                                deleteDir()
                                dir('pmm-qa') {
                                    git poll: false, branch: params.PMM_QA_GIT_BRANCH, url: 'https://github.com/percona/pmm-qa.git'
                                }
                                sh '''
                                    git clone --depth 1 https://github.com/bats-core/bats-core.git bats-core

                                    cd pmm-qa/k8s
                                    ./setup_bats_libs.sh
                                    bash -x ./install_k8s_tools.sh --kubectl --helm --minikube --sudo

                                    minikube start --driver=docker
                                    kubectl wait --for=condition=Ready node --all --timeout=180s

                                    export BATS_LIB_PATH="$(pwd)/lib"
                                    ${WORKSPACE}/bats-core/bin/bats --tap helm-post-release.bats
                                '''
                            }
                            post {
                                always {
                                    sh 'minikube delete || true'
                                    deleteDir()
                                }
                            }
                        }
                    }
                }
                stage('HA') {
                    steps {
                        waitForReleasedCharts()
                        script {
                            def previousChart = sh(returnStdout: true, script: '''
                                helm search repo percona/pmm-ha --versions --output json \
                                    | jq -r --arg v "${PMM_SERVER_PREVIOUS}" \
                                        'map(select(.name == "percona/pmm-ha" and .app_version == $v))[0].version // empty'
                            ''').trim()
                            if (!previousChart) {
                                error "No published pmm-ha chart ships ${env.PMM_SERVER_PREVIOUS}"
                            }
                            echo "HA upgrade: pmm-ha ${previousChart} (${env.DOCKER_TAG}) -> latest published chart (${env.DOCKER_TAG_UPGRADE})"
                            parallel(
                                'HA install': {
                                    build job: 'pmm3-ha-upgrade-tests', parameters: [
                                        string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
                                        string(name: 'CLUSTER_TYPE', value: params.CLUSTER_TYPE ?: 'OpenShift'),
                                        string(name: 'RELEASE_DOCKER_VERSION', value: env.DOCKER_TAG_UPGRADE),
                                        booleanParam(name: 'RUN_UPGRADE', value: false),
                                        booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND != false),
                                        string(name: 'CLIENTS', value: ''),
                                        string(name: 'HA_TAGS', value: ''),
                                    ]
                                },
                                'HA upgrade': {
                                    // "latest" upgrades both charts from the published repo.
                                    build job: 'pmm3-ha-upgrade-tests', parameters: [
                                        string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
                                        string(name: 'CLUSTER_TYPE', value: params.CLUSTER_TYPE ?: 'OpenShift'),
                                        string(name: 'RELEASE_DOCKER_VERSION', value: env.DOCKER_TAG),
                                        string(name: 'CHART_VERSION', value: previousChart),
                                        string(name: 'DOCKER_VERSION', value: env.DOCKER_TAG_UPGRADE),
                                        string(name: 'HELM_CHART_BRANCH', value: 'latest'),
                                        booleanParam(name: 'RUN_UPGRADE', value: true),
                                        booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND != false),
                                        string(name: 'HA_TAGS', value: ''),
                                    ]
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            sh '''
                if [[ -n "${PMM_URL:-}" ]]; then
                    curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                fi
                docker exec pmm-server cat /srv/logs/pmm-managed.log > pmm-managed-full.log || true
                tar -zcvf playwright-report.tar.gz /srv/pmm-qa/e2e_tests/playwright-report || true
            '''
            script {
                archiveArtifacts artifacts: 'logs.zip', allowEmptyArchive: true
                archiveArtifacts artifacts: 'pmm-managed-full.log', allowEmptyArchive: true
                archiveArtifacts artifacts: 'pmm-server-fresh.log', allowEmptyArchive: true
                archiveArtifacts artifacts: 'playwright-report.tar.gz', allowEmptyArchive: true
                def result = currentBuild.currentResult
                slackSend botUser: true, channel: '#pmm-internal', color: result == 'SUCCESS' ? '#00FF00' : '#FF0000',
                          message: "PMM ${env.PMM_SERVER_LATEST ?: params.PMM_VERSION} post-release tests: ${result}. ${env.BUILD_URL}"
            }
            deleteDir()
        }
    }
}
