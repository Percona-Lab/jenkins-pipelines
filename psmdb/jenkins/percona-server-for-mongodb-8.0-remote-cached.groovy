library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

// Map Docker OS names to pre-baked image names
@Field def DOCKER_IMAGE_MAP = [
    'oraclelinux:8':    'ghcr.io/evgeniypatlan/psmdb-build-oraclelinux8:latest',
    'oraclelinux:9':    'ghcr.io/evgeniypatlan/psmdb-build-oraclelinux9:latest',
    'ubuntu:focal':     'ghcr.io/evgeniypatlan/psmdb-build-focal:latest',
    'ubuntu:jammy':     'ghcr.io/evgeniypatlan/psmdb-build-jammy:latest',
    'ubuntu:noble':     'ghcr.io/evgeniypatlan/psmdb-build-noble:latest',
    'debian:bullseye':  'ghcr.io/evgeniypatlan/psmdb-build-bullseye:latest',
    'debian:bookworm':  'ghcr.io/evgeniypatlan/psmdb-build-bookworm:latest',
    'amazonlinux:2023': 'ghcr.io/evgeniypatlan/psmdb-build-amzn2023:latest',
]

// Derive S3 cache prefix from branch name
String getCachePrefix(String branch) {
    def m = (branch =~ /release-(\d+\.\d+\.\d+)/)
    if (m.find()) {
        return "release-${m.group(1)}"
    }
    return "trunk"
}

// Get OS short name for cache path
String getOSPrefix(String dockerOS) {
    def map = [
        'oraclelinux:8': 'el8', 'oraclelinux:9': 'el9',
        'ubuntu:focal': 'focal', 'ubuntu:jammy': 'jammy', 'ubuntu:noble': 'noble',
        'debian:bullseye': 'bullseye', 'debian:bookworm': 'bookworm',
        'amazonlinux:2023': 'amzn2023',
    ]
    return map[dockerOS] ?: dockerOS.replaceAll('[:/]', '-')
}

@Field def S3_CREDENTIALS = 'AWS_STASH'
// bazel-remote config: single binary, downloaded at runtime inside the build
// container, started in background, reached via http://127.0.0.1:8080.
// Bazel fetches only the blobs it actually needs (lazy) instead of pre-syncing
// the entire disk cache.
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

// Container-side path where the host credentials file is mounted read-only.
@Field def BAZEL_REMOTE_CREDS_CONTAINER_PATH = '/bazel-remote-creds/credentials'

// Build the shell snippet that starts bazel-remote inside the build container.
// NOTE: this snippet is embedded inside an outer `sh -c "..."` double-quoted
// argument. That outer double-quoted context would expand any `$!` or
// `${VAR}` references at the outer bash layer (where they're empty), and any
// embedded `"` would break the outer quoting and cause word splitting. So
// this snippet deliberately:
//   - Does NOT track a PID (no `$!`, no `${PID}`)
//   - Does NOT use embedded double quotes in echoes
//   - Relies solely on the HTTP /status endpoint to confirm liveness
//
// AWS credentials: bazel-remote uses --s3.auth_method=aws_credentials_file
// and reads from a file mounted read-only into the container. The file is
// written on the host inside a withCredentials block in buildStage() — keys
// never appear on a command line or as container env vars.
String bazelRemoteStartSnippet(String branch, String dockerOS, String arch) {
    def cachePrefix = getCachePrefix(branch)
    def osPrefix = getOSPrefix(dockerOS)
    def s3Prefix = "${BAZEL_REMOTE_PREFIX_ROOT}/${cachePrefix}/${osPrefix}/${arch}"
    def binUrl = "https://github.com/buchgr/bazel-remote/releases/download/${BAZEL_REMOTE_VERSION}/bazel-remote-${BAZEL_REMOTE_VERSION.replace('v','')}-linux-amd64"
    return """
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
                    --s3.aws_shared_credentials_file=${BAZEL_REMOTE_CREDS_CONTAINER_PATH} \\
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

                echo === bazel-remote status BEFORE build ===
                curl -s http://127.0.0.1:${BAZEL_REMOTE_PORT}/status 2>/dev/null || true
                echo
                echo === bazel-remote metrics BEFORE build ===
                curl -s http://127.0.0.1:${BAZEL_REMOTE_PORT}/metrics 2>/dev/null | grep -E bazel_remote_ | head -30 || true
    """
}

// Helper snippet: print bazel-remote stats AFTER a build to see hit/miss counts.
String bazelRemoteStatsSnippet() {
    return """
                echo === bazel-remote status AFTER build ===
                curl -s http://127.0.0.1:${BAZEL_REMOTE_PORT}/status 2>/dev/null || true
                echo
                echo === bazel-remote metrics AFTER build ===
                curl -s http://127.0.0.1:${BAZEL_REMOTE_PORT}/metrics 2>/dev/null | grep -E bazel_remote_ | head -50 || true
    """
}

// Stages that invoke Bazel and therefore benefit from the disk cache.
// Non-Bazel stages (--get_sources, --build_src_rpm, --build_src_deb) skip
// cache download/upload to save minutes per stage on S3 sync.
boolean stageUsesBazel(String stageParam) {
    return stageParam.contains('--build_rpm=1') ||
           stageParam.contains('--build_deb=1') ||
           stageParam.contains('--build_tarball=1')
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    def osPrefix = getOSPrefix(DOCKER_OS)
    def arch = sh(script: 'uname -m', returnStdout: true).trim()
    // Prefer pre-built images with toolchain/deps baked in; fall back to bare OS if unmapped.
    def baseImage = DOCKER_IMAGE_MAP[DOCKER_OS]
    def image = baseImage ? baseImage.replace(':latest', ':latest-amd64') : DOCKER_OS
    def usePrebuilt = (baseImage != null)
    def usesBazel = stageUsesBazel(STAGE_PARAM)

    echo "Stage image: ${image} (${usePrebuilt ? 'pre-built' : 'bare OS + install_deps'})"
    echo "Cache mode: ${usesBazel ? 'bazel-remote (lazy fetch from S3)' : 'none'}"

    // Step 0: Relocate containerd data-root to /mnt (always — docker pull is heavy)
    prepareContainerd()

    // Skip install_deps for pre-built images (deps already baked in); run it for bare OS.
    def installDepsCmd = usePrebuilt ? '' : "bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1"
    // Start bazel-remote only for Bazel-consuming stages.
    def bazelRemoteStart = usesBazel ? bazelRemoteStartSnippet(GIT_BRANCH, DOCKER_OS, arch) : ''
    // Print cache stats AFTER the build so we can see hit/miss counts in the log.
    def bazelRemoteStats = usesBazel ? bazelRemoteStatsSnippet() : ''
    // Set BAZEL_REMOTE_CACHE env so psmdb_builder.sh / spec / debian/rules add --remote_cache=...
    def bazelRemoteEnv = usesBazel ? "-e BAZEL_REMOTE_CACHE=http://127.0.0.1:${BAZEL_REMOTE_PORT}" : ""
    // Host-side credentials file that gets mounted read-only into the container.
    // Matches the withCredentials pattern used by the disk-cache warmup pipeline.
    def credsHostDir = "${env.WORKSPACE}/.bazel-remote-creds"
    def credsMount = usesBazel ? "-v ${credsHostDir}:/bazel-remote-creds:ro" : ""

    withCredentials([[
        $class: 'AmazonWebServicesCredentialsBinding',
        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        credentialsId: S3_CREDENTIALS,
        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
    ]]) {
        // Write AWS credentials to host file (only for Bazel stages that need them).
        // set +x keeps the values out of xtrace; Jenkins masks them in console regardless.
        if (usesBazel) {
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
        }
        try {
            sh """
                set -o xtrace
                ls -laR ./
                # Backup properties file if it exists
                if [ -f test/percona-server-mongodb-80.properties ]; then
                    cp test/percona-server-mongodb-80.properties percona-server-mongodb-80.properties.backup
                fi
                rm -rf test/*
                mkdir -p test
                # Restore properties file if it was backed up
                if [ -f percona-server-mongodb-80.properties.backup ]; then
                    mv percona-server-mongodb-80.properties.backup test/percona-server-mongodb-80.properties
                fi
                wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
                pwd -P
                ls -laR
                export build_dir=\$(pwd -P)
                docker run -u root \
                    ${bazelRemoteEnv} \
                    ${credsMount} \
                    -v \${build_dir}:\${build_dir} ${image} sh -c "
                    set -o xtrace
                    cd \${build_dir}
                    ${installDepsCmd}
                    ${bazelRemoteStart}
                    bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} --mongo_tools_tag=${MONGO_TOOLS_TAG} ${STAGE_PARAM}
                    ${bazelRemoteStats}"
            """
        } finally {
            // Scrub the credentials file from the host.
            if (usesBazel) {
                sh(script: "rm -rf ${credsHostDir}", returnStatus: true)
            }
        }
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['AWS','Hetzner'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for  percona-server-mongodb repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'v8.0',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '8.0.0',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.9.5',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-80',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            name: 'TESTS',
            choices: ['yes', 'no'],
            description: 'Run functional tests on packages and tarballs after building')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PSMDB source tarball') {

            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    buildStage("oraclelinux:8", "--get_sources=1")
                }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-80.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-80.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-mongodb-80.properties', name: 'psmdb-properties'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PSMDB generic source packages') {

            parallel {
                stage('Build PSMDB generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:8", "--build_src_rpm=1")
                        }

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PSMDB generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:jammy", "--build_src_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PSMDB RPMs/DEBs/Binary tarballs') {

            parallel {
                stage('Oracle Linux 8(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:8", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:9", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            buildStage("amazonlinux:2023", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:jammy", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:noble", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)(x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            buildStage("debian:bookworm", "--build_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 binary tarball(glibc2.28)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:8", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Oracle Linux 9 binary tarball(glibc2.34)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("oraclelinux:9", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Amazon Linux 2023 binary tarball(glibc2.34)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("amazonlinux:2023", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) binary tarball(glibc2.35)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:jammy", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Noble(24.04) binary tarball(glibc2.39)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:noble", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Debian Bookworm(12) binary tarball(glibc2.36)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("debian:bookworm", "--build_tarball=1")
                            pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                        }
                    }
                }
            }
        }

        stage('Upload packages and tarballs from S3') {

            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
            }
            steps {
                cleanUpWS()

                uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
            }
        }

        stage('Sign packages') {

            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {

            steps {
                // sync packages
                script {
                    sync2ProdAutoBuild(params.CLOUD, PSMDB_REPO, COMPONENT)
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {

            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "psmdb", "${PSMDB_VERSION}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Run testing job') {
            when {
                expression { return params.TESTS == 'yes' }
            }
            steps {
                script {
                    build job: 'hetzner-psmdb-multijob-testing', propagate: false, wait: false, quietPeriod: 1800, parameters: [string(name: 'PSMDB_VERSION', value: PSMDB_VERSION), string(name: 'PSMDB_RELEASE', value: PSMDB_RELEASE)]
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
