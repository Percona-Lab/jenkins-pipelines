library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.json.JsonBuilder

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

def mirrorChild(String name, String jobName, def run) {
    def stages = []
    try {
        stages = childStages(run)
    } catch (err) {
        echo "[${name}] child stages unavailable (${err.message}); rendering the suite as one stage"
    }

    def blue = "${env.JENKINS_URL}blue/organizations/jenkins/${jobName}/detail/${jobName}/${run.number}/pipeline"

    int i = 0
    while (i < stages.size()) {
        def child = stages[i]
        if (child.status == 'NOT_EXECUTED') {
            // A declarative child still runs its post actions after a failure, so
            // never-run stages can sit in the middle; each run of them is one node.
            int j = i
            while (j < stages.size() && stages[j].status == 'NOT_EXECUTED') {
                j++
            }
            def names = stages[i..<j].collect { it.name }
            stage(names.size() == 1 ? names[0] : "${names.size()} stages skipped") {
                catchError(buildResult: null, stageResult: 'NOT_BUILT') {
                    error("never ran in ${jobName} #${run.number}: ${names.join(', ')}")
                }
            }
            i = j
            continue
        }
        stage("${child.name} · ${humanMs(child.durationMillis)}") {
            // Blue Ocean draws a stage with no steps as never run (hollow), whatever
            // its status, so every mirrored stage carries at least this echo.
            echo "${child.name}: ${child.status} in ${humanMs(child.durationMillis)} — ${blue}/${child.id}"
            // buildResult stays null on purpose: the suite's own verdict above
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
        i++
    }
}

def suite(String name, String jobName, List jobParams) {
    return {
        // The row label wraps and crops, so the first node leads with the part
        // that tells the lanes of a family apart. The stage name is fixed before
        // the child starts, so the run number can only go in the node's log.
        def cut = name.lastIndexOf(' / ')
        stage(cut > 0 ? name.substring(cut + 3) : name) {
            def run = build job: jobName, parameters: jobParams, wait: true, propagate: false
            results[name] = [job: jobName, number: run.number, url: run.absoluteUrl, result: run.result]
            echo "[${name}] ${jobName} #${run.number} ${run.result} -> ${run.absoluteUrl}"

            if (run.result == 'FAILURE' || run.result == 'ABORTED') {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    error("${name}: ${jobName} #${run.number} ${run.result} — ${run.absoluteUrl}")
                }
            } else if (run.result == 'UNSTABLE') {
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    error("${name}: ${jobName} #${run.number} UNSTABLE — ${run.absoluteUrl}")
                }
            }

            mirrorChild(name, jobName, run)
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

def packageBranches(Map branches, String prefix, String jobName, String serverArch, String serverImage, String devVersion, String gaVersion) {
    // TESTS is the playbook; the trailing spaces in CLIENTS are load-bearing —
    // they keep otherwise identical parameter sets from collapsing in the queue.
    def variants = [
        ['integration',          'pmm3-client_integration',                     '--help  ',    false],
        ['auth config',          'pmm3-client_integration_auth_config',         '--help   ',   false],
        ['auth register',        'pmm3-client_integration_auth_register',       ' --help',     false],
        ['custom path',          'pmm3-client_integration_custom_path',         '  --help',    true],
        ['custom port',          'pmm3-client_integration_custom_port',         '   --help',   true],
        ['upgrade',              'pmm3-client_integration_upgrade',             '    --help',  false],
        ['upgrade custom path',  'pmm3-client_integration_upgrade_custom_path', '    --help ', true],
        ['upgrade custom port',  'pmm3-client_integration_upgrade_custom_port', '    --help  ', false],
    ]
    variants.each { v ->
        def label = v[0]
        def tests = v[1]
        def clients = v[2]
        def gaOnly = v[3] && serverArch == 'arm64'
        def pmmVersionLabel = gaOnly ? gaVersion : devVersion
        def name = gaOnly ? "${prefix} / ${label} (GA ${gaVersion})" : "${prefix} / ${label}"
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

def supportsUiUpgrade(String version) {
    def parts = version.tokenize('.')
    def major = parts[0].toInteger()
    def minor = parts.size() > 1 ? parts[1].toInteger() : 0
    return major < 3 || (major == 3 && minor < 9)
}

def upgradeBranches(Map branches, List pmmVersions, List clientDebVersions, String serverImage, String latestDevVersion) {
    def variants = ['SSL', 'EXTERNAL SERVICES', 'OTHERS']
    pmmVersions.each { ver ->
        // The apt pool carries only the newest few client debs.
        def clientVersion = ver in clientDebVersions
            ? ver
            : "https://downloads.percona.com/downloads/pmm3/${ver}/binary/tarball/pmm-client-${ver}-x86_64.tar.gz"

        def upgradeType = supportsUiUpgrade(ver) ? params.UPGRADE_TYPE : 'DOCKER'

        variants.each { variant ->
            def name = "upgrade / ${ver} ${variant}"
            branches[name] = suite(name, 'pmm3-upgrade-test-runner', [
                string(name: 'PMM_UI_PRE_UPGRADE_GIT_BRANCH', value: "pmm-${ver}"),
                string(name: 'DOCKER_TAG',                    value: "percona/pmm-server:${ver}"),
                string(name: 'DOCKER_TAG_UPGRADE',            value: serverImage),
                string(name: 'CLIENT_VERSION',                value: clientVersion),
                string(name: 'CLIENT_REPOSITORY',             value: 'experimental'),
                string(name: 'PMM_SERVER_LATEST',             value: latestDevVersion),
                string(name: 'PMM_QA_GIT_BRANCH',             value: params.PMM_QA_GIT_BRANCH),
                string(name: 'UPGRADE_FLAG',                  value: variant),
                string(name: 'UPGRADE_TYPE',                  value: upgradeType),
                booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
            ])
        }
    }
}

def amiUpgradeBranches(Map branches, String serverImage, String latestDevVersion) {
    // The AMI equivalent of upgradeBranches, and the last matrix wrapper this job
    // replaces: pmm3-upgrade-ami-test fanned these five out itself, on an executor,
    // behind a retry(2), and reported them as one box.
    def amis = pmmVersion('v3-ami')
    pmmVersion('v3')[-5..-1].each { ver ->
        def name = "upgrade / ami ${ver}"
        branches[name] = suite(name, 'pmm3-upgrade-ami-test-runner', [
            string(name: 'PMM_UI_PRE_UPGRADE_GIT_BRANCH', value: "pmm-${ver}"),
            string(name: 'PMM_QA_GIT_BRANCH',             value: params.PMM_QA_GIT_BRANCH),
            string(name: 'AMI_TAG',                       value: amis[ver] ?: ''),
            string(name: 'DOCKER_TAG_UPGRADE',            value: serverImage),
            string(name: 'CLIENT_VERSION',                value: ver),
            string(name: 'CLIENT_REPOSITORY',             value: 'experimental'),
            string(name: 'PMM_SERVER_LATEST',             value: latestDevVersion),
            booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
        ])
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
                clientDebVersions = sh(
                    returnStdout: true,
                    script: '''curl -fsSL https://repo.percona.com/pmm3-client/apt/dists/jammy/main/binary-amd64/Packages | awk '/^Package: pmm-client$/{p=1} p&&/^Version:/{split($2,a,"-"); print a[1]; p=0}' | sort -u'''
                ).trim().tokenize()
            } finally {
                deleteDir()
            }
        }
        def uiCapable = upgradeVersions.findAll { supportsUiUpgrade(it) }
        def dockerOnly = upgradeVersions.findAll { !supportsUiUpgrade(it) }
        currentBuild.description = "server=${serverImage} client=${params.CLIENT_VERSION}"
        echo """Nightly release readiness
  server image    : ${serverImage}
  client          : ${params.CLIENT_VERSION}
  AMI             : ${amiId}
  compat clients  : ${compatVersions.join(', ')}
  upgrade from    : ${upgradeVersions.join(', ')}
  client source   : deb ${upgradeVersions.findAll { it in clientDebVersions }.join(', ')} | tarball ${upgradeVersions.findAll { !(it in clientDebVersions) }.join(', ')}
  upgrade path    : UI-capable ${uiCapable.join(', ')} | Docker-only ${dockerOnly.join(', ')}
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

    packageBranches(branches, 'pkg amd64', 'nightly-package-testing-amd64', 'amd64', serverImage, latestDevVersion, latestVersion)
    packageBranches(branches, 'pkg arm64', 'nightly-package-testing-arm64', 'arm64', serverImage, latestDevVersion, latestVersion)
    upgradeBranches(branches, upgradeVersions, clientDebVersions, serverImage, latestDevVersion)

    amiUpgradeBranches(branches, serverImage, latestDevVersion)

    branches['gssapi'] = suite('gssapi', 'pmm3-ui-tests-nightly-gssapi', [
        string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
        string(name: 'SERVER_TYPE',       value: 'docker'),
        string(name: 'DOCKER_VERSION',    value: serverImage),
        string(name: 'CLIENT_VERSION',    value: 'https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-dynamic-ol9-latest.tar.gz'),
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
