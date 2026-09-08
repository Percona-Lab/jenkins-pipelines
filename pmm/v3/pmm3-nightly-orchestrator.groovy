library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.json.JsonBuilder

// Nightly release-readiness run for the image on the tip: does everything we
// would check before a release still pass against 3-dev-latest?
//
// Scripted, and deliberately without a top-level `node`. Every suite is
// dispatched with `build job:`, which runs on this build's flyweight executor,
// so the orchestrator holds no EC2 executor for the hours its children take.
// Declarative cannot express that: `agent none` there forces an agent on every
// stage that has steps.
//
// That property is what makes the full fan-out safe. A job that waits while
// holding an executor its own child needs is how pmm3-rc-testing #33 and #34
// deadlocked; nothing here holds anything while waiting.
//
// The matrix jobs are gone on purpose. pmm3-package-tests-matrix-*,
// pmm3-ui-tests-matrix and pmm3-upgrade-tests-matrix existed only to fan a
// parameter out into parallel branches — with the fan-out here that layer is a
// job, an executor and a level of indirection for nothing. Their branches are
// inlined below, grouped so the stage view still reads by family.

properties([
    buildDiscarder(logRotator(numToKeepStr: '30')),
    disableConcurrentBuilds(),
    parameters([
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server image under test',
            name: 'DOCKER_VERSION',
            trim: true),
        string(
            defaultValue: '3-dev-latest',
            description: 'PMM Client for the main lanes. 3-dev-latest installs the package from the experimental repo; latest-tarball pulls the S3 tarball. Compatibility lanes always use GA releases.',
            name: 'CLIENT_VERSION',
            trim: true),
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for the pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH',
            trim: true),
        choice(
            choices: ['UI', 'DOCKER'],
            description: 'How the upgrade suites upgrade PMM Server',
            name: 'UPGRADE_TYPE'),
        booleanParam(
            defaultValue: false,
            description: 'Route the suites to on-demand executors instead of spot',
            name: 'USE_ONDEMAND'),
        booleanParam(
            defaultValue: false,
            description: 'Also dispatch the pmm-qa rc-testing-suite GitHub workflow. Off by default: that workflow composes its image names as <rc_version>-rc and cannot target a dev image yet.',
            name: 'RUN_GH_RC_SUITE'),
    ]),
])

// Script-level binding so the dispatch helpers can record into it.
results = [:]

def suite(String name, String jobName, List jobParams) {
    return {
        stage(name) {
            def run = build job: jobName, parameters: jobParams, wait: true, propagate: false
            results[name] = [job: jobName, number: run.number, url: run.absoluteUrl, result: run.result]
            echo "[${name}] ${run.result} -> ${run.absoluteUrl}"
        }
    }
}

def group(String name, Map branches) {
    return {
        stage(name) {
            parallel branches
        }
    }
}

def nightlyGha(String name, String serverImage, String amiId, Map cfg = [:]) {
    def defaults = [
        PMM_QA_GIT_BRANCH : params.PMM_QA_GIT_BRANCH,
        SERVER_TYPE       : 'docker',
        DOCKER_VERSION    : serverImage,
        AMI_ID            : amiId,
        CLIENT_VERSION    : params.CLIENT_VERSION,
        SERVER_ARCH       : 'amd64',
        ADMIN_PASSWORD    : 'pmm3admin!',
        HELM_CHART_BRANCH : 'main',
        OPENSHIFT_VERSION : 'latest',
        K8S_VERSION       : '1.34',
        PTS_CONFIDENCE    : '100',
    ]
    def jobParams = (defaults + cfg).collect { k, v -> string(name: k, value: v.toString()) }
    jobParams << booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND)
    return suite(name, 'pmm3-ui-tests-nightly-gha', jobParams)
}

def packageBranches(String jobName, String serverArch, String serverImage, String pmmVersionLabel) {
    // TESTS is the playbook; the trailing spaces in CLIENTS are load-bearing —
    // they keep otherwise identical parameter sets from collapsing in the queue.
    def variants = [
        ['pmm3-client_integration',                     '--help  '],
        ['pmm3-client_integration_auth_config',         '--help   '],
        ['pmm3-client_integration_auth_register',       ' --help'],
        ['pmm3-client_integration_custom_path',         '  --help'],
        ['pmm3-client_integration_custom_port',         '   --help'],
        ['pmm3-client_integration_upgrade',             '    --help'],
        ['pmm3-client_integration_upgrade_custom_path', '    --help '],
        ['pmm3-client_integration_upgrade_custom_port', '    --help  '],
    ]
    def branches = [:]
    variants.each { v ->
        def tests = v[0]
        def clients = v[1]
        branches[tests] = suite(tests, jobName, [
            string(name: 'GIT_BRANCH',      value: params.PMM_QA_GIT_BRANCH),
            string(name: 'DOCKER_VERSION',  value: serverImage),
            string(name: 'SERVER_ARCH',     value: serverArch),
            string(name: 'PMM_VERSION',     value: pmmVersionLabel),
            string(name: 'TESTS',           value: tests),
            string(name: 'INSTALL_REPO',    value: 'experimental'),
            string(name: 'TARBALL',         value: ''),
            string(name: 'METRICS_MODE',    value: 'auto'),
            string(name: 'CLIENTS',         value: clients),
            booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
        ])
    }
    return branches
}

def upgradeBranches(List pmmVersions, List oldVersions, String latestVersion, String latestDevVersion) {
    // Mirrors pmm3-upgrade-tests-matrix: every recent version upgraded, with the
    // newest one going from its RC image up to the dev tip.
    def variants = ['SSL', 'EXTERNAL SERVICES', 'OTHERS']
    def branches = [:]
    pmmVersions.each { ver ->
        def isNewest = (ver == pmmVersions.last())
        def dockerTag = isNewest ? "perconalab/pmm-server:${ver}-rc" : "percona/pmm-server:${ver}"
        def dockerTagUpgrade = isNewest ? 'perconalab/pmm-server:3-dev-latest' : "perconalab/pmm-server:${pmmVersions.last()}-rc"
        def clientVersion = isNewest ? 'pmm3-rc'
            : (ver in oldVersions ? "https://downloads.percona.com/downloads/pmm3/${ver}/binary/tarball/pmm-client-${ver}-x86_64.tar.gz" : ver)
        def clientRepo = isNewest ? 'experimental' : 'testing'
        def serverLatest = isNewest ? latestDevVersion : latestVersion

        variants.each { variant ->
            def name = "${ver} / ${variant}"
            branches[name] = suite(name, 'pmm3-upgrade-test-runner', [
                string(name: 'PMM_UI_PRE_UPGRADE_GIT_BRANCH', value: "pmm-${ver}"),
                string(name: 'DOCKER_TAG',                    value: dockerTag),
                string(name: 'DOCKER_TAG_UPGRADE',            value: dockerTagUpgrade),
                string(name: 'CLIENT_VERSION',                value: clientVersion),
                string(name: 'CLIENT_REPOSITORY',             value: clientRepo),
                string(name: 'PMM_SERVER_LATEST',             value: serverLatest),
                string(name: 'PMM_QA_GIT_BRANCH',             value: params.PMM_QA_GIT_BRANCH),
                string(name: 'UPGRADE_FLAG',                  value: variant),
                string(name: 'UPGRADE_TYPE',                  value: params.UPGRADE_TYPE),
                booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
            ])
        }
    }
    return branches
}

timestamps {
    def serverImage = params.DOCKER_VERSION.trim()
    def amiId = pmmVersion('v3-ami').values()[-1]
    def compatVersions = pmmVersion('v3')[-5..-1]
    def upgradeVersions = pmmVersion('v3')[-6..-1]
    def oldVersions = pmmVersion('v3-old')
    def latestVersion = pmmVersion('v3').last()
    def latestDevVersion

    stage('Plan') {
        // The one shell in this job, and the only executor it ever takes.
        node(params.USE_ONDEMAND ? 'cli-ondemand' : 'cli') {
            try {
                latestDevVersion = sh(
                    returnStdout: true,
                    script: 'curl -fsSL https://raw.githubusercontent.com/Percona-Lab/pmm-submodules/v3/VERSION'
                ).trim()
            } finally {
                deleteDir()
            }
        }
        currentBuild.description = "server=${serverImage} client=${params.CLIENT_VERSION}"
        echo """Nightly release readiness
  server image    : ${serverImage}
  client          : ${params.CLIENT_VERSION}
  AMI             : ${amiId}
  compat clients  : ${compatVersions.join(', ')}
  upgrade from    : ${upgradeVersions.join(', ')}
  dev version     : ${latestDevVersion}
  on-demand       : ${params.USE_ONDEMAND}"""
    }

    def nightly = [:]
    nightly['docker'] = nightlyGha('docker', serverImage, amiId)
    nightly['docker (arm64)'] = nightlyGha('docker (arm64)', serverImage, amiId, [SERVER_ARCH: 'arm64'])
    nightly['ami'] = nightlyGha('ami', serverImage, amiId, [SERVER_TYPE: 'ami'])
    nightly['helm'] = nightlyGha('helm', serverImage, amiId, [SERVER_TYPE: 'helm', ADMIN_PASSWORD: 'admin1'])
    nightly['ha'] = nightlyGha('ha', serverImage, amiId, [SERVER_TYPE: 'ha', ADMIN_PASSWORD: 'admin1'])

    def compat = [:]
    compatVersions.each { ver ->
        compat["client ${ver}"] = nightlyGha("client ${ver}", serverImage, amiId, [CLIENT_VERSION: ver])
    }

    def uiTests = [:]
    [['@ia', ''],
     ['@instances', '--database ssl_mysql --database haproxy --database external'],
     ['@gcp', '']].each { t ->
        def tag = t[0]
        def clients = t[1]
        uiTests[tag] = suite(tag, 'pmm3-ui-tests', [
            string(name: 'GIT_COMMIT_HASH',   value: ''),
            string(name: 'DOCKER_VERSION',    value: serverImage),
            string(name: 'CLIENT_VERSION',    value: params.CLIENT_VERSION),
            string(name: 'TAG',               value: tag),
            string(name: 'MYSQL_IMAGE',       value: 'percona:5.7'),
            string(name: 'POSTGRES_IMAGE',    value: 'perconalab/percona-distribution-postgresql:16.0'),
            string(name: 'MONGO_IMAGE',       value: 'percona/percona-server-mongodb:4.4'),
            string(name: 'PROXYSQL_IMAGE',    value: 'proxysql/proxysql:2.3.0'),
            string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
            string(name: 'CLIENTS',           value: clients),
            booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
        ])
    }

    def groups = [:]
    groups['nightly'] = group('nightly', nightly)
    groups['compat'] = group('compat', compat)
    groups['ui-tests'] = group('ui-tests', uiTests)
    groups['package (amd64)'] = group('package (amd64)',
        packageBranches('nightly-package-testing-amd64', 'amd64', serverImage, latestVersion))
    groups['package (arm64)'] = group('package (arm64)',
        packageBranches('nightly-package-testing-arm64', 'arm64', serverImage, latestVersion))
    groups['upgrade (docker)'] = group('upgrade (docker)',
        upgradeBranches(upgradeVersions, oldVersions, latestVersion, latestDevVersion))

    groups['upgrade (ami)'] = suite('upgrade (ami)', 'pmm3-upgrade-ami-test', [
        string(name: 'PMM_QA_GIT_BRANCH',   value: params.PMM_QA_GIT_BRANCH),
        booleanParam(name: 'IS_RC_TESTING', value: false),
        booleanParam(name: 'USE_ONDEMAND',  value: params.USE_ONDEMAND),
    ])

    groups['gssapi'] = suite('gssapi', 'pmm3-ui-tests-nightly-gssapi', [
        string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
        string(name: 'SERVER_TYPE',       value: 'docker'),
        string(name: 'DOCKER_VERSION',    value: serverImage),
        string(name: 'CLIENT_VERSION',    value: params.CLIENT_VERSION),
        string(name: 'ENABLE_PULL_MODE',  value: 'no'),
        string(name: 'ADMIN_PASSWORD',    value: 'pmm3admin!'),
        string(name: 'PSMDB_VERSION',     value: '8.0'),
        string(name: 'MODB_VERSION',      value: '8.0'),
        booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
    ])

    groups['openshift'] = suite('openshift', 'openshift-helm-tests', [
        string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
        string(name: 'PMM_CHART_BRANCH',  value: 'latest'),
        string(name: 'IMAGE_REPO',        value: serverImage.split(':')[0]),
        string(name: 'IMAGE_TAG',         value: serverImage.split(':')[1]),
        string(name: 'OPENSHIFT_VERSION', value: 'latest'),
        booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
    ])

    if (params.RUN_GH_RC_SUITE) {
        groups['github rc-testing-suite'] = {
            stage('github rc-testing-suite') {
                node(params.USE_ONDEMAND ? 'cli-ondemand' : 'cli') {
                    try {
                        writeFile file: 'gh-dispatch.json', text: new JsonBuilder([
                            ref   : 'main',
                            inputs: [
                                rc_version             : latestDevVersion,
                                pmm_client_tarball_ol8 : 'https://pmm-build-cache.s3.us-east-2.amazonaws.com/PR-BUILDS/pmm-client/pmm-client-dynamic-ol8-latest.tar.gz',
                                pmm_client_tarball_ol9 : 'https://pmm-build-cache.s3.us-east-2.amazonaws.com/PR-BUILDS/pmm-client/pmm-client-dynamic-ol9-latest.tar.gz',
                                pmm_qa_branch          : params.PMM_QA_GIT_BRANCH,
                                pxc_version            : '8.0',
                                pxc_glibc              : '2.35',
                                pdpgsql_version        : '17',
                                skip_compatibility     : false,
                            ],
                        ]).toString()
                        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_TOKEN')]) {
                            sh """
                                set -euo pipefail
                                curl -fsS -X POST \\
                                    -H "Accept: application/vnd.github+json" \\
                                    -H "Authorization: Bearer \${GITHUB_TOKEN}" \\
                                    -H "X-GitHub-Api-Version: 2022-11-28" \\
                                    "https://api.github.com/repos/percona/pmm-qa/actions/workflows/rc-testing-suite.yml/dispatches" \\
                                    --data @gh-dispatch.json
                            """
                        }
                        results['github rc-testing-suite'] = [
                            job   : 'rc-testing-suite.yml',
                            url   : 'https://github.com/percona/pmm-qa/actions/workflows/rc-testing-suite.yml',
                            result: 'DISPATCHED',
                        ]
                    } finally {
                        deleteDir()
                    }
                }
            }
        }
    }

    parallel groups

    stage('Report') {
        def links = results.collect { name, r ->
            def label = r.number ? "${name} (${r.job} #${r.number})" : name
            "<a href=\"${r.url}\">${label}</a> &mdash; ${r.result}"
        }
        currentBuild.description = "server=${serverImage} client=${params.CLIENT_VERSION}<br>" + links.join('<br>')

        def failed = results.findAll { it.value.result in ['FAILURE', 'ABORTED'] }
        def unstable = results.findAll { it.value.result == 'UNSTABLE' }

        echo """Nightly release readiness — ${results.size()} suites
${results.collect { n, r -> "  ${(r.result ?: 'UNKNOWN').padRight(10)} ${n}\n             ${r.url}" }.join('\n')}

failed: ${failed.size()}   unstable: ${unstable.size()}   ok: ${results.size() - failed.size() - unstable.size()}"""

        if (failed) {
            currentBuild.result = 'FAILURE'
        } else if (unstable) {
            currentBuild.result = 'UNSTABLE'
        } else {
            currentBuild.result = 'SUCCESS'
        }
    }
}
