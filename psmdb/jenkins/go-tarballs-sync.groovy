library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Mirrors the two supported Go releases (linux amd64/arm64) to downloads.percona.com,
// so the packaging builders have a copy that doesn't depend on go.dev being reachable.
pipeline {
    agent {
        label 'launcher-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        DOWNLOADS_URL = 'https://downloads.percona.com/downloads/packaging/go'
        DOWNLOADS_DIR = '/data/downloads/packaging/go'
        DEPLOY_HOST = 'jenkins-deploy.jenkins-deploy.web.r.int.percona.com'
        GO_ARCHES = 'amd64 arm64'
    }
    parameters {
        booleanParam(
            defaultValue: false,
            description: 'Treat the current stable Go releases as new: upload (existing files are kept), crawl, wait and post the Slack report again',
            name: 'REPUBLISH_LATEST')
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }
    triggers {
        cron('H 4 * * *')
    }
    stages {
        stage('Detect new Go releases') {
            steps {
                deleteDir()
                sh '''#!/bin/bash
                    set -o errexit -o nounset -o pipefail
                    command -v jq >/dev/null || sudo yum -y install jq || sudo apt-get -y install jq
                    command -v column >/dev/null || sudo yum -y install util-linux || sudo apt-get -y install bsdextrautils

                    # without include=all go.dev lists only the latest patch of the two supported minors
                    curl -fsSL --retry 3 'https://go.dev/dl/?mode=json' -o releases.json
                    for arch in ${GO_ARCHES}; do
                        jq -r --arg a "$arch" '.[] | select(.stable) | .files[] | select(.os == "linux" and .arch == $a and .kind == "archive") | [.filename, .sha256] | @tsv' releases.json
                    done > candidates.txt
                    test -s candidates.txt

                    : > todo.txt
                    while read -r f sha; do
                        # downloads.percona.com 302s unknown paths (and any *.sha256) to www.percona.com,
                        # which answers 200, so never follow redirects
                        if [ "${REPUBLISH_LATEST:-false}" != true ] && wget --spider -q --max-redirect=0 "${DOWNLOADS_URL}/${f}" && wget --spider -q --max-redirect=0 "${DOWNLOADS_URL}/${f}.sha256sum"; then
                            echo "already on downloads: ${f}"
                        else
                            echo "${f} ${sha}" >> todo.txt
                        fi
                    done < candidates.txt

                    sed -E 's/^go([0-9.]+)\\.linux-.*/\\1/' todo.txt | sort -uV > new_versions.txt
                    cat todo.txt
                '''
                script {
                    env.NEW_VERSIONS = readFile('new_versions.txt').trim().replace('\n', ' ')
                    currentBuild.description = env.NEW_VERSIONS ? "go ${env.NEW_VERSIONS}" : 'nothing new'
                }
            }
        }
        stage('Download and verify') {
            when { expression { return env.NEW_VERSIONS ? true : false } }
            steps {
                sh '''#!/bin/bash
                    set -o errexit -o nounset -o pipefail
                    mkdir -p upload
                    while read -r f sha; do
                        curl -fsSL --retry 3 "https://dl.google.com/go/${f}" -o "upload/${f}"
                        echo "${sha}  upload/${f}" | sha256sum -c -
                        # .sha256sum like the rest of downloads.percona.com, .sha256 is not served there
                        echo "${sha}  ${f}" > "upload/${f}.sha256sum"
                    done < todo.txt
                    ls -l upload
                '''
            }
        }
        stage('Upload to downloads.percona.com') {
            when { expression { return env.NEW_VERSIONS ? true : false } }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''#!/bin/bash
                        set -o errexit -o nounset -o pipefail
                        cat /etc/hosts > hosts
                        echo '10.30.6.9 repo.ci.percona.com' >> hosts
                        sudo cp ./hosts /etc || true

                        SSH="ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com"
                        STAGE_DIR="/tmp/go-tarballs-sync-${BUILD_NUMBER}"
                        ${SSH} mkdir -p "${STAGE_DIR}"
                        scp -o StrictHostKeyChecking=no -i "${KEY_PATH}" upload/* "${USER}@repo.ci.percona.com:${STAGE_DIR}/"

                        # never overwrite what is already published; checksums go last so a
                        # visible .sha256sum always means its tarball is complete
                        ${SSH} ssh -p 2222 "${DEPLOY_HOST}" mkdir -p "${DOWNLOADS_DIR}"
                        ${SSH} rsync -avt --ignore-existing -e '"ssh -p 2222"' --bwlimit=50000 "--exclude='*.sha256sum'" "${STAGE_DIR}/" "${DEPLOY_HOST}:${DOWNLOADS_DIR}/"
                        ${SSH} rsync -avt --ignore-existing -e '"ssh -p 2222"' "--include='*.sha256sum'" "--exclude='*'" "${STAGE_DIR}/" "${DEPLOY_HOST}:${DOWNLOADS_DIR}/"
                        ${SSH} rm -rf "${STAGE_DIR}"

                        # downloads.percona.com only picks up new files after a crawl;
                        # called from repo.ci like in rel/jenkins/release-from-repository.groovy
                        CRAWL_RESPONSE=$(${SSH} curl -sk https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory)
                        echo "Crawl response: ${CRAWL_RESPONSE}"
                        if ! grep -qE '"status":"(running|queued)"' <<< "${CRAWL_RESPONSE}"; then
                            echo "ERROR: crawl_directory did not return running or queued status"
                            exit 1
                        fi
                    '''
                }
            }
        }
        stage('Wait until published') {
            when { expression { return env.NEW_VERSIONS ? true : false } }
            steps {
                // declarative only allows steps here, the == 0 check needs a script block
                script {
                    timeout(time: 3, unit: 'HOURS') {
                        waitUntil(initialRecurrencePeriod: 60000) {
                            return sh(returnStatus: true, script: '''#!/bin/bash
                                set -o nounset
                                while read -r f sha; do
                                    wget --spider -q --max-redirect=0 "${DOWNLOADS_URL}/${f}" || { echo "not yet: ${f}"; exit 1; }
                                    wget --spider -q --max-redirect=0 "${DOWNLOADS_URL}/${f}.sha256sum" || { echo "not yet: ${f}.sha256sum"; exit 1; }
                                    published=$(wget -q --max-redirect=0 -O - "${DOWNLOADS_URL}/${f}.sha256sum" | awk '{print $1}')
                                    [ "${published}" = "${sha}" ] || { echo "stale checksum for ${f}: ${published}"; exit 1; }
                                done < todo.txt
                            ''') == 0
                        }
                    }
                }
            }
        }
        stage('Collect fixed CVEs') {
            when { expression { return env.NEW_VERSIONS ? true : false } }
            steps {
                sh '''#!/bin/bash
                    set -o errexit -o nounset -o pipefail
                    osv_ids() {
                        for pkg in stdlib toolchain; do
                            curl -fsS --retry 3 -X POST https://api.osv.dev/v1/query -d "{\\"package\\":{\\"name\\":\\"${pkg}\\",\\"ecosystem\\":\\"Go\\"},\\"version\\":\\"$1\\"}" | jq -r '.vulns[]?.id'
                        done | sort -u
                    }

                    # raw.tsv: key, CVE, Go vuln ID, version, summary
                    : > raw.tsv
                    for v in $(cat new_versions.txt); do
                        patch=${v##*.}
                        if [ "${patch}" = 0 ]; then
                            printf 'go%s\\t-\\t-\\tgo%s\\tnew minor release, see https://go.dev/doc/go%s\\n' "${v}" "${v}" "${v%.*}" >> raw.tsv
                            continue
                        fi
                        # what the previous patch was vulnerable to and this one is not
                        prev="${v%.*}.$((patch - 1))"
                        fixed=$(comm -23 <(osv_ids "${prev}") <(osv_ids "${v}"))
                        if [ -z "${fixed}" ]; then
                            printf 'go%s\\t-\\t-\\tgo%s\\tno security fixes in vuln.go.dev yet\\n' "${v}" "${v}" >> raw.tsv
                            continue
                        fi
                        for id in ${fixed}; do
                            curl -fsS --retry 3 "https://vuln.go.dev/ID/${id}.json" | jq -r --arg v "go${v}" '[.id, ([.aliases[]? | select(startswith("CVE-"))] | if length > 0 then join(",") else "-" end), .id, $v, (.summary // "-" | .[0:90])] | @tsv' >> raw.tsv
                        done
                    done

                    # both branches usually get the same fixes, so one row per vuln with all versions in it
                    awk -F '\\t' -v OFS='\\t' '
                        BEGIN { print "CVE", "Go vuln ID", "Fixed in", "Summary" }
                        !($1 in fixed) { order[++n] = $1; row[$1] = $2 OFS $3; summary[$1] = $5; fixed[$1] = $4; next }
                        { fixed[$1] = fixed[$1] ", " $4 }
                        END { for (i = 1; i <= n; i++) { k = order[i]; print row[k], fixed[k], summary[k] } }
                    ' raw.tsv | column -t -s "$(printf '\\t')" > cve_report.txt
                    cat cve_report.txt
                '''
            }
        }
        stage('Check Go in our builders') {
            when { expression { return env.NEW_VERSIONS ? true : false } }
            steps {
                sh '''#!/bin/bash
                    set -o errexit -o nounset -o pipefail
                    # product, repo, branch, builder script with GO_VERSION=
                    builders=(
                        'PBM percona/percona-backup-mongodb main packaging/scripts/mongodb-backup_builder.sh'
                        'PCSM percona/percona-clustersync-mongodb main packaging/scripts/percona-clustersync-mongodb_builder.sh'
                        'mongo-tools Percona-Lab/percona-mongodb-tools-packaging main scripts/mongo_tools_builder.sh'
                    )
                    latest=$(jq -r '.[] | select(.stable) | .version | ltrimstr("go")' releases.json)

                    printf 'Product\\tGo\\tStatus\\n' > builders.tsv
                    for b in "${builders[@]}"; do
                        read -r name repo branch path <<< "${b}"
                        if ! script=$(curl -fsSL --retry 3 "https://raw.githubusercontent.com/${repo}/${branch}/${path}"); then
                            printf '%s\\t?\\tcannot fetch %s@%s:%s\\n' "${name}" "${repo}" "${branch}" "${path}" >> builders.tsv
                            continue
                        fi
                        have=$(sed -nE 's/^[[:space:]]*GO_VERSION="?([0-9.]+)"?.*/\\1/p' <<< "${script}" | head -n 1)
                        want=$(awk -v m="${have%.*}." 'index($0, m) == 1' <<< "${latest}")
                        if [ -z "${have}" ]; then
                            status='GO_VERSION not found'
                        elif [ -z "${want}" ]; then
                            status="go${have%.*} is out of support, move to $(echo ${latest} | sed 's/ / or /')"
                        elif [ "${have}" = "${want}" ]; then
                            status='up to date'
                        else
                            status="bump to ${want}"
                        fi
                        printf '%s\\t%s\\t%s\\n' "${name}" "${have:-?}" "${status}" >> builders.tsv
                    done
                    column -t -s "$(printf '\\t')" builders.tsv > builders_report.txt
                    cat builders_report.txt
                '''
            }
        }
    }
    post {
        success {
            script {
                if (env.NEW_VERSIONS) {
                    def report = readFile('cve_report.txt').trim()
                    def builders = readFile('builders_report.txt').trim()
                    slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: Go ${env.NEW_VERSIONS} (linux amd64/arm64) published to ${DOWNLOADS_URL}/\nFixed vulnerabilities (vs previous patch, from vuln.go.dev):\n```\n${report}\n```\nGo in our builders (main):\n```\n${builders}\n```\nRelease notes: https://go.dev/doc/devel/release")
                }
            }
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: Go tarballs sync failed: [${BUILD_URL}]")
        }
        aborted {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: Go tarballs sync aborted (timed out waiting for downloads.percona.com?): [${BUILD_URL}]")
        }
        // not always: it runs before success and would wipe the reports
        cleanup {
            deleteDir()
        }
    }
}
