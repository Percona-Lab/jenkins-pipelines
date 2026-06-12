library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

def versionsList = pmmVersion('v3')
def latestVersion = versionsList.last()
def prevVersion = versionsList[-2]

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
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    env.ADMIN_PASSWORD = params.ADMIN_PASSWORD
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
        stage('Start old PMM server (docker way)') {
            steps {
                sh '''
                    docker network create pmm-qa
                    docker volume create pmm-volume

                    docker run --detach --restart always \
                        --network="pmm-qa" \
                        -e PMM_DEBUG=1 \
                        -e PMM_ENABLE_UPDATES=1 \
                        -e PMM_ENABLE_INTERNAL_PG_QAN=1 \
                        -e GF_SECURITY_ADMIN_PASSWORD="${ADMIN_PASSWORD}" \
                        --publish 443:8443 \
                        --volume pmm-volume:/srv \
                        --name pmm-server \
                        ${DOCKER_TAG}
                '''
                waitForContainer('pmm-server', 'pmm-managed entered RUNNING state')
                script {
                    env.SERVER_IP = "127.0.0.1"
                    env.PMM_URL = "https://admin:${env.ADMIN_PASSWORD}@${env.SERVER_IP}"
                }
            }
        }
        stage('Sanity check') {
            steps {
                sh '''
                    if ! timeout 100 bash -c 'until [ "$(curl -ks -o /dev/null -w "%{http_code}" https://127.0.0.1/v1/server/readyz)" = "200" ]; do sleep 5; done'; then
                        echo "PMM Server did not become ready within the timeout, dumping logs" >&2
                        docker logs pmm-server || true
                        exit 1
                    fi
                '''
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
                    docker run --detach --restart always \
                        --network="pmm-qa" \
                        -e PMM_DEBUG=1 \
                        -e PMM_ENABLE_UPDATES=1 \
                        -e PMM_ENABLE_INTERNAL_PG_QAN=1 \
                        -e GF_SECURITY_ADMIN_PASSWORD="${ADMIN_PASSWORD}" \
                        --publish 443:8443 \
                        --volume pmm-volume:/srv \
                        --name pmm-server \
                        ${DOCKER_TAG_UPGRADE}
                    docker ps -a
                '''
            }
        }
        stage('Sanity check after upgrade') {
            steps {
                sh '''
                    if ! timeout 100 bash -c 'until [ "$(curl -ks -o /dev/null -w "%{http_code}" https://127.0.0.1/v1/server/readyz)" = "200" ]; do sleep 5; done'; then
                        echo "PMM Server did not become ready within the timeout, dumping logs" >&2
                        docker logs pmm-server || true
                        exit 1
                    fi
                '''
            }
        }
        stage('Verify version after upgrade') {
            steps {
                sh '''
                    UPGRADED_VERSION=$(curl -sk --user admin:${ADMIN_PASSWORD} https://127.0.0.1/v1/server/version | jq -r '.version' | awk -F "-" '{print $1}')
                    echo "Upgraded PMM Server version: ${UPGRADED_VERSION}, expected: ${PMM_SERVER_LATEST}"
                    if [[ "${UPGRADED_VERSION}" != "${PMM_SERVER_LATEST}" ]]; then
                        echo "Version mismatch after docker upgrade" >&2
                        exit 1
                    fi
                '''
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
                archiveArtifacts artifacts: 'playwright-report.tar.gz', allowEmptyArchive: true
            }
            deleteDir()
        }
    }
}
