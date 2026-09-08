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
// job, an executor and a level of indirection for nothing.
//
// The fan-out is ONE flat parallel. Neither the Stage View nor Blue Ocean can
// render parallel nested in parallel: the graph analyser fails to pair the end
// nodes, so it reports still-running stages as SUCCESS with negative durations
// (pmm3-nightly-orchestrator #1). The family therefore lives in the branch
// name — "pkg amd64 / auth config" — not in a wrapper stage.
//
// Sequential stages INSIDE a parallel branch are a different case, and one the
// graph does render: that is how each lane expands into the child's own steps
// (see mirrorChild). So a lane is "suite name" followed by the job, the build
// number and one box per stage the child ran.

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

def humanMs(def millis) {
    // A stage still running or paused for input reports no duration.
    if (millis == null) {
        return '?'
    }
    long ms = millis as long
    if (ms < 1000) {
        return "${ms}ms"
    }
    long total = (ms / 1000) as long
    long h = (total / 3600) as long
    long m = ((total % 3600) / 60) as long
    long s = total % 60
    if (h > 0) {
        return "${h}h${m.toString().padLeft(2, '0')}m"
    }
    if (m > 0) {
        return "${m}m${s.toString().padLeft(2, '0')}s"
    }
    return "${s}s"
}

// Redraw the child's stages as stages of this branch, so a lane reads as the
// sequence of steps the suite actually ran instead of one box that only says
// pass or fail.
//
// Opening them after the child finishes is the one thing this cannot do
// faithfully: a stage entered and left immediately lasts a few milliseconds
// however long the child spent there. The child's real elapsed time therefore
// goes in the label, which is also what keeps a lane readable — the box that
// took 8m22s is the one to look at.
def mirrorChild(String name, String jobName, def run) {
    def stages = []
    try {
        stages = childStages(run)
    } catch (err) {
        // Degrade to the single-box lane rather than lose the suite's verdict.
        // childStages lives in the global library, so this is also what a
        // controller whose lib@master predates it reports.
        echo "[${name}] child stages unavailable (${err.message}); rendering the suite as one stage"
    }

    stage("#${run.number} ${jobName}") {
        echo "${run.absoluteUrl}"
    }

    // A suite that dies early leaves a long tail of "skipped due to earlier
    // failure(s)" stages — 15 of upgrade's 22 in one #3 build. A box each buries
    // the failure that caused them, so the tail collapses into one.
    int lastRun = -1
    for (int i = 0; i < stages.size(); i++) {
        if (stages[i].status != 'NOT_EXECUTED') {
            lastRun = i
        }
    }

    for (int i = 0; i <= lastRun; i++) {
        def child = stages[i]
        stage("${child.name} · ${humanMs(child.durationMillis)}") {
            // buildResult stays null on purpose: the suite's own verdict below
            // owns the build result, and a mirrored step must not raise it.
            if (child.status == 'FAILED') {
                catchError(buildResult: null, stageResult: 'FAILURE') {
                    error("${child.name} failed in ${jobName} #${run.number}")
                }
            } else if (child.status == 'UNSTABLE') {
                catchError(buildResult: null, stageResult: 'UNSTABLE') {
                    error("${child.name} unstable in ${jobName} #${run.number}")
                }
            } else if (child.status == 'ABORTED') {
                catchError(buildResult: null, stageResult: 'ABORTED') {
                    error("${child.name} aborted in ${jobName} #${run.number}")
                }
            }
        }
    }

    int skipped = stages.size() - 1 - lastRun
    if (skipped > 0) {
        stage("${skipped} stages skipped") {
            catchError(buildResult: null, stageResult: 'NOT_BUILT') {
                error("${skipped} stages never ran: ${jobName} #${run.number} stopped at '${stages[lastRun].name}'")
            }
        }
    }
}

def suite(String name, String jobName, List jobParams) {
    return {
        stage(name) {
            def run = build job: jobName, parameters: jobParams, wait: true, propagate: false
            results[name] = [job: jobName, number: run.number, url: run.absoluteUrl, result: run.result]
            echo "[${name}] ${run.result} -> ${run.absoluteUrl}"

            mirrorChild(name, jobName, run)

            // propagate:false stops one failing suite from aborting the other 49,
            // but on its own it also leaves the stage green forever. catchError
            // colours the stage and the build without throwing, so the stage view
            // reports what actually happened.
            if (run.result == 'FAILURE' || run.result == 'ABORTED') {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    error("${name}: ${jobName} #${run.number} ${run.result} — ${run.absoluteUrl}")
                }
            } else if (run.result == 'UNSTABLE') {
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    error("${name}: ${jobName} #${run.number} UNSTABLE — ${run.absoluteUrl}")
                }
            }
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

def packageBranches(Map branches, String prefix, String jobName, String serverArch, String serverImage, String pmmVersionLabel) {
    // TESTS is the playbook; the trailing spaces in CLIENTS are load-bearing —
    // they keep otherwise identical parameter sets from collapsing in the queue.
    def variants = [
        ['integration',          'pmm3-client_integration',                     '--help  '],
        ['auth config',          'pmm3-client_integration_auth_config',         '--help   '],
        ['auth register',        'pmm3-client_integration_auth_register',       ' --help'],
        ['custom path',          'pmm3-client_integration_custom_path',         '  --help'],
        ['custom port',          'pmm3-client_integration_custom_port',         '   --help'],
        ['upgrade',              'pmm3-client_integration_upgrade',             '    --help'],
        ['upgrade custom path',  'pmm3-client_integration_upgrade_custom_path', '    --help '],
        ['upgrade custom port',  'pmm3-client_integration_upgrade_custom_port', '    --help  '],
    ]
    variants.each { v ->
        def label = v[0]
        def tests = v[1]
        def clients = v[2]
        def name = "${prefix} / ${label}"
        branches[name] = suite(name, jobName, [
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
}

def upgradeBranches(Map branches, List pmmVersions, List clientDebVersions, String latestVersion, String latestDevVersion) {
    // Mirrors pmm3-upgrade-tests-matrix: every recent version upgraded, with the
    // newest one going from its RC image up to the dev tip.
    def variants = ['SSL', 'EXTERNAL SERVICES', 'OTHERS']
    pmmVersions.each { ver ->
        def isNewest = (ver == pmmVersions.last())
        def dockerTag = isNewest ? "perconalab/pmm-server:${ver}-rc" : "percona/pmm-server:${ver}"
        def dockerTagUpgrade = isNewest ? 'perconalab/pmm-server:3-dev-latest' : "perconalab/pmm-server:${pmmVersions.last()}-rc"
        // repo.percona.com keeps only the newest few client debs, so the oldest
        // sources in this matrix have no deb to install and pmm3-client-setup.sh's
        // constructed pool URL 404s. clientDebVersions is what the apt index
        // actually offers; anything missing from it installs from its tarball.
        def clientVersion = isNewest ? 'pmm3-rc'
            : (ver in clientDebVersions ? ver : "https://downloads.percona.com/downloads/pmm3/${ver}/binary/tarball/pmm-client-${ver}-x86_64.tar.gz")
        def clientRepo = isNewest ? 'experimental' : 'testing'
        def serverLatest = isNewest ? latestDevVersion : latestVersion

        variants.each { variant ->
            def name = "upgrade / ${ver} ${variant}"
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
}

timestamps {
    def serverImage = params.DOCKER_VERSION.trim()
    def amiId = pmmVersion('v3-ami').values()[-1]
    def compatVersions = pmmVersion('v3')[-5..-1]
    def upgradeVersions = pmmVersion('v3')[-6..-1]
    def clientDebVersions = []
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
                // Ask the apt index which client debs still exist rather than
                // assuming a retention depth; the client containers are jammy.
                // A repo blip leaves the list empty, which installs every source
                // from its tarball rather than failing the whole nightly here.
                try {
                    clientDebVersions = sh(
                        returnStdout: true,
                        script: '''curl -fsSL https://repo.percona.com/pmm3-client/apt/dists/jammy/main/binary-amd64/Packages | awk '/^Version:/{split($2,a,"-"); print a[1]}' | sort -u'''
                    ).trim().tokenize()
                } catch (err) {
                    echo "Could not read the pmm3-client apt index (${err.message}); every upgrade source will install from its tarball."
                }
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
  client source   : deb ${upgradeVersions.findAll { it in clientDebVersions }.join(', ')} | tarball ${upgradeVersions.findAll { !(it in clientDebVersions) }.join(', ')}
  dev version     : ${latestDevVersion}
  on-demand       : ${params.USE_ONDEMAND}"""
    }

    // Insertion order is the display order in the stage view, so families stay
    // adjacent even though every branch is a sibling.
    def branches = [:]

    branches['nightly / docker'] = nightlyGha('nightly / docker', serverImage, amiId)
    branches['nightly / docker arm64'] = nightlyGha('nightly / docker arm64', serverImage, amiId, [SERVER_ARCH: 'arm64'])
    branches['nightly / ami'] = nightlyGha('nightly / ami', serverImage, amiId, [SERVER_TYPE: 'ami'])
    branches['nightly / helm'] = nightlyGha('nightly / helm', serverImage, amiId, [SERVER_TYPE: 'helm', ADMIN_PASSWORD: 'admin1'])
    branches['nightly / ha'] = nightlyGha('nightly / ha', serverImage, amiId, [SERVER_TYPE: 'ha', ADMIN_PASSWORD: 'admin1'])

    compatVersions.each { ver ->
        def name = "compat / client ${ver}"
        branches[name] = nightlyGha(name, serverImage, amiId, [CLIENT_VERSION: ver])
    }

    [['@ia', ''],
     ['@instances', '--database ssl_mysql --database haproxy --database external'],
     ['@gcp', '']].each { t ->
        def tag = t[0]
        def clients = t[1]
        def name = "ui / ${tag}"
        branches[name] = suite(name, 'pmm3-ui-tests', [
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

    packageBranches(branches, 'pkg amd64', 'nightly-package-testing-amd64', 'amd64', serverImage, latestDevVersion)
    packageBranches(branches, 'pkg arm64', 'nightly-package-testing-arm64', 'arm64', serverImage, latestDevVersion)
    upgradeBranches(branches, upgradeVersions, clientDebVersions, latestVersion, latestDevVersion)

    branches['upgrade / ami'] = suite('upgrade / ami', 'pmm3-upgrade-ami-test', [
        string(name: 'PMM_QA_GIT_BRANCH',   value: params.PMM_QA_GIT_BRANCH),
        booleanParam(name: 'IS_RC_TESTING', value: false),
        booleanParam(name: 'USE_ONDEMAND',  value: params.USE_ONDEMAND),
    ])

    branches['gssapi'] = suite('gssapi', 'pmm3-ui-tests-nightly-gssapi', [
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

    branches['openshift'] = suite('openshift', 'openshift-helm-tests', [
        string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
        string(name: 'PMM_CHART_BRANCH',  value: 'latest'),
        string(name: 'IMAGE_REPO',        value: serverImage.split(':')[0]),
        string(name: 'IMAGE_TAG',         value: serverImage.split(':')[1]),
        string(name: 'OPENSHIFT_VERSION', value: 'latest'),
        booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
    ])

    if (params.RUN_GH_RC_SUITE) {
        branches['github rc-testing-suite'] = {
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

    parallel branches

    stage('Report') {
        // Grouped, clickable summary. The stage graph is at the mercy of whichever
        // viewer you open; this is the surface that reads the same everywhere.
        def order = []
        def byFam = [:]
        results.each { name, r ->
            def cut = name.indexOf(' / ')
            def fam = cut > 0 ? name.substring(0, cut) : 'standalone'
            def leaf = cut > 0 ? name.substring(cut + 3) : name
            if (byFam[fam] == null) {
                byFam[fam] = []
                order.add(fam)
            }
            byFam[fam].add([leaf: leaf, res: r])
        }

        def tally = []
        def text = []
        def totalBad = 0
        def totalWarn = 0

        order.each { fam ->
            def items = byFam[fam]
            def bad = 0
            def warn = 0
            items.each { i ->
                if (i.res.result == 'FAILURE' || i.res.result == 'ABORTED') {
                    bad = bad + 1
                } else if (i.res.result == 'UNSTABLE') {
                    warn = warn + 1
                }
            }
            totalBad = totalBad + bad
            totalWarn = totalWarn + warn
            def ok = items.size() - bad - warn

            tally.add("${fam} ${ok}/${items.size()}")
            text.add("")
            text.add("${fam}  (${ok} ok, ${bad} failed, ${warn} unstable)")
            items.each { i ->
                def r = i.res
                text.add("  ${(r.result ?: 'UNKNOWN').padRight(9)} ${i.leaf}")
                text.add("            ${r.url}")
            }
        }

        // Plain text on purpose. Whether a description renders HTML depends on the
        // controller's Markup Formatter, and the API and Blue Ocean hand back the
        // raw string either way — pmm3-nightly-orchestrator #2 showed 50 anchor
        // tags as source. The per-suite URLs live in the log below, and the stage
        // view is already clickable.
        currentBuild.description = "${serverImage} / client ${params.CLIENT_VERSION}" +
            "  |  ${results.size() - totalBad - totalWarn} ok, ${totalBad} failed" +
            (totalWarn > 0 ? ", ${totalWarn} unstable" : '') +
            "  |  " + tally.join(' \u00b7 ')
        echo "Nightly release readiness \u2014 ${results.size()} suites\n" + text.join('\n') +
            "\n\nfailed: ${totalBad}   unstable: ${totalWarn}   ok: ${results.size() - totalBad - totalWarn}"

        // catchError has already set the build result; only escalate here, never
        // reset to SUCCESS.
        if (totalBad > 0) {
            currentBuild.result = 'FAILURE'
        } else if (totalWarn > 0 && currentBuild.result != 'FAILURE') {
            currentBuild.result = 'UNSTABLE'
        }
    }
}
