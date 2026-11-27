library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Each entry must match a scenario folder in package-testing molecule/pbs/molecule/.
// percona-binlog-server packages exist for el9 and Debian 13 (trixie), not for Debian 12.
def pbsTestOSes() {
  return ['debian-13', 'oracle-9']
}

// Regions used by the scenarios above (debian-13: us-west-1, oracle-9: us-west-2).
def pbsTestRegions() {
  return ['us-west-1', 'us-west-2']
}

// Safety net after molecule destroy: terminate anything still tagged with this build.
def deleteBuildInstances() {
    script {
        def awsCredentials = [
                sshUserPrivateKey(
                    credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
                    keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
                    passphraseVariable: '',
                    usernameVariable: ''
                ),
                aws(
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                )
        ]

        withCredentials(awsCredentials) {
            def jobName = env.JOB_NAME.trim()
            pbsTestRegions().each { region ->
                def instanceIds = sh(
                    script: """
                    aws ec2 describe-instances --region ${region} \\
                    --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${env.BUILD_NUMBER}" "Name=instance-state-name,Values=pending,running,stopping,stopped" \\
                    --query "Reservations[].Instances[].InstanceId" \\
                    --output text || true
                    """,
                    returnStdout: true
                ).trim()

                if (instanceIds) {
                    echo "Terminating leftover instances in ${region}: ${instanceIds}"
                    sh "aws ec2 terminate-instances --region ${region} --instance-ids ${instanceIds}"
                } else {
                    echo "No leftover instances in ${region}"
                }
            }
        }
    }
}

def loadEnvFile(envFilePath) {
    def envMap = []
    def envFileContent = readFile(file: envFilePath).trim().split('\n')
    envFileContent.each { line ->
        if (line && !line.startsWith('#')) {
            def parts = line.split('=')
            if (parts.length == 2) {
                envMap << "${parts[0].trim()}=${parts[1].trim()}"
            }
        }
    }
    return envMap
}

// Value of KEY="..." from package-testing's VERSIONS file (in the checked-out workspace),
// the release team's list of current versions. Empty string when the key is missing.
def versionFromVersionsFile(String key) {
    return sh(
        script: "grep -E '^${key}=' VERSIONS | head -1 | cut -d= -f2- | tr -d '\"'",
        returnStdout: true
    ).trim()
}

// "PS80", "PS84", "PS97": a series alias, resolved to the newest version whose tarball is
// available (see resolvePsTarballs). Anything else is a specific version.
def isSeriesAlias(String value) {
    return value ==~ /PS\d\d/
}

// Versions of the newest packages of a series in the percona-release repository
// (repo.percona.com/<repo>/yum/<component>/9/), newest first. A package being there doesn't
// mean the tarball is published, so these are only the candidates to check.
// regex: the package file name up to ".el9", with one group: the version to return.
def repoPackageVersions(String repo, List components, String regex, int count) {
    def listings = components.collect { "https://repo.percona.com/${repo}/yum/${it}/9/RPMS/x86_64/" }.join(' ')
    def out = sh(returnStdout: true, script: """
        for url in ${listings}; do curl -sf --max-time 60 "\$url" || true; done \\
          | grep -oE '${regex}' | sed -E 's/^${regex}\$/\\1/' | sort -uVr | head -${count}
    """).trim()
    return out ? out.split('\n').collect { it.trim() } : []
}

def sortVersionsDesc(List versions) {
    if (!versions) {
        return []
    }
    return sh(returnStdout: true, script: "printf '%s\\n' ${versions.collect { "'${it}'" }.join(' ')} | sort -uVr").trim().split('\n') as List
}

// Newest-first candidate tarball versions for a series alias: <alias>_VER from
// package-testing VERSIONS (it can be ahead of what is built) plus the Percona Server
// packages in the release (and, for testing, the testing) repository, e.g. 8.4.11-11.
// binlog_server needs Percona Server 8.0 or newer.
def psCandidateVersions(String alias, String source) {
    def series = alias.substring('PS'.length())
    def repo = ['80': 'ps-80', '84': 'ps-84-lts', '97': 'ps-97-lts'][series]
    if (!repo) {
        error "${alias}: not a supported Percona Server series for binlog_server (PS80, PS84, PS97)"
    }
    def dotted = "${series[0]}\\.${series.substring(1)}"
    def components = source == 'testing' ? ['testing', 'release'] : ['release']
    def candidates = []
    def fromVersions = versionFromVersionsFile("${alias}_VER")
    if (fromVersions) {
        candidates << fromVersions
    }
    candidates += repoPackageVersions(repo, components, "percona-server-server-(${dotted}\\.[0-9]+-[0-9]+)\\.[0-9]+\\.el9", 5)
    return sortVersionsDesc(candidates)
}

// Percona Server binary tarball URL.
// release: the published tarball (same layout as molecule/pbs/tasks/main.yml)
// testing: the pre-release tarball the build pipeline uploads to downloads/TESTING
//          (vars/uploadTarballToDownloadsTesting.groovy: TESTING/ps-<version>/)
def psTarballUrl(String version, String glibc, String source) {
    def file = "Percona-Server-${version}-Linux.x86_64.glibc${glibc}.tar.gz"
    if (source == 'testing') {
        return "https://downloads.percona.com/downloads/TESTING/ps-${version}/${file}"
    }
    def upstream = version.tokenize('-')[0]
    def series = upstream.tokenize('.')[0..1].join('.')
    return "https://downloads.percona.com/downloads/Percona-Server-${series}/Percona-Server-${version}/binary/tarball/${file}"
}

def urlExists(String url) {
    return sh(script: "curl -sfI -o /dev/null --max-time 60 '${url}'", returnStatus: true) == 0
}

// Picks the tarball of every host for a PS_VERSION-style value:
//   specific version: that version; it must exist.
//   series alias:     the newest candidate (psCandidateVersions) available for every host.
// source testing: a series alias looks in downloads/TESTING first and takes the released
// tarball when there is none, so it gets the newest of the two; a specific version must be
// in downloads/TESTING. An explicit URL in
// host.override is used as given. Returns [version: ..., urls: [<family>: <url>]].
def resolvePsTarballs(String value, Map hosts, String source) {
    def alias = isSeriesAlias(value)
    def candidates = alias ? psCandidateVersions(value, source) : [value]
    // A series alias on testing also takes released tarballs (the newest of both); a specific
    // version on testing must be a TESTING tarball.
    def sources = source != 'testing' ? ['release'] : (alias ? ['testing', 'release'] : ['testing'])
    echo "${value} (${source}): checking ${candidates}"
    def skipped = []
    for (version in candidates) {
        def urls = [:]
        def missing = []
        hosts.each { os, cfg ->
            if (cfg.override) {
                urls[cfg.family] = cfg.override
                return
            }
            def found = ''
            for (src in sources) {
                def url = psTarballUrl(version, cfg.glibc, src)
                if (urlExists(url)) {
                    found = url
                    break
                }
            }
            if (found) {
                urls[cfg.family] = found
            } else {
                missing << os
            }
        }
        if (!missing) {
            if (skipped) {
                echo "${value}: newer ${skipped} not available as tarball(s), using ${version}"
            }
            urls.each { family, url -> echo "${family}: ${url}" }
            return [version: version, urls: urls]
        }
        echo "${version}: no tarball for ${missing}"
        skipped << version
    }
    error "No Percona Server tarball available for ${value} (${source}) on ${hosts.keySet()}; checked ${candidates}. " +
          (alias ? 'Set a specific PS_VERSION or PS_TARBALL_URL_*.' : 'Check the version, or set PS_TARBALL_URL_*.')
}


pipeline {
  agent {
    label 'deb12-x64-min'
  }
  environment {
    // String parameters are trimmed: a stray space breaks git refs, URLs and versions.
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/pbs/";
    PS_VERSION = "${params.PS_VERSION?.trim() ?: ''}";
    PS_RHEL_GLIBC_VERSION = "${params.PS_RHEL_GLIBC_VERSION?.trim() ?: ''}";
    PS_DEBIAN_GLIBC_VERSION = "${params.PS_DEBIAN_GLIBC_VERSION?.trim() ?: ''}";
    PS_TARBALL_URL_RHEL = "${params.PS_TARBALL_URL_RHEL?.trim() ?: ''}";
    PS_TARBALL_URL_DEB = "${params.PS_TARBALL_URL_DEB?.trim() ?: ''}";
    PS_TARBALL_SOURCE = "${params.PS_TARBALL_SOURCE}";
    PBS_VERSION = "${params.PBS_VERSION?.trim() ?: ''}";
    PBS_REPO_CHANNEL = "${params.PBS_REPO_CHANNEL}";
    PBS_BINARY_URL = "${params.PBS_BINARY_URL?.trim() ?: ''}";
    PBS_USE_DEBUG_BINARY = "${params.PBS_USE_DEBUG_BINARY}";
    PBS_QA_REPO = "${params.PBS_QA_REPO?.trim() ?: ''}";
    PBS_QA_BRANCH = "${params.PBS_QA_BRANCH?.trim() ?: ''}";
    PBS_TESTS = "${params.PBS_TESTS?.trim() ?: ''}";
    PBS_PARALLEL = "${params.PBS_PARALLEL?.trim() ?: ''}";
    PBS_ENCRYPTION = "${params.PBS_ENCRYPTION}";
    PBS_VERBOSE = "${params.PBS_VERBOSE}";
    PBS_CONFIG_OVERRIDES = "${params.PBS_CONFIG_OVERRIDES?.trim() ?: ''}";
    PBS_TIMEOUT_HOURS = "${params.PBS_TIMEOUT_HOURS?.trim() ?: ''}";
    TESTING_GIT_ACCOUNT = "${params.TESTING_GIT_ACCOUNT?.trim() ?: ''}";
    TESTING_BRANCH = "${params.TESTING_BRANCH?.trim() ?: ''}";
    INSTANCE_TYPE = "${params.INSTANCE_TYPE?.trim() ?: ''}";
  }
  parameters {
    string(
      name: 'PS_VERSION',
      defaultValue: 'PS84',
      description: 'Percona Server for the binlog_server replication source. PS84 | PS97 | PS80 = newest available tarball of that series (from PS_TARBALL_SOURCE), or a specific version, used exactly: 8.4.10-10, 9.7.2-2'
    )
    string(
      name: 'PS_RHEL_GLIBC_VERSION',
      defaultValue: '2.34',
      description: 'glibc suffix of the PS tarball used on the RedHat-family host (oracle-9)'
    )
    string(
      name: 'PS_DEBIAN_GLIBC_VERSION',
      defaultValue: '2.35',
      description: 'glibc suffix of the PS tarball used on the Debian-family host (debian-13)'
    )
    string(
      name: 'PS_TARBALL_URL_RHEL',
      defaultValue: '',
      description: 'Optional: full URL of a PS tarball for the RedHat host (e.g. an unreleased build). Overrides PS_VERSION there.'
    )
    string(
      name: 'PS_TARBALL_URL_DEB',
      defaultValue: '',
      description: 'Optional: full URL of a PS tarball for the Debian host. Overrides PS_VERSION there.'
    )
    choice(
      name: 'PS_TARBALL_SOURCE',
      choices: ['release', 'testing'],
      description: 'release: published tarballs (downloads.percona.com). testing: pre-release tarballs (downloads.percona.com/downloads/TESTING/ps-<version>/). A series alias also takes published tarballs, so it gets the newest of both; a specific version must be a TESTING tarball.'
    )
    string(
      name: 'PBS_VERSION',
      defaultValue: '',
      description: 'percona-binlog-server package version to install (e.g. 0.4.1-1). Empty: latest in PBS_REPO_CHANNEL (the installed version is printed in the build log).'
    )
    choice(
      name: 'PBS_REPO_CHANNEL',
      choices: ['experimental', 'testing', 'release'],
      description: 'Channel of the Percona "tools" repository to install percona-binlog-server from (it is currently published in experimental only)'
    )
    string(
      name: 'PBS_BINARY_URL',
      defaultValue: '',
      description: 'Optional: URL of a binlog_server binary to test instead of the packaged one (e.g. an unreleased build). The package is still installed for its runtime libraries.'
    )
    booleanParam(
      name: 'PBS_USE_DEBUG_BINARY',
      defaultValue: false,
      description: 'Test the packaged /usr/bin/binlog_server-debug instead of /usr/bin/binlog_server'
    )
    string(
      name: 'PBS_TESTS',
      defaultValue: 'all',
      description: 'Comma-separated test_scripts/pbs/tests names (e.g. gtid_rewrite_test,pull_purge_resume_test), or "all" for every *_test.py'
    )
    string(
      name: 'PBS_PARALLEL',
      defaultValue: '2',
      description: 'pbs_test_runner.py --parallel (0 = sequential, max 4). Each test runs its own mysqld, so size INSTANCE_TYPE accordingly.'
    )
    booleanParam(
      name: 'PBS_ENCRYPTION',
      defaultValue: true,
      description: 'Pass --encryption to pbs_test_runner.py (storage encryption on for every test)'
    )
    booleanParam(
      name: 'PBS_VERBOSE',
      defaultValue: true,
      description: 'Pass --verbose to pbs_test_runner.py'
    )
    string(
      name: 'PBS_CONFIG_OVERRIDES',
      defaultValue: '',
      description: 'Semicolon-separated test_scripts/pbs/config.json overrides as key=value (nested: s3.bucket=...), e.g. sysbench_table_size=50000;sysbench_run_time=120;fetch_timeout=600. Do not put secrets here.'
    )
    string(
      name: 'PBS_QA_REPO',
      defaultValue: 'https://github.com/Percona-QA/server-qa.git',
      description: 'server-qa git repository holding the tests in test_scripts/pbs/. Use your fork until the tests are merged.'
    )
    string(
      name: 'PBS_QA_BRANCH',
      defaultValue: 'main',
      description: 'server-qa branch'
    )
    string(
      name: 'TESTING_GIT_ACCOUNT',
      defaultValue: 'Percona-QA',
      description: 'GitHub account of the package-testing repo that holds molecule/pbs (use your fork until it is merged)'
    )
    string(
      name: 'TESTING_BRANCH',
      defaultValue: 'master',
      description: 'Branch of the package-testing repository'
    )
    choice(
      name: 'TEST_OS',
      choices: ['all', 'debian-13', 'oracle-9'],
      description: 'Which test host(s) to run on'
    )
    string(
      name: 'INSTANCE_TYPE',
      defaultValue: 't3.xlarge',
      description: 'EC2 instance type for the test host(s)'
    )
    string(
      name: 'PBS_TIMEOUT_HOURS',
      defaultValue: '6',
      description: 'Hard timeout for the pbs_test_runner.py run on each host'
    )
  }
  options {
    withCredentials(moleculepxcJenkinsCreds())
    disableConcurrentBuilds()
    disableResume()
    timeout(time: 12, unit: 'HOURS')
  }

  stages {
    stage('Set build name') {
      steps {
        script {
          currentBuild.displayName = "${env.BUILD_NUMBER}-pbs${env.PBS_VERSION ?: '-latest'}-ps${env.PS_VERSION}-${params.TEST_OS}"
          currentBuild.description = "${env.PBS_TESTS ?: 'all'} | ${env.PBS_QA_REPO}@${env.PBS_QA_BRANCH}"
        }
      }
    }

    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: env.TESTING_BRANCH, url: "https://github.com/${env.TESTING_GIT_ACCOUNT}/package-testing.git"
      }
    }

    // PS_VERSION -> tarball URL per host (resolvePsTarballs), checked before any EC2 host is
    // created. The URLs are passed to the role as PS_TARBALL_URL_*, so this is the one place
    // that decides them.
    stage('Resolve versions') {
      steps {
        script {
          def hosts = [
            'oracle-9' : [glibc: env.PS_RHEL_GLIBC_VERSION, override: env.PS_TARBALL_URL_RHEL, family: 'RHEL'],
            'debian-13': [glibc: env.PS_DEBIAN_GLIBC_VERSION, override: env.PS_TARBALL_URL_DEB, family: 'DEB'],
          ].findAll { os, cfg -> params.TEST_OS == 'all' || params.TEST_OS == os }
          def ps = resolvePsTarballs(env.PS_VERSION, hosts, env.PS_TARBALL_SOURCE)
          env.PS_VERSION_RESOLVED = ps.version
          env.PS_TARBALL_URL_RESOLVED_RHEL = ps.urls['RHEL'] ?: ''
          env.PS_TARBALL_URL_RESOLVED_DEB = ps.urls['DEB'] ?: ''
          def fromTesting = ps.urls.values().any { it.contains('/downloads/TESTING/') } ? '-testing' : ''
          currentBuild.displayName = "${env.BUILD_NUMBER}-pbs${env.PBS_VERSION ?: '-latest'}-ps${env.PS_VERSION_RESOLVED}${fromTesting}-${params.TEST_OS}"
        }
      }
    }

    stage('Prepare') {
      steps {
        script {
          installMoleculeBookwormMysql()
        }
      }
    }

    stage('Run test') {
      steps {
        script {
          sh """
              echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
          """
          def envMap = loadEnvFile('.env.ENV_VARS')
          def skipOS = (params.TEST_OS == 'all') ? [] : pbsTestOSes().findAll { it != params.TEST_OS }
          // environment {} values can't be reassigned via env.X, so pass the resolved version here
          withEnv(envMap + ["PS_VERSION=${env.PS_VERSION_RESOLVED}",
                            "PS_TARBALL_URL_RHEL=${env.PS_TARBALL_URL_RESOLVED_RHEL}",
                            "PS_TARBALL_URL_DEB=${env.PS_TARBALL_URL_RESOLVED_DEB}"]) {
            moleculeParallelTestSkip(pbsTestOSes(), env.MOLECULE_DIR, skipOS)
          }
        }
      }
    }
  }

  post {
    always {
      script {
        archiveArtifacts artifacts: "pbs_logs_*.tar.gz", followSymlinks: false, allowEmptyArchive: true
        junit allowEmptyResults: true, testResults: "**/junit-pbs-*.xml"
        // The molecule venv only exists once Prepare has run (not after a checkout failure).
        // A destroy failure must not stop deleteBuildInstances() below from running.
        if (fileExists('virtenv/bin/activate')) {
          try {
            moleculeParallelPostDestroy(pbsTestOSes(), env.MOLECULE_DIR)
          } catch (err) {
            echo "molecule destroy failed (${err}); deleteBuildInstances() cleans up by tag"
          }
        } else {
          echo 'Molecule virtualenv not created; skipping molecule destroy'
        }
      }
      deleteBuildInstances()
    }
  }
}
