library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// trigger — return a closure (for parallel{}) that runs a downstream job and
// waits for it. A failed or aborted job fails the pipeline. An UNSTABLE job only
// marks it unstable and the pipeline goes on: the shared artifact helpers mark a
// job unstable when Hetzner S3 falls back to AWS, and with propagate: true that
// stopped every later stage although the packages were built and published.
//
// onDone, when given, is called with the finished run (SUCCESS or UNSTABLE), so
// the caller can record which build produced a component.
def trigger(String jobName, List jobParams, Closure onDone = null) {
    return {
        def run = build job: jobName, parameters: jobParams, wait: true, propagate: false
        String result = run.result
        if (result == 'UNSTABLE') {
            unstable("${jobName} #${run.number} finished UNSTABLE, continuing: ${run.absoluteUrl}")
        } else if (result != 'SUCCESS') {
            error("${jobName} #${run.number} finished ${result}: ${run.absoluteUrl}")
        }
        if (onDone != null) {
            onDone(run)
        }
    }
}

// ---------------------------------------------------------------------------
// Release manifest
//
// Every run archives two files:
//   valkey-release.txt           for people: one line per component with its
//                                version-release and PATH_TO_BUILD
//   valkey-release-manifest.tsv  read back by the next run
// A component is recorded only once waitForPublished has confirmed the repo
// serves the build this run made. Components this run did not build are
// carried over from the previous run's manifest when it is for the same
// VALKEY_REPO and CHANNEL, so the latest manifest always describes one
// complete set. RELEASE releases what the manifest lists.
// ---------------------------------------------------------------------------

// The release order is the dependency order: server, then the modules and
// admin, then the bundle (which depends on server + modules).
@groovy.transform.Field
List COMPONENTS = [
    [key: 'server', deb: 'percona-valkey-server',       rpm: 'percona-valkey',              job: 'hetzner-valkey-RELEASE',        ver: 'VALKEY_VERSION',        rel: 'SERVER_RELEASE'],
    [key: 'json',   deb: 'percona-valkey-json',         rpm: 'percona-valkey-json',         job: 'hetzner-valkey-json-RELEASE',   ver: 'VALKEY_JSON_VERSION',   rel: 'JSON_RELEASE'],
    [key: 'bloom',  deb: 'percona-valkey-bloom',        rpm: 'percona-valkey-bloom',        job: 'hetzner-valkey-bloom-RELEASE',  ver: 'VALKEY_BLOOM_VERSION',  rel: 'BLOOM_RELEASE'],
    [key: 'search', deb: 'percona-valkey-search',       rpm: 'percona-valkey-search',       job: 'hetzner-valkey-search-RELEASE', ver: 'VALKEY_SEARCH_VERSION', rel: 'SEARCH_RELEASE'],
    [key: 'ldap',   deb: 'percona-valkey-ldap',         rpm: 'percona-valkey-ldap',         job: 'hetzner-valkey-ldap-RELEASE',   ver: 'VALKEY_LDAP_VERSION',   rel: 'LDAP_RELEASE'],
    [key: 'audit',  deb: 'percona-valkey-audit',        rpm: 'percona-valkey-audit',        job: 'hetzner-valkey-audit-RELEASE',  ver: 'VALKEY_AUDIT_VERSION',  rel: 'AUDIT_RELEASE'],
    [key: 'admin',  deb: 'percona-valkey-admin-server', rpm: 'percona-valkey-admin-server', job: 'hetzner-valkey-admin-RELEASE',  ver: 'VALKEY_ADMIN_VERSION',  rel: 'ADMIN_RELEASE'],
    [key: 'bundle', deb: 'percona-valkey-bundle',       rpm: 'percona-valkey-bundle',       job: 'hetzner-valkey-bundle-RELEASE', ver: 'VALKEY_BUNDLE_VERSION', rel: 'BUNDLE_RELEASE'],
]

@groovy.transform.Field
List MANIFEST_FIELDS = ['key', 'version', 'deb', 'rpm', 'path', 'rpm_build_time', 'job', 'build', 'url', 'from_build']

@groovy.transform.Field
String MANIFEST_FILE = 'valkey-release-manifest.tsv'

@groovy.transform.Field
String MANIFEST_TEXT = 'valkey-release.txt'

// key -> [job, build, url, path] for each package job this run finished
@groovy.transform.Field
Map BUILT = [:]

// key -> manifest entry (MANIFEST_FIELDS -> String)
@groovy.transform.Field
Map MANIFEST = [:]

// What the release stages did, for valkey-release.txt
@groovy.transform.Field
List RELEASED = []

@groovy.transform.Field
List RELEASE_IMAGES_PUSHED = []

// Set by the approval stage: the release stages run only when it is 'Release'.
@groovy.transform.Field
String RELEASE_DECISION = ''

boolean releaseApproved() {
    return params.RELEASE && RELEASE_DECISION == 'Release'
}

String agentLabel() {
    return params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon'
}

Map componentByKey(String key) {
    for (Map c : COMPONENTS) {
        if (c.key == key) {
            return c
        }
    }
    return null
}

Map componentByDeb(String deb) {
    for (Map c : COMPONENTS) {
        if (c.deb == deb) {
            return c
        }
    }
    return null
}

// The package jobs end with
// currentBuild.description = "... Path to packages: experimental/<path>", which
// is the PATH_TO_BUILD hetzner-RELEASE-from-repository takes.
@NonCPS
String packagesPath(String description) {
    def m = (description ?: '') =~ /Path to packages: (\S+)/
    return m.find() ? m.group(1) : ''
}

def recordBuild(String key, def run) {
    Map c = componentByKey(key)
    String path = packagesPath(run.description)
    if (!path) {
        unstable("${c.job} #${run.number}: no 'Path to packages:' in its description, the manifest has no PATH_TO_BUILD for ${key}")
    }
    BUILT[key] = [job: c.job, build: "${run.number}".toString(), url: "${run.absoluteUrl}".toString(), path: path ?: '-']
}

// buildTimes: deb name -> RPM build time, as returned by waitForPublished
def confirmPublished(Map buildTimes) {
    for (def entry : buildTimes.entrySet()) {
        Map c = componentByDeb(entry.key)
        Map b = BUILT[c.key]
        if (b == null) {
            continue
        }
        MANIFEST[c.key] = [
            key:            c.key,
            version:        "${params[c.ver]}-${params[c.rel]}".toString(),
            deb:            c.deb,
            rpm:            c.rpm,
            path:           b.path,
            rpm_build_time: "${entry.value}".toString(),
            job:            b.job,
            build:          b.build,
            url:            b.url,
            from_build:     "${env.BUILD_NUMBER}".toString(),
        ]
    }
}

// Read the TSV manifest text; [repo, channel, entries]
Map parseManifest(String text) {
    String repo = ''
    String channel = ''
    Map entries = [:]
    for (String line : text.readLines()) {
        List cols = line.tokenize('\t')
        if (line.startsWith('#repo') && cols.size() == 2) {
            repo = cols[1]
        } else if (line.startsWith('#channel') && cols.size() == 2) {
            channel = cols[1]
        } else if (!line.startsWith('#') && cols.size() == MANIFEST_FIELDS.size()) {
            Map e = [:]
            for (int i = 0; i < MANIFEST_FIELDS.size(); i++) {
                e[MANIFEST_FIELDS[i]] = cols[i]
            }
            entries[e.key] = e
        }
    }
    return [repo: repo, channel: channel, entries: entries]
}

// TSV for the next run. Empty values are written as '-' so tokenize() keeps
// every column.
String renderManifest() {
    List lines = ["#repo\t${params.VALKEY_REPO}".toString(), "#channel\t${params.CHANNEL}".toString(), '#' + MANIFEST_FIELDS.join('\t')]
    for (Map c : COMPONENTS) {
        Map e = MANIFEST[c.key]
        if (e == null) {
            continue
        }
        List row = []
        for (String f : MANIFEST_FIELDS) {
            row << (e[f] ?: '-').toString()
        }
        lines << row.join('\t')
    }
    return lines.join('\n') + '\n'
}

String renderManifestText() {
    List lines = ["Valkey release manifest: ${params.VALKEY_REPO} / ${params.CHANNEL} (${env.JOB_NAME} #${env.BUILD_NUMBER})".toString(), '']
    for (Map c : COMPONENTS) {
        Map e = MANIFEST[c.key]
        if (e == null) {
            lines << "${c.key}: not in the manifest (never built into ${params.VALKEY_REPO}/${params.CHANNEL} by this job)".toString()
            continue
        }
        String origin = e.from_build == "${env.BUILD_NUMBER}".toString() ? 'built in this run' : "carried over from #${e.from_build}"
        lines << "${c.key}: ${e.deb} ${e.version}  PATH_TO_BUILD=${releasePath(e.path)}  (${e.job} #${e.build}, ${origin})".toString()
    }
    if (RELEASED) {
        lines << ''
        lines << "Released to ${params.VALKEY_REPO}/release in this run: ${RELEASED.join(' ')}".toString()
    } else if (params.RELEASE && RELEASE_DECISION && RELEASE_DECISION != 'Release') {
        lines << ''
        lines << "Release not approved in this run (${RELEASE_DECISION}): nothing was released.".toString()
    }
    if (RELEASE_IMAGES_PUSHED) {
        lines << "Release images built in this run: ${RELEASE_IMAGES_PUSHED.join(', ')}".toString()
    }
    return lines.join('\n') + '\n'
}

def loadPreviousManifest() {
    copyArtifacts(projectName: env.JOB_NAME, selector: lastWithArtifacts(), filter: MANIFEST_FILE,
                  target: 'previous-manifest', optional: true, fingerprintArtifacts: false)
    String f = "previous-manifest/${MANIFEST_FILE}"
    if (!fileExists(f)) {
        echo "No previous manifest: starting a new one for ${params.VALKEY_REPO}/${params.CHANNEL}"
        return
    }
    Map prev = parseManifest(readFile(f))
    if (prev.repo != params.VALKEY_REPO || prev.channel != params.CHANNEL) {
        echo "The previous manifest is for ${prev.repo}/${prev.channel}, not ${params.VALKEY_REPO}/${params.CHANNEL}: starting a new one"
        return
    }
    MANIFEST.putAll(prev.entries)
    echo "Carried over from the previous manifest: ${prev.entries.keySet().join(', ') ?: 'nothing'}"
}

def writeManifest() {
    writeFile file: MANIFEST_FILE, text: renderManifest()
    writeFile file: MANIFEST_TEXT, text: renderManifestText()
    archiveArtifacts artifacts: "${MANIFEST_FILE},${MANIFEST_TEXT}"
    echo readFile(MANIFEST_TEXT)
}

// The components to release, in release order. Stops the run when one is
// unknown or not in the manifest.
List releaseKeys() {
    String want = (params.RELEASE_COMPONENTS ?: 'ALL').trim()
    List wanted = want == 'ALL' ? [] : want.tokenize(', ')
    List known = []
    for (Map c : COMPONENTS) {
        known << c.key
    }
    for (String w : wanted) {
        if (!known.contains(w)) {
            error("RELEASE_COMPONENTS: unknown component '${w}' (known: ${known.join(',')})")
        }
    }
    List keys = []
    List missing = []
    for (Map c : COMPONENTS) {
        if (wanted && !wanted.contains(c.key)) {
            continue
        }
        if (MANIFEST[c.key] == null) {
            missing << c.key
        } else {
            keys << c.key
        }
    }
    if (missing) {
        error("Not in the manifest, so there is nothing to release for: ${missing.join(', ')}. Build them first, or leave them out of RELEASE_COMPONENTS.")
    }
    return keys
}

// releasePath — the PATH_TO_BUILD hetzner-RELEASE-from-repository takes: the
// package job's path without its leading "experimental/".
String releasePath(String path) {
    String prefix = 'experimental/'
    return path.startsWith(prefix) ? path.substring(prefix.length()) : path
}

// The version part of a manifest version-release ("9.2.0-3" -> "9.2.0").
String upstreamVersion(String key, String fallback) {
    Map e = MANIFEST[key]
    return e == null ? fallback : e.version.tokenize('-')[0]
}

// waitForPublished — poll the public repo until every package in
// [name: "<version>-<release>"] is served from the build this run just made,
// so the next stage builds against packages repo.percona.com actually serves.
//
// Listing the version-release is not enough: republishing the same
// version-release is allowed, so it can be listed from an earlier build while
// the new files are still being copied. A build that starts then reads the old
// metadata, downloads the new file and dnf rejects it ("checksum doesn't
// match"). So a package only counts when:
//   - yum: its entry in the EL9 x86_64 primary.xml has an RPM build time no
//     earlier than `since` (when the child job started). sync2ProdAutoBuild runs
//     createrepo over the EL versions in `ls` order (10, 2023, 8, 9) and the
//     arches as aarch64 then x86_64, so EL9 x86_64 is the last directory it
//     updates.
//   - apt: its file is in the noble amd64 Packages index, and the dist's
//     Release file is dated no earlier than `since` (reprepro re-exported the
//     index during this run).
// `since` allows 120 s of clock skew between the controller and build agents.
//
// channel: the repo channel to check (default: CHANNEL).
// exact:   deb name -> RPM build time. When given, the RPM must have exactly that
//          build time instead of one after `since`: a release copies the build
//          recorded in the manifest, built before this run.
// Returns deb name -> the RPM build time the repo serves.
Map waitForPublished(Map packages, long since, String channel = null, Map exact = [:]) {
    String ch = channel ?: params.CHANNEL
    // Released packages are in the yum "release" directory but the apt "main"
    // component.
    String aptCh = ch == 'release' ? 'main' : ch
    String dist = 'noble'
    int waitMinutes = 30
    long notBefore = since - 120
    String aptDist = "https://repo.percona.com/${params.VALKEY_REPO}/apt/dists/${dist}"
    String index = "${aptDist}/${aptCh}/binary-amd64/Packages"
    String yumRepo = "https://repo.percona.com/${params.VALKEY_REPO}/yum/${ch}/9/RPMS/x86_64"
    List expected = []
    List expectedRpm = []
    Map rpmOfDeb = [:]
    for (def entry : packages.entrySet()) {
        expected << "/${entry.key}_${entry.value}.${dist}_"
        // The server's main RPM is percona-valkey; its deb is percona-valkey-server.
        String rpmName = entry.key == 'percona-valkey-server' ? 'percona-valkey' : entry.key
        // toString(): a GString never equals a String map key (different
        // hashCode), so rpmBuilt.containsKey() below would always miss it.
        String rpmFile = "${rpmName}-${entry.value}.el9.x86_64.rpm".toString()
        expectedRpm << rpmFile
        rpmOfDeb["${entry.key}".toString()] = rpmFile
    }
    Map exactByRpm = [:]
    for (def entry : exact.entrySet()) {
        exactByRpm[rpmOfDeb["${entry.key}".toString()]] = "${entry.value}".toString() as long
    }
    echo "Waiting up to ${waitMinutes} min for ${packages.keySet().join(', ')} built after ${notBefore} (epoch) in ${index} and ${yumRepo}"
    int attempts = waitMinutes * 2
    for (int i = 1; i <= attempts; i++) {
        String listed = sh(returnStdout: true, script: "curl -fsS --max-time 60 '${index}' | grep '^Filename:' || true")
        String releaseDate = sh(returnStdout: true, script: """
            set +x
            d=\$(curl -fsS --max-time 60 '${aptDist}/Release' | sed -n 's/^Date: //p' | head -1)
            [ -n "\$d" ] && date -d "\$d" +%s || echo 0
        """).trim()
        // One "<build time> <file name>" line per package in primary.xml.
        // createrepo writes <time file=".." build=".."/> before <location href="..">.
        String rpms = sh(returnStdout: true, script: """
            set +x
            primary=\$(curl -fsS --max-time 60 '${yumRepo}/repodata/repomd.xml' | grep -oE 'repodata/[^"]*primary[.]xml[.]gz' | head -1)
            [ -n "\$primary" ] && curl -fsS --max-time 60 "${yumRepo}/\$primary" | gzip -dc | awk '
                /<time / { if (match(\$0, /build="[0-9]+"/)) bt = substr(\$0, RSTART + 7, RLENGTH - 8) }
                /<location href=/ { if (match(\$0, /href="[^"]+"/)) print bt, substr(\$0, RSTART + 6, RLENGTH - 7) }
            ' || true
        """)
        Map rpmBuilt = [:]
        for (String line : rpms.readLines()) {
            List parts = line.trim().tokenize(' ')
            if (parts.size() == 2) {
                rpmBuilt[parts[1].toString()] = parts[0] as long
            }
        }
        List missing = []
        for (String file : expected) {
            if (!listed.contains(file)) {
                missing << file
            }
        }
        if (expected && (releaseDate as long) < notBefore) {
            missing << "apt index not re-exported yet (Release date ${releaseDate})"
        }
        for (String file : expectedRpm) {
            if (!rpmBuilt.containsKey(file)) {
                missing << file
            } else if (exactByRpm.containsKey(file)) {
                if (rpmBuilt[file] != exactByRpm[file]) {
                    missing << "${file} (serves the build from ${rpmBuilt[file]}, want ${exactByRpm[file]})"
                }
            } else if (rpmBuilt[file] < notBefore) {
                missing << "${file} (still the earlier build, built ${rpmBuilt[file]})"
            }
        }
        if (!missing) {
            echo "Published to ${ch}: ${packages.keySet().join(', ')}"
            Map served = [:]
            for (def entry : rpmOfDeb.entrySet()) {
                served[entry.key] = rpmBuilt[entry.value]
            }
            return served
        }
        echo "Attempt ${i}/${attempts}: not served yet: ${missing.join(' ')}"
        sleep(time: 30, unit: 'SECONDS')
    }
    error("Packages were not published to ${index} within ${waitMinutes} minutes")
    return [:]
}

// epochNow — the current time in seconds, taken on the agent as waitForPublished
// compares it with times the repo reports.
long epochNow() {
    return sh(returnStdout: true, script: 'date +%s').trim() as long
}

// fetchIndex — the body of a repo index URL, or '' when the repo has no such
// index yet (HTTP 404: a new repo or channel). Any other failure stops the run,
// so a network problem can't make the pre-flight check pass by accident.
def fetchIndex(String url) {
    String out = sh(returnStdout: true, script: """
        code=\$(curl -sS --max-time 60 -o index.tmp -w '%{http_code}' '${url}') || code=000
        case "\$code" in
            200) cat index.tmp ;;
            404) ;;
            *)   echo "HTTP \$code for ${url}" >&2; rm -f index.tmp; exit 1 ;;
        esac
        rm -f index.tmp
    """)
    return out
}

// checkNotPublished — fail the run before anything is built when one of the
// requested <version>-<release> builds is already in the repo. Publishing the
// same version-release twice gives a file whose checksum no longer matches the
// metadata clients already cached, so dnf/apt refuse it ("checksum doesn't
// match", "Server reports Content-Length ..."). Every rebuild needs a new
// release number. Checks the target channel and main (where testing is
// promoted to), in the apt noble index and the EL9 x86_64 yum directory.
// packages: [debName: [rpm: rpmName, ver: "<version>-<release>"]]
def checkNotPublished(Map packages) {
    List channels = params.CHANNEL == 'main' ? ['main'] : [params.CHANNEL, 'main']
    List clashes = []
    for (String ch : channels) {
        String base = "https://repo.percona.com/${params.VALKEY_REPO}"
        String apt = fetchIndex("${base}/apt/dists/noble/${ch}/binary-amd64/Packages")
        String yum = fetchIndex("${base}/yum/${ch}/9/RPMS/x86_64/")
        for (def entry : packages.entrySet()) {
            String deb = "/${entry.key}_${entry.value.ver}.noble_"
            String rpm = "${entry.value.rpm}-${entry.value.ver}.el9.x86_64.rpm"
            if (apt.contains(deb) || yum.contains(rpm)) {
                clashes << "${entry.key} ${entry.value.ver} (${ch})"
            }
        }
    }
    if (clashes) {
        String msg = "Already published in ${params.VALKEY_REPO}: ${clashes.join(', ')}. " +
                     "Raise the release number for each of them, or set ALLOW_REPUBLISH to override."
        if (params.ALLOW_REPUBLISH) {
            echo "Republishing (ALLOW_REPUBLISH is set): ${msg}"
        } else {
            error(msg)
        }
    } else {
        echo "Pre-flight OK: none of ${packages.keySet().join(', ')} is published yet at the requested version-release"
    }
}

pipeline {
    // No pipeline-wide agent: each stage takes its own, so waiting for the
    // release approval holds no executor.
    agent none
    parameters {
        choice(
            choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra for all triggered jobs',
            name: 'CLOUD')
        choice(
            choices: ['testing', 'experimental'],
            description: 'Repo channel: packages are published here, Docker images and tests consume from it',
            name: 'CHANNEL')
        string(
            defaultValue: 'valkey-91',
            description: 'Repository every package publishes to (and the test stage installs from)',
            name: 'VALKEY_REPO')
        string(
            defaultValue: 'perconalab',
            description: 'DockerHub namespace images publish to (server=<ns>/valkey, bundle=<ns>/valkey-bundle, modules=<ns>/valkey-mod-<name>)',
            name: 'IMAGE_NAMESPACE')

        // --- What to build (tick to include) ---
        booleanParam(defaultValue: true, description: 'Package: percona-valkey (server)',        name: 'PKG_SERVER')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-json',            name: 'PKG_JSON')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-bloom',           name: 'PKG_BLOOM')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-search',          name: 'PKG_SEARCH')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-ldap',            name: 'PKG_LDAP')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-audit',           name: 'PKG_AUDIT')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-bundle',          name: 'PKG_BUNDLE')
        booleanParam(defaultValue: true,  description: 'Package: percona-valkey-admin',          name: 'PKG_ADMIN')
        booleanParam(defaultValue: true, description: 'Docker image: valkey (server)',           name: 'IMG_VALKEY')
        booleanParam(defaultValue: true, description: 'Docker image: valkey-bundle',             name: 'IMG_BUNDLE')
        booleanParam(defaultValue: true, description: 'Docker image: valkey modules',            name: 'IMG_MODULE')
        booleanParam(defaultValue: true, description: 'Docker image: valkey-admin',              name: 'IMG_ADMIN')
        booleanParam(defaultValue: false, description: 'Docker images: run the Trivy scan (off by default)', name: 'RUN_TRIVY')
        booleanParam(defaultValue: true, description: 'Run the package test suite (final stage)', name: 'RUN_TESTS')

        // --- Versions / refs ---
        string(defaultValue: '9.1.3', description: 'Valkey server version (server RELEASE, Docker, tests)', name: 'VALKEY_VERSION')
        string(defaultValue: '9.1.3', description: 'Upstream valkey-io/valkey tag/branch (server RELEASE)', name: 'GIT_BRANCH')
        string(defaultValue: '9.1.3', description: 'valkey-packaging branch (every package, Docker, tests)', name: 'PACKAGING_BRANCH')
        // --- Package versions and release numbers ---
        // Each package has its own release number: a rebuild of an unchanged
        // version has to go above what the repo already serves, while a new
        // upstream version starts again at 1.
        string(defaultValue: '1',     description: 'percona-valkey (server) release number', name: 'SERVER_RELEASE')
        string(defaultValue: '1.0.3', description: 'valkey-json version',   name: 'VALKEY_JSON_VERSION')
        string(defaultValue: '1',     description: 'valkey-json release number',   name: 'JSON_RELEASE')
        string(defaultValue: '1.0.1', description: 'valkey-bloom version',  name: 'VALKEY_BLOOM_VERSION')
        string(defaultValue: '4',     description: 'valkey-bloom release number',  name: 'BLOOM_RELEASE')
        string(defaultValue: '1.2.1', description: 'valkey-search version', name: 'VALKEY_SEARCH_VERSION')
        string(defaultValue: '1',     description: 'valkey-search release number', name: 'SEARCH_RELEASE')
        string(defaultValue: '1.1.1', description: 'valkey-ldap version',   name: 'VALKEY_LDAP_VERSION')
        string(defaultValue: '4',     description: 'valkey-ldap release number',   name: 'LDAP_RELEASE')
        string(defaultValue: '1.0.0', description: 'valkey-audit version',  name: 'VALKEY_AUDIT_VERSION')
        string(defaultValue: '4',     description: 'valkey-audit release number',  name: 'AUDIT_RELEASE')
        string(defaultValue: 'b5ebc556e5af076fe067bf53771dea236db1d2fb', description: 'percona/valkey-audit commit/tag/branch to build', name: 'AUDIT_GIT_BRANCH')
        string(defaultValue: '9.1.3', description: 'valkey-bundle version', name: 'VALKEY_BUNDLE_VERSION')
        string(defaultValue: '2',     description: 'valkey-bundle release number', name: 'BUNDLE_RELEASE')
        string(defaultValue: '1.3.0', description: 'valkey-admin version (its upstream tag is v<version>)', name: 'VALKEY_ADMIN_VERSION')
        string(defaultValue: '1',     description: 'valkey-admin release number',  name: 'ADMIN_RELEASE')
        string(defaultValue: 'ALL',   description: 'Platforms for the test stage (see hetzner-valkey-TESTING)', name: 'TEST_PLATFORMS')
        booleanParam(defaultValue: true, description: 'Allow publishing a version-release that is already in the repo (the default). Uncheck to stop the build when the version-release is already published.', name: 'ALLOW_REPUBLISH')

        // --- Release (see valkey-release.txt in the build artifacts) ---
        booleanParam(defaultValue: false, description: 'After the build and tests, wait for approval, then release the components in the release manifest to VALKEY_REPO release (hetzner-RELEASE-from-repository) and build the release Docker images. To release without building, untick every PKG_*, IMG_* and RUN_TESTS.', name: 'RELEASE')
        string(defaultValue: 'ALL', description: 'Components to release: ALL, or a comma-separated list of server,json,bloom,search,ldap,audit,admin,bundle', name: 'RELEASE_COMPONENTS')
        booleanParam(defaultValue: true, description: 'With RELEASE: build the release Docker images from the release channel after the packages', name: 'RELEASE_IMAGES')
        string(defaultValue: 'percona', description: 'With RELEASE: DockerHub namespace for the release images (<ns>/valkey, <ns>/valkey-bundle, <ns>/valkey-mod-<name>, <ns>/valkey-admin)', name: 'RELEASE_IMAGE_NAMESPACE')
    }
    options {
        // Save pipeline state at the end of each stage, not after every step. With
        // many parallel platform stages the per-step saves overload the controller
        // ("cannot find current thread in CpsStepContext", ClosedByInterruptException
        // in saveProgram). A build cut off by a controller restart is rerun instead.
        durabilityHint('PERFORMANCE_OPTIMIZED')
        disableConcurrentBuilds()
        // Each run reads the previous run's manifest.
        copyArtifactPermission('hetzner-valkey-ALL-NEXT')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
        timestamps()
    }
    stages {
        stage('Load release manifest') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            steps {
                script {
                    loadPreviousManifest()
                }
            }
        }
        // Stop before building anything if a requested version-release is already
        // in the repo: every rebuild needs a new release number.
        stage('Pre-flight: release numbers') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            when {
                beforeAgent true
                expression { params.PKG_SERVER || params.PKG_JSON || params.PKG_BLOOM || params.PKG_SEARCH || params.PKG_LDAP || params.PKG_AUDIT || params.PKG_BUNDLE || params.PKG_ADMIN }
            }
            steps {
                script {
                    Map pkgs = [:]
                    if (params.PKG_SERVER) { pkgs['percona-valkey-server']       = [rpm: 'percona-valkey',              ver: "${params.VALKEY_VERSION}-${params.SERVER_RELEASE}"] }
                    if (params.PKG_JSON)   { pkgs['percona-valkey-json']         = [rpm: 'percona-valkey-json',         ver: "${params.VALKEY_JSON_VERSION}-${params.JSON_RELEASE}"] }
                    if (params.PKG_BLOOM)  { pkgs['percona-valkey-bloom']        = [rpm: 'percona-valkey-bloom',        ver: "${params.VALKEY_BLOOM_VERSION}-${params.BLOOM_RELEASE}"] }
                    if (params.PKG_SEARCH) { pkgs['percona-valkey-search']       = [rpm: 'percona-valkey-search',       ver: "${params.VALKEY_SEARCH_VERSION}-${params.SEARCH_RELEASE}"] }
                    if (params.PKG_LDAP)   { pkgs['percona-valkey-ldap']         = [rpm: 'percona-valkey-ldap',         ver: "${params.VALKEY_LDAP_VERSION}-${params.LDAP_RELEASE}"] }
                    if (params.PKG_AUDIT)  { pkgs['percona-valkey-audit']        = [rpm: 'percona-valkey-audit',        ver: "${params.VALKEY_AUDIT_VERSION}-${params.AUDIT_RELEASE}"] }
                    if (params.PKG_BUNDLE) { pkgs['percona-valkey-bundle']       = [rpm: 'percona-valkey-bundle',       ver: "${params.VALKEY_BUNDLE_VERSION}-${params.BUNDLE_RELEASE}"] }
                    if (params.PKG_ADMIN)  { pkgs['percona-valkey-admin-server'] = [rpm: 'percona-valkey-admin-server', ver: "${params.VALKEY_ADMIN_VERSION}-${params.ADMIN_RELEASE}"] }
                    checkNotPublished(pkgs)
                }
            }
        }
        // Packages are built in dependency order. Modules install percona-valkey-dev
        // from the public repo, and percona-valkey-bundle depends on server + json +
        // bloom + search + ldap, so each step waits until the previous one is served
        // by repo.percona.com before starting.
        stage('Build server package') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            when {
                beforeAgent true
                expression { params.PKG_SERVER }
            }
            steps {
                script {
                    long since = epochNow()
                    trigger('hetzner-valkey-RELEASE', [
                        booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                        string(name: 'CLOUD',            value: params.CLOUD),
                        string(name: 'GIT_BRANCH',       value: params.GIT_BRANCH),
                        string(name: 'PACKAGING_BRANCH', value: params.PACKAGING_BRANCH),
                        string(name: 'VALKEY_VERSION',   value: params.VALKEY_VERSION),
                        string(name: 'VALKEY_RELEASE',   value: params.SERVER_RELEASE),
                        string(name: 'VALKEY_REPO',    value: params.VALKEY_REPO),
                        string(name: 'COMPONENT',      value: params.CHANNEL),
                    ], { r -> recordBuild('server', r) }).call()
                    confirmPublished(waitForPublished(['percona-valkey-server': "${params.VALKEY_VERSION}-${params.SERVER_RELEASE}"], since))
                }
            }
        }
        stage('Build module packages') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            when {
                beforeAgent true
                expression { params.PKG_JSON || params.PKG_BLOOM || params.PKG_SEARCH || params.PKG_LDAP || params.PKG_AUDIT || params.PKG_ADMIN }
            }
            steps {
                script {
                    String cloud = params.CLOUD
                    String ch    = params.CHANNEL
                    String pkgBr = params.PACKAGING_BRANCH
                    Map jobs = [:]
                    // Published packages to wait for, so the bundle and the Docker images
                    // install what this run built.
                    Map published = [:]
                    if (params.PKG_JSON) {
                        jobs['json'] = trigger('hetzner-valkey-json-RELEASE', [
                            booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                            string(name: 'CLOUD',               value: cloud),
                            string(name: 'PACKAGING_BRANCH',    value: pkgBr),
                            string(name: 'VALKEY_JSON_VERSION', value: params.VALKEY_JSON_VERSION),
                            string(name: 'VALKEY_JSON_RELEASE', value: params.JSON_RELEASE),
                            string(name: 'VALKEY_JSON_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',           value: ch),
                            string(name: 'VALKEY_REPO_CHANNEL', value: ch),
                        ], { r -> recordBuild('json', r) })
                        published['percona-valkey-json'] = "${params.VALKEY_JSON_VERSION}-${params.JSON_RELEASE}"
                    }
                    if (params.PKG_BLOOM) {
                        jobs['bloom'] = trigger('hetzner-valkey-bloom-RELEASE', [
                            booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                            string(name: 'CLOUD',                value: cloud),
                            string(name: 'PACKAGING_BRANCH',     value: pkgBr),
                            string(name: 'VALKEY_BLOOM_VERSION', value: params.VALKEY_BLOOM_VERSION),
                            string(name: 'VALKEY_BLOOM_RELEASE', value: params.BLOOM_RELEASE),
                            string(name: 'VALKEY_BLOOM_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',            value: ch),
                        ], { r -> recordBuild('bloom', r) })
                        published['percona-valkey-bloom'] = "${params.VALKEY_BLOOM_VERSION}-${params.BLOOM_RELEASE}"
                    }
                    if (params.PKG_SEARCH) {
                        jobs['search'] = trigger('hetzner-valkey-search-RELEASE', [
                            booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                            string(name: 'CLOUD',                 value: cloud),
                            string(name: 'PACKAGING_BRANCH',      value: pkgBr),
                            string(name: 'VALKEY_SEARCH_VERSION', value: params.VALKEY_SEARCH_VERSION),
                            string(name: 'VALKEY_SEARCH_RELEASE', value: params.SEARCH_RELEASE),
                            string(name: 'VALKEY_SEARCH_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',             value: ch),
                        ], { r -> recordBuild('search', r) })
                        published['percona-valkey-search'] = "${params.VALKEY_SEARCH_VERSION}-${params.SEARCH_RELEASE}"
                    }
                    if (params.PKG_LDAP) {
                        jobs['ldap'] = trigger('hetzner-valkey-ldap-RELEASE', [
                            booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                            string(name: 'CLOUD',               value: cloud),
                            string(name: 'VALKEY_LDAP_VERSION', value: params.VALKEY_LDAP_VERSION),
                            string(name: 'VALKEY_LDAP_RELEASE', value: params.LDAP_RELEASE),
                            string(name: 'VALKEY_LDAP_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',           value: ch),
                        ], { r -> recordBuild('ldap', r) })
                        published['percona-valkey-ldap'] = "${params.VALKEY_LDAP_VERSION}-${params.LDAP_RELEASE}"
                    }
                    if (params.PKG_AUDIT) {
                        jobs['audit'] = trigger('hetzner-valkey-audit-RELEASE', [
                            booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                            string(name: 'CLOUD',                value: cloud),
                            // Pass the source explicitly: a job left with older defaults
                            // (or a rebuild) would otherwise build a different repo. The
                            // fallback covers the first run, before AUDIT_GIT_BRANCH exists.
                            string(name: 'GIT_REPO',             value: 'https://github.com/percona/valkey-audit.git'),
                            string(name: 'GIT_BRANCH',           value: params.AUDIT_GIT_BRANCH ?: 'b5ebc556e5af076fe067bf53771dea236db1d2fb'),
                            string(name: 'PACKAGING_BRANCH',     value: pkgBr),
                            string(name: 'VALKEY_AUDIT_VERSION', value: params.VALKEY_AUDIT_VERSION),
                            string(name: 'VALKEY_AUDIT_RELEASE', value: params.AUDIT_RELEASE),
                            string(name: 'VALKEY_AUDIT_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',            value: ch),
                            string(name: 'VALKEY_REPO_CHANNEL',  value: ch),
                        ], { r -> recordBuild('audit', r) })
                        published['percona-valkey-audit'] = "${params.VALKEY_AUDIT_VERSION}-${params.AUDIT_RELEASE}"
                    }
                    if (params.PKG_ADMIN) {
                        jobs['admin'] = trigger('hetzner-valkey-admin-RELEASE', [
                            booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                            string(name: 'CLOUD',                value: cloud),
                            string(name: 'PACKAGING_BRANCH',     value: pkgBr),
                            // Upstream valkey-admin tags carry a leading "v", so the ref is
                            // derived from the version rather than being a separate knob.
                            string(name: 'GIT_BRANCH',           value: "v${params.VALKEY_ADMIN_VERSION}"),
                            string(name: 'VALKEY_ADMIN_VERSION', value: params.VALKEY_ADMIN_VERSION),
                            string(name: 'VALKEY_ADMIN_RELEASE', value: params.ADMIN_RELEASE),
                            string(name: 'VALKEY_ADMIN_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',            value: ch),
                        ], { r -> recordBuild('admin', r) })
                        published['percona-valkey-admin-server'] = "${params.VALKEY_ADMIN_VERSION}-${params.ADMIN_RELEASE}"
                    }
                    echo "Building module packages: ${jobs.keySet().join(', ')}"
                    long since = epochNow()
                    parallel(jobs)
                    if (published) {
                        confirmPublished(waitForPublished(published, since))
                    }
                }
            }
        }
        stage('Build bundle package') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            when {
                beforeAgent true
                expression { params.PKG_BUNDLE }
            }
            steps {
                script {
                    long since = epochNow()
                    trigger('hetzner-valkey-bundle-RELEASE', [
                        booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                        string(name: 'CLOUD',                 value: params.CLOUD),
                        string(name: 'PACKAGING_BRANCH',      value: params.PACKAGING_BRANCH),
                        string(name: 'VALKEY_BUNDLE_VERSION', value: params.VALKEY_BUNDLE_VERSION),
                        string(name: 'VALKEY_BUNDLE_RELEASE', value: params.BUNDLE_RELEASE),
                        string(name: 'VALKEY_BUNDLE_REPO',    value: params.VALKEY_REPO),
                        string(name: 'COMPONENT',             value: params.CHANNEL),
                    ], { r -> recordBuild('bundle', r) }).call()
                    confirmPublished(waitForPublished(['percona-valkey-bundle': "${params.VALKEY_BUNDLE_VERSION}-${params.BUNDLE_RELEASE}"], since))
                }
            }
        }
        stage('Build Docker images') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            when {
                beforeAgent true
                expression { params.IMG_VALKEY || params.IMG_BUNDLE || params.IMG_MODULE || params.IMG_ADMIN }
            }
            steps {
                script {
                    String cloud = params.CLOUD
                    String ch    = params.CHANNEL
                    String pkgBr = params.PACKAGING_BRANCH
                    String ns    = params.IMAGE_NAMESPACE
                    Map jobs = [:]
                    if (params.IMG_VALKEY) {
                        jobs['valkey'] = trigger('hetzner-valkey-DOCKER', [
                            string(name: 'CLOUD',            value: cloud),
                            string(name: 'PACKAGING_BRANCH', value: pkgBr),
                            string(name: 'VALKEY_VERSION',   value: params.VALKEY_VERSION),
                            string(name: 'REPO_CHANNEL',     value: ch),
                            string(name: 'IMAGE_NAME',       value: "${ns}/valkey"),
                            booleanParam(name: 'RUN_TRIVY',  value: params.RUN_TRIVY),
                        ])
                    }
                    if (params.IMG_BUNDLE) {
                        jobs['bundle'] = trigger('hetzner-valkey-bundle-DOCKER', [
                            string(name: 'CLOUD',            value: cloud),
                            string(name: 'PACKAGING_BRANCH', value: pkgBr),
                            string(name: 'VALKEY_VERSION',   value: params.VALKEY_VERSION),
                            string(name: 'REPO_CHANNEL',     value: ch),
                            string(name: 'IMAGE_NAME',       value: "${ns}/valkey-bundle"),
                            booleanParam(name: 'RUN_TRIVY',  value: params.RUN_TRIVY),
                        ])
                    }
                    if (params.IMG_MODULE) {
                        jobs['module'] = trigger('hetzner-valkey-module-DOCKER', [
                            string(name: 'CLOUD',            value: cloud),
                            string(name: 'PACKAGING_BRANCH', value: pkgBr),
                            string(name: 'REPO_CHANNEL',     value: ch),
                            string(name: 'IMAGE_PREFIX',     value: "${ns}/valkey-mod"),
                            booleanParam(name: 'RUN_TRIVY',  value: params.RUN_TRIVY),
                        ])
                    }
                    if (params.IMG_ADMIN) {
                        jobs['admin'] = trigger('hetzner-valkey-admin-DOCKER', [
                            string(name: 'CLOUD',                value: cloud),
                            string(name: 'PACKAGING_BRANCH',     value: pkgBr),
                            string(name: 'VALKEY_ADMIN_VERSION', value: params.VALKEY_ADMIN_VERSION),
                            string(name: 'REPO_CHANNEL',         value: ch),
                            string(name: 'IMAGE_NAME',           value: "${ns}/valkey-admin"),
                            booleanParam(name: 'RUN_TRIVY',      value: params.RUN_TRIVY),
                        ])
                    }
                    echo "Building Docker images (namespace ${ns}): ${jobs.keySet().join(', ')}"
                    parallel(jobs)
                }
            }
        }
        stage('Run tests') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            when {
                beforeAgent true
                expression { params.RUN_TESTS }
            }
            steps {
                script {
                    trigger('hetzner-valkey-TESTING', [
                        string(name: 'CLOUD',            value: params.CLOUD),
                        string(name: 'VALKEY_VERSION',   value: params.VALKEY_VERSION),
                        string(name: 'PACKAGING_BRANCH', value: params.PACKAGING_BRANCH),
                        string(name: 'COMPONENT',        value: params.CHANNEL),
                        string(name: 'VALKEY_REPO',      value: params.VALKEY_REPO),
                        string(name: 'PLATFORMS',        value: params.TEST_PLATFORMS),
                    ]).call()
                }
            }
        }
        // No agent: the run can wait for approval as long as needed without
        // holding an executor. Only one run of this job is active at a time
        // (disableConcurrentBuilds), so a run waiting here holds the job until
        // you choose Release or Do not release.
        stage('Approve release') {
            agent none
            when { expression { params.RELEASE } }
            steps {
                script {
                    List keys = releaseKeys()
                    List lines = []
                    for (String k : keys) {
                        Map e = MANIFEST[k]
                        lines << "  ${k}: ${e.deb} ${e.version}  PATH_TO_BUILD=${releasePath(e.path)}".toString()
                    }
                    String images = params.RELEASE_IMAGES ? "then build the release images in ${params.RELEASE_IMAGE_NAMESPACE}/ from the release channel" : 'no release images'
                    echo "Release to ${params.VALKEY_REPO}/release, in this order:\n${lines.join('\n')}\n${images}"
                    // "Do not release" ends the run normally: the release stages are
                    // skipped and post archives the manifest. Abort does the same but
                    // marks the run ABORTED.
                    RELEASE_DECISION = input(
                        message: "Release ${keys.join(', ')} to ${params.VALKEY_REPO}/release? The list is in the log above.",
                        ok: 'Continue',
                        parameters: [choice(name: 'DECISION', choices: ['Release', 'Do not release'],
                                            description: 'Release: push the listed builds to the release channel. Do not release: finish the run and keep only the manifest.')])
                    if (RELEASE_DECISION == 'Release') {
                        echo 'Release approved'
                    } else {
                        echo 'Release not approved: the release stages are skipped, the manifest is archived with this run'
                    }
                }
            }
        }
        stage('Release packages') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            when {
                beforeAgent true
                expression { releaseApproved() }
            }
            steps {
                script {
                    List keys = releaseKeys()
                    long since = epochNow()
                    Map packages = [:]
                    Map exact = [:]
                    // One run of hetzner-valkey-RELEASE-from-repository for all of
                    // them: it pushes the builds in this order (dependencies first,
                    // the bundle last), then syncs the repo to production once.
                    List paths = []
                    for (String k : keys) {
                        Map e = MANIFEST[k]
                        paths << releasePath(e.path)
                        packages[e.deb] = e.version
                        exact[e.deb] = e.rpm_build_time
                    }
                    trigger('hetzner-valkey-RELEASE-from-repository', [
                        string(name: 'CLOUD',      value: params.CLOUD),
                        text(name: 'PATHS',        value: paths.join('\n')),
                        string(name: 'REPOSITORY', value: params.VALKEY_REPO.toUpperCase()),
                        string(name: 'COMPONENT',  value: 'RELEASE'),
                    ]).call()
                    RELEASED.addAll(keys)
                    // Wait until the release channel serves exactly the builds in
                    // the manifest before building images from it.
                    waitForPublished(packages, since, 'release', exact)
                }
            }
        }
        stage('Release Docker images') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon' }
            when {
                beforeAgent true
                expression { releaseApproved() && params.RELEASE_IMAGES }
            }
            steps {
                script {
                    String cloud = params.CLOUD
                    String pkgBr = params.PACKAGING_BRANCH
                    String ns    = params.RELEASE_IMAGE_NAMESPACE
                    // Versions come from the manifest, not the parameters, so a
                    // release-only run with stale defaults still gets them right.
                    String valkeyVer = upstreamVersion('server', params.VALKEY_VERSION)
                    String adminVer  = upstreamVersion('admin', params.VALKEY_ADMIN_VERSION)
                    // The server image first: the module job checks each module
                    // loads into it.
                    trigger('hetzner-valkey-DOCKER', [
                        string(name: 'CLOUD',            value: cloud),
                        string(name: 'PACKAGING_BRANCH', value: pkgBr),
                        string(name: 'VALKEY_VERSION',   value: valkeyVer),
                        string(name: 'REPO_CHANNEL',     value: 'release'),
                        string(name: 'IMAGE_NAME',       value: "${ns}/valkey"),
                        booleanParam(name: 'RUN_TRIVY',  value: params.RUN_TRIVY),
                    ]).call()
                    RELEASE_IMAGES_PUSHED << "${ns}/valkey:${valkeyVer}".toString()
                    Map jobs = [:]
                    jobs['bundle'] = trigger('hetzner-valkey-bundle-DOCKER', [
                        string(name: 'CLOUD',            value: cloud),
                        string(name: 'PACKAGING_BRANCH', value: pkgBr),
                        string(name: 'VALKEY_VERSION',   value: valkeyVer),
                        string(name: 'REPO_CHANNEL',     value: 'release'),
                        string(name: 'IMAGE_NAME',       value: "${ns}/valkey-bundle"),
                        booleanParam(name: 'RUN_TRIVY',  value: params.RUN_TRIVY),
                    ], { r -> RELEASE_IMAGES_PUSHED << "${ns}/valkey-bundle:${valkeyVer}".toString() })
                    jobs['module'] = trigger('hetzner-valkey-module-DOCKER', [
                        string(name: 'CLOUD',            value: cloud),
                        string(name: 'PACKAGING_BRANCH', value: pkgBr),
                        string(name: 'REPO_CHANNEL',     value: 'release'),
                        string(name: 'IMAGE_PREFIX',     value: "${ns}/valkey-mod"),
                        string(name: 'SERVER_IMAGE',     value: "${ns}/valkey:${valkeyVer}"),
                        booleanParam(name: 'RUN_TRIVY',  value: params.RUN_TRIVY),
                    ], { r -> RELEASE_IMAGES_PUSHED << "${ns}/valkey-mod-*".toString() })
                    jobs['admin'] = trigger('hetzner-valkey-admin-DOCKER', [
                        string(name: 'CLOUD',                value: cloud),
                        string(name: 'PACKAGING_BRANCH',     value: pkgBr),
                        string(name: 'VALKEY_ADMIN_VERSION', value: adminVer),
                        string(name: 'REPO_CHANNEL',         value: 'release'),
                        string(name: 'IMAGE_NAME',           value: "${ns}/valkey-admin"),
                        booleanParam(name: 'RUN_TRIVY',      value: params.RUN_TRIVY),
                    ], { r -> RELEASE_IMAGES_PUSHED << "${ns}/valkey-admin:${adminVer}".toString() })
                    parallel(jobs)
                }
            }
        }
    }
    post {
        // Archive the manifest even when a stage failed: it records every
        // component the repo was confirmed to serve.
        always {
            script {
                if (MANIFEST) {
                    node(agentLabel()) {
                        writeManifest()
                    }
                }
            }
        }
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: full Valkey pipeline succeeded for ${params.VALKEY_VERSION} (${params.CHANNEL}) - [${BUILD_URL}]")
        }
        unstable {
            slackNotify("#releases-ci", "#FFFF00", "[${JOB_NAME}]: full Valkey pipeline finished UNSTABLE for ${params.VALKEY_VERSION} (${params.CHANNEL}), see the downstream jobs - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: full Valkey pipeline failed for ${params.VALKEY_VERSION} (${params.CHANNEL}) - [${BUILD_URL}]")
        }
    }
}
