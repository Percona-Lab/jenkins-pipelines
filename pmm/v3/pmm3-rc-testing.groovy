library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.json.JsonBuilder

// ---------- Slack + triggers --------------------------------------------------

final String RC_SLACK_CHANNEL = '#qa-automation'

def slackRcMessage(String message) {
    if (!env.SLACK_RC_THREAD?.trim()) {
        echo '[slack] No thread ID — skipping Slack notification.'
        return
    }
    slackSend botUser: true, channel: env.SLACK_RC_THREAD.trim(), message: message
}

def triggerJenkinsRc(String shortName, String jobName, List jobParams) {
    def run = build job: jobName, wait: true, propagate: false, parameters: jobParams
    slackRcMessage("*${shortName}*: ${run.absoluteUrl}")
    if (run.result != 'SUCCESS') {
        unstable("${shortName} finished with: ${run.result}")
    }
}

// ---------- pipeline ---------------------------------------------------------

pipeline {
    agent {
        label 'cli'
    }
    options {
        disableConcurrentBuilds()
        timeout(time: 48, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }
    parameters {
        string(
            description: 'Numeric RC line, e.g. 3.7.0',
            name: 'RC_VERSION',
            trim: true)
        string(
            description: 'RC pmm-client amd64 tarball URL',
            name: 'PMM_CLIENT_TARBALL',
            trim: true)
        string(
            description: 'RC pmm-client arm64 tarball URL',
            name: 'PMM_CLIENT_TARBALL_ARM64',
            trim: true)
        string(
            description: 'RC OL8 pmm-client dynamic tarball URL from client autobuild',
            name: 'PMM_CLIENT_TARBALL_OL8',
            trim: true)
        string(
            description: 'RC OL9 pmm-client dynamic tarball URL from client autobuild',
            name: 'PMM_CLIENT_TARBALL_OL9',
            trim: true)
        string(
            description: 'RC AMI ID from pmm3-ami build',
            name: 'AMI_ID',
            trim: true)
    }

    stages {
        stage('Plan') {
            steps {
                script {
                    if (!params.RC_VERSION?.trim()) {
                        error('RC_VERSION is required (e.g. 3.7.0).')
                    }
                    if (!params.PMM_CLIENT_TARBALL_OL8?.trim() || !params.PMM_CLIENT_TARBALL_OL9?.trim()) {
                        error('PMM_CLIENT_TARBALL_OL8 and PMM_CLIENT_TARBALL_OL9 are required.')
                    }
                    env.PMM_SERVER_IMAGE = "perconalab/pmm-server:${params.RC_VERSION.trim()}-rc"

                    def compatTagsOut = sh(
                        returnStdout: true,
                        script: '''
                            set -euo pipefail
                            wget -q 'https://registry.hub.docker.com/v2/repositories/percona/pmm-client/tags?page_size=250' -O - \
                                | jq -r '.results[].name' \
                                | grep -v latest \
                                | sort -V \
                                | grep -E '[[:digit:]]\\.[[:digit:]]+\\.[[:digit:]]' \
                                | tail -n 5
                        '''
                    ).trim()
                    if (!compatTagsOut) {
                        error('Plan: failed to fetch GA percona/pmm-client tags from Docker Hub (empty result)')
                    }
                    writeFile file: 'compat-tags.txt', text: compatTagsOut + '\n'

                    currentBuild.description = "rc=${params.RC_VERSION.trim()} server=${env.PMM_SERVER_IMAGE}"
                }
            }
        }

        stage('Create RC Slack thread') {
            steps {
                script {
                    def intro = """*RC testing started* (`${params.RC_VERSION.trim()}`)
|Orchestrator: ${env.BUILD_URL}
|Server image: `${env.PMM_SERVER_IMAGE}`
|
|Each triggered job will appear below with a link.""".stripMargin()
                    def slackResponse = slackSend botUser: true, channel: RC_SLACK_CHANNEL, message: intro
                    env.SLACK_RC_THREAD = slackResponse.threadId
                    env.SLACK_RC_SCREENSHOTS_TARGET = "${RC_SLACK_CHANNEL}:${slackResponse.ts}"
                }
            }
        }

        stage('Trigger RC suites') {
            parallel {
                stage('Lane 1') {
                    stages {
                        stage('nightly (AMI)') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-ui-tests-nightly (ami)', 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'ami'),
                                        string(name: 'DOCKER_VERSION',          value: params.AMI_ID.trim()),
                                        string(name: 'CLIENT_VERSION',          value: 'pmm3-rc'),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'pmm3admin!'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (compat #1)') {
                            steps {
                                script {
                                    def tags = readFile('compat-tags.txt').trim().split('\n').collect { it.trim() }.findAll { it }
                                    def ver = tags[0]
                                    triggerJenkinsRc("pmm3-ui-tests-nightly (compat ${ver})", 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'docker'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: ver),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'pmm3admin!'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (compat #2)') {
                            steps {
                                script {
                                    def tags = readFile('compat-tags.txt').trim().split('\n').collect { it.trim() }.findAll { it }
                                    def ver = tags[1]
                                    triggerJenkinsRc("pmm3-ui-tests-nightly (compat ${ver})", 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'docker'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: ver),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'pmm3admin!'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (compat #3)') {
                            steps {
                                script {
                                    def tags = readFile('compat-tags.txt').trim().split('\n').collect { it.trim() }.findAll { it }
                                    def ver = tags[2]
                                    triggerJenkinsRc("pmm3-ui-tests-nightly (compat ${ver})", 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'docker'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: ver),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'pmm3admin!'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (compat #4)') {
                            steps {
                                script {
                                    def tags = readFile('compat-tags.txt').trim().split('\n').collect { it.trim() }.findAll { it }
                                    def ver = tags[3]
                                    triggerJenkinsRc("pmm3-ui-tests-nightly (compat ${ver})", 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'docker'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: ver),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'pmm3admin!'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (compat #5)') {
                            steps {
                                script {
                                    def tags = readFile('compat-tags.txt').trim().split('\n').collect { it.trim() }.findAll { it }
                                    def ver = tags[4]
                                    triggerJenkinsRc("pmm3-ui-tests-nightly (compat ${ver})", 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'docker'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: ver),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'pmm3admin!'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                    }
                }

                stage('Lane 2') {
                    stages {
                        stage('nightly (OVF)') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-ui-tests-nightly (ovf)', 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'ovf'),
                                        string(name: 'DOCKER_VERSION',          value: "https://percona-vm.s3.amazonaws.com/PMM3-Server-${params.RC_VERSION.trim()}.ova"),
                                        string(name: 'CLIENT_VERSION',          value: 'pmm3-rc'),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'admin1'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '99'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (Docker)') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-ui-tests-nightly (docker)', 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'docker'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: 'pmm3-rc'),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'pmm3admin!'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (Helm)') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-ui-tests-nightly (helm)', 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'helm'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: 'pmm3-rc'),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'admin1'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (HA)') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-ui-tests-nightly (ha)', 'pmm3-ui-tests-nightly', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'ha'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: 'pmm3-rc'),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'admin1'),
                                        string(name: 'HELM_CHART_BRANCH',       value: 'main'),
                                        string(name: 'OPENSHIFT_VERSION',       value: 'latest'),
                                        string(name: 'K8S_VERSION',             value: '1.34'),
                                        string(name: 'PXC_VERSION',             value: '8.0'),
                                        string(name: 'PS_VERSION',              value: '8.4'),
                                        string(name: 'MS_VERSION',              value: '8.4'),
                                        string(name: 'PGSQL_VERSION',           value: '17'),
                                        string(name: 'PDPGSQL_VERSION',         value: '17'),
                                        string(name: 'MD_VERSION',              value: '10.6'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                        string(name: 'QUERY_SOURCE',            value: 'slowlog'),
                                        string(name: 'PTS_CONFIDENCE',          value: '93'),
                                    ])
                                }
                            }
                        }
                        stage('nightly (GSSAPI)') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-ui-tests-nightly-gssapi', 'pmm3-ui-tests-nightly-gssapi', [
                                        string(name: 'PMM_QA_GIT_BRANCH',       value: 'main'),
                                        string(name: 'SERVER_TYPE',             value: 'docker'),
                                        string(name: 'DOCKER_VERSION',          value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',          value: params.PMM_CLIENT_TARBALL_OL9.trim()),
                                        string(name: 'ENABLE_PULL_MODE',        value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',          value: 'pmm3admin!'),
                                        string(name: 'PSMDB_VERSION',           value: '8.0'),
                                        string(name: 'MODB_VERSION',            value: '8.0'),
                                    ])
                                }
                            }
                        }
                        stage('openshift-helm-tests') {
                            steps {
                                script {
                                    triggerJenkinsRc('openshift-helm-tests', 'openshift-helm-tests', [
                                        string(name: 'PMM_QA_GIT_BRANCH',  value: 'main'),
                                        string(name: 'PMM_CHART_BRANCH',   value: 'latest'),
                                        string(name: 'IMAGE_REPO',         value: env.PMM_SERVER_IMAGE.split(':')[0]),
                                        string(name: 'IMAGE_TAG',          value: env.PMM_SERVER_IMAGE.split(':')[1]),
                                        string(name: 'OPENSHIFT_VERSION',  value: 'latest'),
                                    ])
                                }
                            }
                        }
                    }
                }

                stage('Lane 3') {
                    stages {
                        stage('GitHub rc-testing-suite') {
                            steps {
                                script {
                                    try {
                                        def payload = new JsonBuilder([
                                            ref   : 'main',
                                            inputs: [
                                                rc_version             : params.RC_VERSION.trim(),
                                                pmm_client_tarball_ol8 : params.PMM_CLIENT_TARBALL_OL8.trim(),
                                                pmm_client_tarball_ol9 : params.PMM_CLIENT_TARBALL_OL9.trim(),
                                                pmm_qa_branch          : 'main',
                                                pxc_version            : '8.0',
                                                pxc_glibc              : '2.35',
                                                pdpgsql_version        : '17',
                                            ],
                                        ]).toString()
                                        writeFile file: 'rc-suite-dispatch.json', text: payload
                                        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_TOKEN')]) {
                                            sh """
                                                set -euo pipefail
                                                curl -fsS -X POST \\
                                                    -H "Accept: application/vnd.github+json" \\
                                                    -H "Authorization: Bearer \${GITHUB_TOKEN}" \\
                                                    -H "X-GitHub-Api-Version: 2022-11-28" \\
                                                    "https://api.github.com/repos/percona/pmm-qa/actions/workflows/rc-testing-suite.yml/dispatches" \\
                                                    --data @rc-suite-dispatch.json
                                            """
                                        }
                                        slackRcMessage("*Github Release Testing Suite*: https://github.com/percona/pmm-qa/actions/workflows/rc-testing-suite.yml")
                                    } catch (err) {
                                        slackRcMessage("*Github Release Testing Suite* (dispatch failed): ${err.getMessage()}")
                                    }
                                }
                            }
                        }
                        stage('pmm3-migration-tests') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-migration-tests', 'pmm3-migration-tests', [
                                        string(name: 'PMM_V3_UI_GIT_BRANCH', value: 'main'),
                                        string(name: 'PMM_V2_UI_GIT_BRANCH', value: 'v2'),
                                        string(name: 'DOCKER_VERSION',       value: 'perconalab/pmm-server:2.44.1'),
                                        string(name: 'CLIENT_VERSION',       value: '2.44.1'),
                                        string(name: 'ADMIN_PASSWORD',       value: 'pmm3admin!'),
                                        string(name: 'PMM_QA_GIT_BRANCH',   value: 'v2'),
                                        string(name: 'UPGRADE_TAG',          value: 'testing'),
                                    ])
                                }
                            }
                        }
                        stage('pmm3-ui-tests-matrix') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-ui-tests-matrix', 'pmm3-ui-tests-matrix', [
                                        string(name: 'PMM_QA_GIT_BRANCH', value: 'main'),
                                        string(name: 'GIT_COMMIT_HASH',  value: ''),
                                        string(name: 'DOCKER_VERSION',   value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',   value: 'pmm3-rc'),
                                        string(name: 'MYSQL_IMAGE',      value: 'percona:5.7'),
                                        string(name: 'POSTGRES_IMAGE',   value: 'perconalab/percona-distribution-postgresql:16.0'),
                                        string(name: 'MONGO_IMAGE',      value: 'percona/percona-server-mongodb:4.4'),
                                        string(name: 'PROXYSQL_IMAGE',   value: 'proxysql/proxysql:2.3.0'),
                                    ])
                                }
                            }
                        }
                        stage('pmm3-upgrade-ami-test') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-upgrade-ami-test', 'pmm3-upgrade-ami-test', [
                                        string(name: 'PMM_UI_GIT_BRANCH',         value: 'main'),
                                        string(name: 'PMM_QA_GIT_BRANCH',         value: 'main'),
                                        string(name: 'QA_INTEGRATION_GIT_BRANCH', value: 'main'),
                                        booleanParam(name: 'IS_RC_TESTING',       value: true),
                                    ])
                                }
                            }
                        }
                        stage('pmm3-package-testing-matrix') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-package-testing-matrix', 'pmm3-package-testing-matrix', [
                                        string(name: 'GIT_BRANCH',      value: 'main'),
                                        string(name: 'GIT_COMMIT_HASH', value: ''),
                                        string(name: 'DOCKER_VERSION',  value: env.PMM_SERVER_IMAGE),
                                        string(name: 'PMM_VERSION',     value: params.RC_VERSION.trim()),
                                        string(name: 'INSTALL_REPO',    value: 'testing'),
                                        string(name: 'TARBALL',         value: params.PMM_CLIENT_TARBALL.trim()),
                                        string(name: 'METRICS_MODE',    value: 'auto'),
                                    ])
                                }
                            }
                        }
                        stage('pmm3-package-testing-arm-matrix') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-package-testing-arm-matrix', 'pmm3-package-testing-arm-matrix', [
                                        string(name: 'GIT_BRANCH',      value: 'main'),
                                        string(name: 'GIT_COMMIT_HASH', value: ''),
                                        string(name: 'DOCKER_VERSION',  value: env.PMM_SERVER_IMAGE),
                                        string(name: 'PMM_VERSION',     value: params.RC_VERSION.trim()),
                                        string(name: 'INSTALL_REPO',    value: 'testing'),
                                        string(name: 'TARBALL',         value: params.PMM_CLIENT_TARBALL_ARM64.trim()),
                                        string(name: 'METRICS_MODE',    value: 'auto'),
                                    ])
                                }
                            }
                        }
                        stage('pmm3-upgrade-tests-matrix') {
                            steps {
                                script {
                                    triggerJenkinsRc('pmm3-upgrade-tests-matrix', 'pmm3-upgrade-tests-matrix', [
                                        string(name: 'PMM_QA_GIT_BRANCH', value: 'main'),
                                    ])
                                }
                            }
                        }
                        stage('PMM screenshots') {
                            steps {
                                script {
                                    build job: 'pmm3-deploy-services', wait: false, propagate: false, parameters: [
                                        string(name: 'SERVER_TYPE',                    value: 'docker'),
                                        string(name: 'DOCKER_VERSION',                 value: env.PMM_SERVER_IMAGE),
                                        string(name: 'CLIENT_VERSION',                 value: 'pmm3-rc'),
                                        string(name: 'ENABLE_PULL_MODE',               value: 'no'),
                                        string(name: 'ADMIN_PASSWORD',                 value: 'pmm3admin!'),
                                        booleanParam(name: 'DEPLOY_EXTERNAL',          value: true),
                                        booleanParam(name: 'DEPLOY_MYSQL_GROUP',       value: true),
                                        booleanParam(name: 'DEPLOY_POSTGRES_GROUP',    value: true),
                                        booleanParam(name: 'DEPLOY_MONGO_GROUP',       value: true),
                                        booleanParam(name: 'DEPLOY_VALKEY',            value: true),
                                        string(name: 'PXC_VERSION',                    value: '8.0'),
                                        string(name: 'PS_VERSION',                     value: '8.4'),
                                        string(name: 'MS_VERSION',                     value: '8.4'),
                                        string(name: 'PGSQL_VERSION',                  value: '17'),
                                        string(name: 'PDPGSQL_VERSION',                value: '17'),
                                        string(name: 'PSMDB_VERSION',                  value: '8.0'),
                                        string(name: 'MODB_VERSION',                   value: '8.0'),
                                        string(name: 'PMM_QA_GIT_BRANCH',              value: 'main'),
                                        booleanParam(name: 'GENERATE_DASHBOARD_SCREENSHOTS', value: true),
                                        string(name: 'SCREENSHOTS_SLACK_TARGET',       value: env.SLACK_RC_SCREENSHOTS_TARGET),
                                    ]
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                slackRcMessage("""*RC orchestrator finished* — all triggers were attempted.
|Check each link above for status.
|Orchestrator build: ${env.BUILD_URL}""".stripMargin())
                slackSend botUser: true, channel: RC_SLACK_CHANNEL, message: "*RC testing finished* (`${params.RC_VERSION.trim()}`)\nResults: ${env.BUILD_URL}"
                currentBuild.result = 'SUCCESS'
            }
            deleteDir()
        }
    }
}
