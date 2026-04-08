library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def DOCKER_IMAGE_MAP = [
    'oraclelinux:8':    'ghcr.io/evgeniypatlan/psmdb-build-oraclelinux8:latest',
    'oraclelinux:9':    'ghcr.io/evgeniypatlan/psmdb-build-oraclelinux9:latest',
    'amazonlinux:2023': 'ghcr.io/evgeniypatlan/psmdb-build-amzn2023:latest',
    'ubuntu:jammy':     'ghcr.io/evgeniypatlan/psmdb-build-jammy:latest',
    'ubuntu:noble':     'ghcr.io/evgeniypatlan/psmdb-build-noble:latest',
    'debian:bookworm':  'ghcr.io/evgeniypatlan/psmdb-build-bookworm:latest',
]

// Platforms to warm: [dockerOS, arch, agentLabel]
def PLATFORMS = [
    ['oraclelinux:8',    'x86_64',  'docker-64gb'],
    ['oraclelinux:9',    'x86_64',  'docker-64gb'],
    ['amazonlinux:2023', 'x86_64',  'docker-64gb'],
    ['ubuntu:jammy',     'x86_64',  'docker-64gb'],
    ['ubuntu:noble',     'x86_64',  'docker-64gb'],
    ['debian:bookworm',  'x86_64',  'docker-64gb'],
    ['oraclelinux:8',    'aarch64', 'docker-64gb-aarch64'],
    ['oraclelinux:9',    'aarch64', 'docker-64gb-aarch64'],
    ['amazonlinux:2023', 'aarch64', 'docker-64gb-aarch64'],
    ['ubuntu:jammy',     'aarch64', 'docker-64gb-aarch64'],
    ['ubuntu:noble',     'aarch64', 'docker-64gb-aarch64'],
]

String getOSPrefix(String dockerOS) {
    def map = [
        'oraclelinux:8': 'el8', 'oraclelinux:9': 'el9',
        'ubuntu:focal': 'focal', 'ubuntu:jammy': 'jammy', 'ubuntu:noble': 'noble',
        'debian:bullseye': 'bullseye', 'debian:bookworm': 'bookworm',
        'amazonlinux:2023': 'amzn2023',
    ]
    return map[dockerOS] ?: dockerOS.replaceAll('[:/]', '-')
}

String getCachePrefix(String branch) {
    def m = (branch =~ /release-(\d+\.\d+\.\d+)/)
    if (m.find()) {
        return "release-${m.group(1)}"
    }
    return "trunk"
}

// Check if cache needs warming for a branch
// Returns the HEAD commit if needs warming, empty string if up to date
String checkCacheStatus(String repo, String branch, String cachePrefix) {
    def headCommit = sh(
        script: "git ls-remote ${repo} refs/heads/${branch} | cut -f1",
        returnStdout: true
    ).trim()

    if (!headCommit) {
        echo "Branch ${branch} not found in ${repo}"
        return ''
    }

    def lastWarmed = sh(
        script: "aws s3 cp s3://psmdb-bazel-cache/${cachePrefix}/.last-warmed-commit - 2>/dev/null || echo 'none'",
        returnStdout: true
    ).trim()

    echo "Branch: ${branch}, HEAD: ${headCommit}, Last warmed: ${lastWarmed}"

    if (headCommit == lastWarmed && !params.FORCE) {
        echo "Cache is up to date for ${branch}"
        return ''
    }

    return headCommit
}

// Warm cache for a single platform
void warmPlatform(String branch, String commit, String cachePrefix, String dockerOS, String arch) {
    def osPrefix = getOSPrefix(dockerOS)
    def image = DOCKER_IMAGE_MAP[dockerOS]
    def cacheName = "bazel-cache-warmup-${env.BUILD_NUMBER}-${osPrefix}-${arch}"

    if (!image) {
        echo "No pre-baked image for ${dockerOS}, skipping"
        return
    }

    // Start bazel-remote sidecar
    sh """
        docker run -d --name ${cacheName} \
            --network=host \
            buchgr/bazel-remote-cache:v2.4.4 \
            --max_size=50 --dir=/tmp/cache \
            --s3.endpoint=s3.us-east-1.amazonaws.com \
            --s3.bucket=psmdb-bazel-cache \
            --s3.prefix=${cachePrefix}/${osPrefix}/${arch}/ \
            --s3.auth_method=iam_role \
            --grpc_address=0.0.0.0:9092 \
            --http_address=0.0.0.0:8080
        sleep 3
    """

    try {
        // Compile inside pre-baked container
        sh """
            docker run --rm --network=host \
                -e BAZEL_REMOTE_CACHE=grpc://localhost:9092 \
                ${image} \
                bash -c '
                    set -ex
                    cd /tmp
                    git clone --depth 1 --branch ${branch} ${params.GIT_REPO} psmdb
                    cd psmdb

                    # Install bazel if not in image
                    python3 buildscripts/install_bazel.py || true
                    export PATH=/root/.local/bin:\$PATH

                    # Extract version
                    PSM_VER=\$(grep "^VERSION=" /dev/stdin <<< "\$(cat version.json 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get(\\\"version\\\",\\\"8.0.0\\\"))" 2>/dev/null || echo "8.0.0")")
                    GIT_HASH=\$(git rev-parse HEAD)

                    # Poetry setup
                    poetry env use /opt/mongodbtoolchain/v4/bin/python3 2>/dev/null || true
                    poetry install --no-root --sync 2>/dev/null || true

                    # Compile only — populate cache
                    bazel build --config=psmdb_opt_release \
                        --remote_cache=grpc://localhost:9092 \
                        --remote_upload_local_results=true \
                        --define=MONGO_VERSION=\${PSM_VER:-8.0.0} \
                        --define=GIT_COMMIT_HASH=\${GIT_HASH} \
                        install-dist-test
                '
        """

        // Mark this platform as warmed
        sh """
            echo '${commit}' | aws s3 cp - \
                s3://psmdb-bazel-cache/${cachePrefix}/${osPrefix}/${arch}/.last-warmed-commit
        """
        echo "Cache warmed: ${cachePrefix}/${osPrefix}/${arch} @ ${commit.take(7)}"

    } finally {
        sh "docker stop ${cacheName} 2>/dev/null; docker rm ${cacheName} 2>/dev/null || true"
    }
}

def needsWarmup = []
def results = [:]

pipeline {
    agent {
        label 'micro-amazon'
    }
    triggers {
        cron('0 0 * * *')
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'PSMDB repository URL',
            name: 'GIT_REPO')
        booleanParam(
            defaultValue: false,
            description: 'Force warm-up even if cache is up to date',
            name: 'FORCE')
    }
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
    }
    stages {
        stage('Check cache status') {
            steps {
                script {
                    // Check master
                    def masterCommit = checkCacheStatus(params.GIT_REPO, 'master', 'trunk')
                    if (masterCommit) {
                        needsWarmup.add([branch: 'master', commit: masterCommit, prefix: 'trunk'])
                    }

                    // Find and check latest release branch
                    def releaseBranch = sh(
                        script: """
                            git ls-remote --heads ${params.GIT_REPO} 'refs/heads/release-8.0.*' |
                            sort -t/ -k3 -V | tail -1 | sed 's|.*refs/heads/||'
                        """,
                        returnStdout: true
                    ).trim()

                    if (releaseBranch) {
                        def releasePrefix = getCachePrefix(releaseBranch)
                        def releaseCommit = checkCacheStatus(params.GIT_REPO, releaseBranch, releasePrefix)
                        if (releaseCommit) {
                            needsWarmup.add([branch: releaseBranch, commit: releaseCommit, prefix: releasePrefix])
                        }
                    }

                    if (needsWarmup.isEmpty()) {
                        echo "All caches are up to date. Nothing to do."
                        currentBuild.description = "Cache up to date — skipped"
                    } else {
                        def branchList = needsWarmup.collect { "${it.branch}@${it.commit.take(7)}" }.join(', ')
                        echo "Branches needing warmup: ${branchList}"
                        currentBuild.description = "Warming: ${branchList}"
                    }
                }
            }
        }

        stage('Warm caches') {
            when {
                expression { return !needsWarmup.isEmpty() }
            }
            steps {
                script {
                    for (branchInfo in needsWarmup) {
                        def parallelStages = [:]

                        for (platform in PLATFORMS) {
                            def dockerOS = platform[0]
                            def arch = platform[1]
                            def agentLabel = platform[2]
                            def osPrefix = getOSPrefix(dockerOS)
                            def stageName = "${branchInfo.branch} / ${osPrefix} / ${arch}"

                            // Capture for closure
                            def bi = branchInfo
                            def dos = dockerOS
                            def a = arch
                            def al = agentLabel
                            def osp = osPrefix

                            parallelStages[stageName] = {
                                node(al) {
                                    stage(stageName) {
                                        try {
                                            warmPlatform(bi.branch, bi.commit, bi.prefix, dos, a)
                                            results[stageName] = 'OK'
                                        } catch (err) {
                                            echo "Failed to warm ${stageName}: ${err.getMessage()}"
                                            results[stageName] = 'FAILED'
                                        }
                                    }
                                }
                            }
                        }

                        parallel parallelStages
                    }

                    // Write branch-level marker after all platforms succeed
                    for (branchInfo in needsWarmup) {
                        def allOk = true
                        for (platform in PLATFORMS) {
                            def osPrefix = getOSPrefix(platform[0])
                            def stageName = "${branchInfo.branch} / ${osPrefix} / ${platform[1]}"
                            if (results[stageName] != 'OK') {
                                allOk = false
                                break
                            }
                        }
                        if (allOk) {
                            sh """
                                echo '${branchInfo.commit}' | aws s3 cp - \
                                    s3://psmdb-bazel-cache/${branchInfo.prefix}/.last-warmed-commit
                            """
                            echo "All platforms warmed for ${branchInfo.branch}"
                        } else {
                            echo "Some platforms failed for ${branchInfo.branch} — will retry next run"
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                if (!needsWarmup.isEmpty()) {
                    def okCount = results.values().count { it == 'OK' }
                    def failCount = results.values().count { it == 'FAILED' }
                    def msg = "[Cache Warmup]: ${okCount} platforms warmed, ${failCount} failed"
                    slackNotify("#releases-ci", "#00FF00", msg)
                    currentBuild.description = "${okCount} OK, ${failCount} FAILED"
                }
            }
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[Cache Warmup]: pipeline failed — [${BUILD_URL}]")
        }
        always {
            script {
                if (results) {
                    echo "=== Cache Warmup Results ==="
                    results.each { name, status ->
                        echo "  ${status}: ${name}"
                    }
                }
            }
        }
    }
}
