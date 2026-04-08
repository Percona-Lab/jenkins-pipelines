library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

@Field def DOCKER_IMAGE_MAP = [
    'oraclelinux:8':    'ghcr.io/evgeniypatlan/psmdb-build-oraclelinux8:latest',
    'oraclelinux:9':    'ghcr.io/evgeniypatlan/psmdb-build-oraclelinux9:latest',
    'amazonlinux:2023': 'ghcr.io/evgeniypatlan/psmdb-build-amzn2023:latest',
    'ubuntu:jammy':     'ghcr.io/evgeniypatlan/psmdb-build-jammy:latest',
    'ubuntu:noble':     'ghcr.io/evgeniypatlan/psmdb-build-noble:latest',
    'debian:bookworm':  'ghcr.io/evgeniypatlan/psmdb-build-bookworm:latest',
]

// Platforms to warm: [dockerOS, arch, agentLabel]
// Platforms to warm (x86_64 only for now, aarch64 to be added later)
@Field def PLATFORMS = [
    ['oraclelinux:8',    'x86_64',  'docker-64gb'],
    ['oraclelinux:9',    'x86_64',  'docker-64gb'],
    ['amazonlinux:2023', 'x86_64',  'docker-64gb'],
    ['ubuntu:jammy',     'x86_64',  'docker-64gb'],
    ['ubuntu:noble',     'x86_64',  'docker-64gb'],
    ['debian:bookworm',  'x86_64',  'docker-64gb'],
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

    def lastWarmed
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_CREDENTIALS, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        lastWarmed = sh(
            script: "aws s3 cp s3://${BAZEL_REMOTE_BUCKET}/${BAZEL_REMOTE_PREFIX_ROOT}/${cachePrefix}/.last-warmed-commit - 2>/dev/null || echo 'none'",
            returnStdout: true
        ).trim()
    }

    echo "Branch: ${branch}, HEAD: ${headCommit}, Last warmed: ${lastWarmed}"

    if (headCommit == lastWarmed && !params.FORCE) {
        echo "Cache is up to date for ${branch}"
        return ''
    }

    return headCommit
}

// Jenkins credential ID for S3 cache access
@Field def S3_CREDENTIALS = 'AWS_STASH'
// bazel-remote config — matches the remote-cached build pipeline so both
// read/write the same S3 prefix. Each platform has its own sub-prefix so
// commits across platforms don't poison each other's cache.
@Field def BAZEL_REMOTE_VERSION = 'v2.6.1'
@Field def BAZEL_REMOTE_PORT = '8080'
@Field def BAZEL_REMOTE_BUCKET = 'percona-jenkins-artifactory'
@Field def BAZEL_REMOTE_PREFIX_ROOT = 'bazel-remote'
@Field def BAZEL_REMOTE_MAX_SIZE_GB = '10'

// Relocate containerd data-root from root partition to /mnt to fix
// "no space left on device" when pulling large pre-built images.
// cloud.groovy moves docker's --data-root to /mnt/docker, but containerd
// has its own storage at /var/lib/containerd which stays on the root partition.
// Uses bind-mount (not symlink) to avoid SELinux/path-resolution surprises.
void prepareContainerd() {
    sh '''
        set -e
        if mountpoint -q /var/lib/containerd; then
            echo "containerd already bind-mounted to /mnt/containerd"
            df -h /var/lib/containerd || true
            exit 0
        fi
        echo "=== Relocating containerd data-root to /mnt/containerd ==="
        df -h / /mnt 2>/dev/null || true
        sudo systemctl stop docker.service docker.socket 2>/dev/null || sudo systemctl stop docker 2>/dev/null || true
        sudo systemctl stop containerd 2>/dev/null || true
        sudo mkdir -p /mnt/containerd
        if [ -d /var/lib/containerd ] && [ -n "$(sudo ls -A /var/lib/containerd 2>/dev/null)" ]; then
            sudo rsync -aHAX --numeric-ids /var/lib/containerd/ /mnt/containerd/ 2>/dev/null || true
            sudo find /var/lib/containerd -mindepth 1 -delete 2>/dev/null || true
        fi
        sudo mkdir -p /var/lib/containerd
        sudo mount --bind /mnt/containerd /var/lib/containerd
        sudo systemctl start containerd 2>/dev/null || true
        sudo systemctl start docker.socket docker.service 2>/dev/null || sudo systemctl start docker 2>/dev/null || true
        for i in 1 2 3 4 5 6 7 8 9 10; do
            if docker info >/dev/null 2>&1; then
                echo "docker is ready"
                break
            fi
            sleep 2
        done
        echo "=== containerd relocation complete ==="
        df -h /var/lib/containerd / /mnt 2>/dev/null || true
    '''
}

// Warm cache for a single platform by running a full build with bazel-remote
// pointed at S3. First run = cold compile uploads everything. Subsequent runs
// = fast (only new blobs get uploaded). No pre-download / post-upload sync —
// bazel-remote handles it lazily during the build itself.
void warmPlatform(String branch, String commit, String cachePrefix, String dockerOS, String arch) {
    def osPrefix = getOSPrefix(dockerOS)
    def baseImage = DOCKER_IMAGE_MAP[dockerOS]
    def image = baseImage ? baseImage.replace(':latest', ':latest-amd64') : dockerOS
    def usePrebuilt = (baseImage != null)
    def s3Prefix = "${BAZEL_REMOTE_PREFIX_ROOT}/${cachePrefix}/${osPrefix}/${arch}"
    def binUrl = "https://github.com/buchgr/bazel-remote/releases/download/${BAZEL_REMOTE_VERSION}/bazel-remote-${BAZEL_REMOTE_VERSION.replace('v','')}-linux-amd64"
    // Host-side path for the credentials file that will be mounted into the container.
    // Jenkins masks the file contents in console output because it's written inside withCredentials.
    def credsHostDir = "${env.WORKSPACE}/.bazel-remote-creds"
    def credsHostFile = "${credsHostDir}/credentials"
    def credsContainerFile = "/bazel-remote-creds/credentials"

    // Step 0: Relocate containerd data-root to /mnt (fixes image-pull OOD)
    prepareContainerd()

    // Run the build with bazel-remote as a sidecar INSIDE the container.
    // Credentials are written to a host-local file inside the withCredentials
    // block, then mounted read-only into the container. bazel-remote reads them
    // via --s3.auth_method=aws_credentials_file. Keys never appear on a command
    // line and are never passed as container env vars.
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_CREDENTIALS, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        // Write creds file on host (set +x keeps the values out of xtrace; Jenkins masks anyway).
        sh '''
            set +x
            mkdir -p "${WORKSPACE}/.bazel-remote-creds"
            chmod 700 "${WORKSPACE}/.bazel-remote-creds"
            umask 077
            cat > "${WORKSPACE}/.bazel-remote-creds/credentials" <<EOF
[default]
aws_access_key_id = ${AWS_ACCESS_KEY_ID}
aws_secret_access_key = ${AWS_SECRET_ACCESS_KEY}
EOF
            chmod 600 "${WORKSPACE}/.bazel-remote-creds/credentials"
            set -x
        '''
        try {
            def installDepsCmd = usePrebuilt ? '' : 'bash -x percona-packaging/scripts/psmdb_builder.sh --builddir=/tmp/build --install_deps=1 &&'
            sh """
                docker run --rm \
                    -v ${credsHostDir}:/bazel-remote-creds:ro \
                    ${image} \
                    bash -c '
                        set -ex
                        cd /tmp
                        git clone --depth 1 --branch ${branch} ${params.GIT_REPO} psmdb
                        cd psmdb

                        # Install build dependencies (skipped for pre-built images)
                        ${installDepsCmd}

                        # Install bazel
                        python3 buildscripts/install_bazel.py || true
                        export PATH=/root/.local/bin:\$PATH

                        # Extract version
                        BRANCH_NAME=${branch}
                        if echo \${BRANCH_NAME} | grep -q "^release-"; then
                            PSM_VER=\$(echo \${BRANCH_NAME} | sed "s/release-//")
                        else
                            PSM_VER="8.0.0-0"
                        fi
                        GIT_HASH=\$(git rev-parse HEAD)

                        # Poetry setup
                        poetry env use /opt/mongodbtoolchain/v4/bin/python3 2>/dev/null || true
                        poetry install --no-root --sync 2>/dev/null || true

                        # Start bazel-remote pointed at S3 with credentials-file auth.
                        # NOTE: this block is inside an outer bash -c single-quoted arg,
                        # so shell expansions happen at the INNER shell. Groovy interpolation
                        # is resolved before the script ever runs. We avoid PID tracking
                        # and embedded double quotes to keep quoting simple.
                        echo === Starting bazel-remote S3 prefix ${s3Prefix} ===
                        if [ ! -x /tmp/bazel-remote ]; then
                            curl -fsSL -o /tmp/bazel-remote ${binUrl}
                            chmod +x /tmp/bazel-remote
                        fi
                        mkdir -p /tmp/bazel-remote-data
                        /tmp/bazel-remote \\
                            --dir=/tmp/bazel-remote-data \\
                            --max_size=${BAZEL_REMOTE_MAX_SIZE_GB} \\
                            --http_address=127.0.0.1:${BAZEL_REMOTE_PORT} \\
                            --s3.endpoint=s3.amazonaws.com \\
                            --s3.bucket=${BAZEL_REMOTE_BUCKET} \\
                            --s3.prefix=${s3Prefix} \\
                            --s3.auth_method=aws_credentials_file \\
                            --s3.aws_shared_credentials_file=${credsContainerFile} \\
                            --s3.aws_profile=default \\
                            > /tmp/bazel-remote.log 2>&1 &
                        for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
                            if curl -fs http://127.0.0.1:${BAZEL_REMOTE_PORT}/status >/dev/null 2>&1; then
                                echo bazel-remote is healthy
                                break
                            fi
                            sleep 1
                        done
                        if ! curl -fs http://127.0.0.1:${BAZEL_REMOTE_PORT}/status >/dev/null 2>&1; then
                            echo ERROR bazel-remote failed to start
                            cat /tmp/bazel-remote.log
                            exit 1
                        fi

                        # Compile with remote cache. bazel-remote uploads every new
                        # blob to S3 during the build, so this IS the warmup.
                        # Note: --remote_upload_local_results=true overrides the false
                        # default in .bazelrc.psmdb so locally-built results get pushed
                        # to the cache. Without this the warmup compiles but uploads nothing.
                        bazel build --config=psmdb_opt_release \\
                            --remote_cache=http://127.0.0.1:${BAZEL_REMOTE_PORT} \\
                            --remote_upload_local_results=true \\
                            --define=MONGO_VERSION=\${PSM_VER:-8.0.0} \\
                            --define=GIT_COMMIT_HASH=\${GIT_HASH} \\
                            install-dist-test

                        # Print cache stats
                        echo === bazel-remote stats after build ===
                        curl -s http://127.0.0.1:${BAZEL_REMOTE_PORT}/metrics 2>/dev/null | grep -E bazel_remote_ | head -20 || true
                    '
            """

            // Write per-platform marker (aws s3 cp still sees withCredentials env vars)
            sh """
                echo '${commit}' | aws s3 cp - \
                    s3://${BAZEL_REMOTE_BUCKET}/${s3Prefix}/.last-warmed-commit
            """
        } finally {
            // Scrub the credentials file from the host — belt and braces even though
            // Jenkins cleans the workspace between runs.
            sh(script: "rm -rf ${credsHostDir}", returnStatus: true)
        }
    }
    echo "Cache warmed: ${cachePrefix}/${osPrefix}/${arch} @ ${commit.take(7)}"
}

@Field def needsWarmup = []
@Field def results = [:]

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
                            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_CREDENTIALS, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                                sh """
                                    echo '${branchInfo.commit}' | aws s3 cp - \
                                        s3://${BAZEL_REMOTE_BUCKET}/${BAZEL_REMOTE_PREFIX_ROOT}/${branchInfo.prefix}/.last-warmed-commit
                                """
                            }
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
