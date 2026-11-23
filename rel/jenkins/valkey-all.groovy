library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// trigger — return a closure (for parallel{}) that runs a downstream job and
// waits for it. A failed or aborted job fails the pipeline. An UNSTABLE job only
// marks it unstable and the pipeline goes on: the shared artifact helpers mark a
// job unstable when Hetzner S3 falls back to AWS, and with propagate: true that
// stopped every later stage although the packages were built and published.
def trigger(String jobName, List jobParams) {
    return {
        def run = build job: jobName, parameters: jobParams, wait: true, propagate: false
        String result = run.result
        if (result == 'UNSTABLE') {
            unstable("${jobName} #${run.number} finished UNSTABLE, continuing: ${run.absoluteUrl}")
        } else if (result != 'SUCCESS') {
            error("${jobName} #${run.number} finished ${result}: ${run.absoluteUrl}")
        }
    }
}

// waitForPublished — poll the public repo until every package in
// [name: "<version>-<release>"] is listed both in the apt noble amd64 index and
// in the EL9 x86_64 yum metadata (primary.xml), so the next stage builds against
// packages repo.percona.com actually serves. The yum check matters: the RPM files
// land before createrepo rewrites the metadata, and the apt index can be live
// minutes earlier. sync2ProdAutoBuild runs createrepo over the EL versions in
// `ls` order (10, 2023, 8, 9) and the arches as aarch64 then x86_64, so EL9
// x86_64 is the last directory it updates.
def waitForPublished(Map packages) {
    String dist = 'noble'
    int waitMinutes = 30
    String index = "https://repo.percona.com/${params.VALKEY_REPO}/apt/dists/${dist}/${params.CHANNEL}/binary-amd64/Packages"
    String yumRepo = "https://repo.percona.com/${params.VALKEY_REPO}/yum/${params.CHANNEL}/9/RPMS/x86_64"
    List expected = []
    List expectedRpm = []
    for (def entry : packages.entrySet()) {
        expected << "/${entry.key}_${entry.value}.${dist}_"
        // The server's main RPM is percona-valkey; its deb is percona-valkey-server.
        String rpmName = entry.key == 'percona-valkey-server' ? 'percona-valkey' : entry.key
        expectedRpm << "\"${rpmName}-${entry.value}.el9.x86_64.rpm\""
    }
    echo "Waiting up to ${waitMinutes} min for ${packages.keySet().join(', ')} in ${index} and ${yumRepo}"
    int attempts = waitMinutes * 2
    for (int i = 1; i <= attempts; i++) {
        String listed = sh(returnStdout: true, script: "curl -fsS --max-time 60 '${index}' | grep '^Filename:' || true")
        String rpms = sh(returnStdout: true, script: """
            set +x
            primary=\$(curl -fsS --max-time 60 '${yumRepo}/repodata/repomd.xml' | grep -oE 'repodata/[^"]*primary[.]xml[.]gz' | head -1)
            [ -n "\$primary" ] && curl -fsS --max-time 60 "${yumRepo}/\$primary" | gzip -dc | grep -oE 'location href="[^"]+"' || true
        """)
        List missing = []
        for (String file : expected) {
            if (!listed.contains(file)) {
                missing << file
            }
        }
        for (String file : expectedRpm) {
            if (!rpms.contains(file)) {
                missing << file
            }
        }
        if (!missing) {
            echo "Published: ${packages.keySet().join(', ')}"
            return
        }
        echo "Attempt ${i}/${attempts}: not served yet: ${missing.join(' ')}"
        sleep(time: 30, unit: 'SECONDS')
    }
    error("Packages were not published to ${index} within ${waitMinutes} minutes")
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
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon'
    }
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
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
        timestamps()
    }
    stages {
        // Stop before building anything if a requested version-release is already
        // in the repo: every rebuild needs a new release number.
        stage('Pre-flight: release numbers') {
            when { expression { params.PKG_SERVER || params.PKG_JSON || params.PKG_BLOOM || params.PKG_SEARCH || params.PKG_LDAP || params.PKG_AUDIT || params.PKG_BUNDLE || params.PKG_ADMIN } }
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
            when { expression { params.PKG_SERVER } }
            steps {
                script {
                    trigger('hetzner-valkey-RELEASE', [
                        booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                        string(name: 'CLOUD',            value: params.CLOUD),
                        string(name: 'GIT_BRANCH',       value: params.GIT_BRANCH),
                        string(name: 'PACKAGING_BRANCH', value: params.PACKAGING_BRANCH),
                        string(name: 'VALKEY_VERSION',   value: params.VALKEY_VERSION),
                        string(name: 'VALKEY_RELEASE',   value: params.SERVER_RELEASE),
                        string(name: 'VALKEY_REPO',    value: params.VALKEY_REPO),
                        string(name: 'COMPONENT',      value: params.CHANNEL),
                    ]).call()
                    waitForPublished(['percona-valkey-server': "${params.VALKEY_VERSION}-${params.SERVER_RELEASE}"])
                }
            }
        }
        stage('Build module packages') {
            when { expression { params.PKG_JSON || params.PKG_BLOOM || params.PKG_SEARCH || params.PKG_LDAP || params.PKG_AUDIT || params.PKG_ADMIN } }
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
                        ])
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
                        ])
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
                        ])
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
                        ])
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
                        ])
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
                        ])
                        published['percona-valkey-admin-server'] = "${params.VALKEY_ADMIN_VERSION}-${params.ADMIN_RELEASE}"
                    }
                    echo "Building module packages: ${jobs.keySet().join(', ')}"
                    parallel(jobs)
                    if (published) {
                        waitForPublished(published)
                    }
                }
            }
        }
        stage('Build bundle package') {
            when { expression { params.PKG_BUNDLE } }
            steps {
                script {
                    trigger('hetzner-valkey-bundle-RELEASE', [
                        booleanParam(name: 'ALLOW_REPUBLISH', value: params.ALLOW_REPUBLISH ? true : false),
                        string(name: 'CLOUD',                 value: params.CLOUD),
                        string(name: 'PACKAGING_BRANCH',      value: params.PACKAGING_BRANCH),
                        string(name: 'VALKEY_BUNDLE_VERSION', value: params.VALKEY_BUNDLE_VERSION),
                        string(name: 'VALKEY_BUNDLE_RELEASE', value: params.BUNDLE_RELEASE),
                        string(name: 'VALKEY_BUNDLE_REPO',    value: params.VALKEY_REPO),
                        string(name: 'COMPONENT',             value: params.CHANNEL),
                    ]).call()
                    waitForPublished(['percona-valkey-bundle': "${params.VALKEY_BUNDLE_VERSION}-${params.BUNDLE_RELEASE}"])
                }
            }
        }
        stage('Build Docker images') {
            when { expression { params.IMG_VALKEY || params.IMG_BUNDLE || params.IMG_MODULE || params.IMG_ADMIN } }
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
            when { expression { params.RUN_TESTS } }
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
    }
    post {
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
