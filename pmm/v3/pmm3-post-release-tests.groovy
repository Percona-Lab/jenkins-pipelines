library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

// Checks what users get after a PMM 3 release, against the newest percona/pmm-server
// tag on Docker Hub. Each branch of 'Post-release checks' reports on its own:
//
//   Release notes        docs release notes and the percona/pmm GitHub release
//   Downloads and repos  percona.com/downloads files and the pmm3-client yum/apt repos
//   GSSAPI tarballs      the private OL8/OL9 dynamic client tarballs and their checksums
//   Docker               fresh install of the release, then previous -> latest upgrade
//                        with the @post-release specs run before it
//   Helm                 k8s/helm-post-release.bats from pmm-qa on minikube: released
//                        chart install, previous -> latest chart upgrade
//   HA                   pmm3-ha-helm-upgrade-tests with RUN_UPGRADE=false: released
//                        pmm-ha chart on EKS. HA upgrade is not supported yet.

void startPmmServer(String name, String image, String volume) {
    sh """
        docker run --detach --restart always \
            --network="pmm-qa" \
            -e PMM_DEBUG=1 \
            -e PMM_ENABLE_UPDATES=1 \
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

void verifyPmmVersion(String expected) {
    sh """
        VERSION=\$(curl -sk --user admin:\${ADMIN_PASSWORD} https://127.0.0.1/v1/server/version | jq -r '.version' | awk -F "-" '{print \$1}')
        echo "PMM Server version: \${VERSION}, expected: ${expected}"
        if [[ "\${VERSION}" != "${expected}" ]]; then
            echo "Version mismatch" >&2
            exit 1
        fi
    """
}

pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: 'admin',
            description: 'PMM Server admin password.',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: 'main',
            description: 'pmm-qa git branch.',
            name: 'PMM_QA_GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        // HA builds an EKS cluster before it installs anything.
        timeout(time: 4, unit: 'HOURS')
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
                    if (tags.size() < 2) {
                        error('Need at least two released PMM 3.x.y percona/pmm-server tags on Docker Hub after filtering semver tags')
                    }
                    def prevVersion = tags[-2]
                    def latestVersion = tags[-1]
                    env.PMM_SERVER_LATEST = latestVersion
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
                '''
            }
        }
        stage('Post-release checks') {
            parallel {
                stage('Release notes') {
                    steps {
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
                            '''
                        }
                    }
                }
                stage('Docker') {
                    stages {
                        stage('Fresh install of the release') {
                            steps {
                                sh 'docker network create pmm-qa'
                                // A failure here must not stop the upgrade check below.
                                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                                    sh 'docker volume create pmm-fresh-volume'
                                    startPmmServer('pmm-server-fresh', env.DOCKER_TAG_UPGRADE, 'pmm-fresh-volume')
                                    waitForContainer('pmm-server-fresh', 'pmm-managed entered RUNNING state')
                                    waitForPmmReady('pmm-server-fresh')
                                    verifyPmmVersion(env.PMM_SERVER_LATEST)
                                    sh '''
                                        updates=$(curl -sk --user admin:${ADMIN_PASSWORD} "https://127.0.0.1/v1/server/updates?force=true")
                                        echo "${updates}" | jq '{update_available, installed: .installed.version, latest: .latest.version}'
                                        if ! echo "${updates}" | jq -e '.update_available == false' >/dev/null; then
                                            echo "A fresh ${PMM_SERVER_LATEST} install should have no update available" >&2
                                            exit 1
                                        fi
                                    '''
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
                    agent {
                        label 'agent-amd64'
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
                stage('HA') {
                    steps {
                        build job: 'pmm3-ha-helm-upgrade-tests', parameters: [
                            string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
                            string(name: 'CLUSTER_TYPE', value: 'EKS'),
                            string(name: 'RELEASE_DOCKER_VERSION', value: env.DOCKER_TAG_UPGRADE),
                            booleanParam(name: 'RUN_UPGRADE', value: false),
                            string(name: 'CLIENTS', value: ''),
                            string(name: 'HA_TAGS', value: ''),
                        ]
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
            }
            deleteDir()
        }
    }
}
