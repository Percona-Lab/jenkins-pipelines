library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Test matrix for the mysql-shell packaging builder.
//
// Unlike the release job this does not stash artifacts between stages, sign or
// publish anything. Every OS runs the full pipeline standalone -- source
// tarball, source package and binary package -- so a failure is attributable to
// one OS and one OS only. A failing cell marks the build UNSTABLE and lets the
// rest of the matrix finish, and the summary at the end lists every cell.
//
// The builder is used from a git checkout rather than a single downloaded file
// because it needs the patches/ directory next to it.

def RESULTS = java.util.Collections.synchronizedMap([:])

def osMatrix() {
    return [
        [name: 'Oracle Linux 8',      image: 'oraclelinux:8',    type: 'rpm'],
        [name: 'Oracle Linux 9',      image: 'oraclelinux:9',    type: 'rpm'],
        [name: 'Oracle Linux 10',     image: 'oraclelinux:10',   type: 'rpm'],
        [name: 'Amazon Linux 2023',   image: 'amazonlinux:2023', type: 'rpm'],
        [name: 'Debian Bookworm',     image: 'debian:bookworm',  type: 'deb'],
        [name: 'Debian Trixie',       image: 'debian:trixie',    type: 'deb'],
        [name: 'Ubuntu Jammy',        image: 'ubuntu:jammy',     type: 'deb'],
        [name: 'Ubuntu Noble',        image: 'ubuntu:noble',     type: 'deb'],
        [name: 'Ubuntu Resolute',     image: 'ubuntu:resolute',  type: 'deb'],
    ]
}

void checkoutBuilder() {
    sh """
        set -o xtrace
        rm -rf packaging
        git clone --depth 1 --branch ${BUILD_BRANCH} ${BUILD_REPO} packaging
        test -f packaging/mysql-shell_builder.sh
        test -d packaging/patches
    """
}

void runBuild(String dockerImage, String pkgType) {
    def sourceParam = (pkgType == 'rpm') ? '--build_src_rpm=1' : '--build_source_deb=1'
    def binaryParam = (pkgType == 'rpm') ? '--build_rpm=1' : '--build_deb=1'
    sh """
        set -o xtrace
        export build_dir=\$(pwd -P)
        mkdir -p \${build_dir}/build
        docker run --rm -u root -v \${build_dir}:\${build_dir} ${dockerImage} sh -c "
            set -o xtrace
            cd \${build_dir}/packaging
            bash ./mysql-shell_builder.sh \
                --builddir=\${build_dir}/build \
                --install_deps=1 \
                --get_sources=1 \
                ${sourceParam} \
                ${binaryParam} \
                --verify=0 \
                --repo_mysqlshell=${SHELL_REPO} \
                --mysqlshell_branch=${SHELL_BRANCH} \
                --repo=${PS_REPO} \
                --branch_db=${PS_BRANCH} \
                --rpm_release=${RPM_RELEASE} \
                --deb_release=${DEB_RELEASE} \
                --with_js=${WITH_JS} \
                --refresh_patches=${REFRESH_PATCHES} \
                --apply_patches=${APPLY_PATCHES}"
    """
}

// Verification deliberately runs on the agent rather than inside the build
// container: the package must be installed into an image that has never seen
// the build tree, otherwise build-time RPATHs still resolve and a package that
// cannot start on a user's machine passes every check.
void verifyPackage(String dockerImage, String pkgType) {
    def pkgDir = (pkgType == 'rpm') ? 'packaging/rpm' : 'packaging/deb'
    // debug symbol packages are not what users install and add hundreds of
    // megabytes to the check, so verify only the shipped packages
    def installCmd = (pkgType == 'rpm') ?
        'dnf -y install $(ls /pkg/*.rpm | grep -vE "debuginfo|debugsource")' :
        'apt-get update -qq && apt-get -y install $(ls /pkg/*.deb | grep -v dbgsym)'

    writeFile file: 'verify.sh', text: """#!/bin/sh
set -x
export DEBIAN_FRONTEND=noninteractive
if ! { ${installCmd} ; } >/tmp/install.log 2>&1; then
    echo "FAIL: package does not install"
    tail -20 /tmp/install.log
    exit 1
fi

rc=0
mysqlsh --version || rc=1
mysqlsh --version 2>&1 | grep -q "Ver " || { echo "FAIL: no version string"; rc=1; }

mysqlsh --py -e 'print("PYOK")' 2>&1 | grep -q PYOK || { echo "FAIL: python mode"; rc=1; }

if [ "${WITH_JS}" != "0" ]; then
    mysqlsh --js -e 'println("JSOK")' 2>&1 | grep -q JSOK || { echo "FAIL: javascript mode"; rc=1; }
fi

shell_bin=\$(command -v mysqlsh || true)
if [ -n "\$shell_bin" ]; then
    unresolved=\$(ldd "\$shell_bin" 2>&1 | grep -c "not found")
    echo "unresolved shared libraries: \$unresolved"
    [ "\$unresolved" = "0" ] || { echo "FAIL: mysqlsh has unresolved shared libraries"; rc=1; }
else
    echo "FAIL: mysqlsh is not on PATH"
    rc=1
fi

for b in /usr/libexec/mysqlsh/mysqlbinlog /usr/lib/mysqlsh/mysqlbinlog; do
    if [ -x "\$b" ]; then
        "\$b" --version >/dev/null 2>&1 || { echo "FAIL: bundled mysqlbinlog is broken"; rc=1; }
    fi
done
for b in /usr/libexec/mysqlsh/mysql_config_editor /usr/lib/mysqlsh/mysql_config_editor; do
    if [ -x "\$b" ]; then
        "\$b" print --all >/dev/null 2>&1 || { echo "FAIL: bundled mysql_config_editor is broken"; rc=1; }
    fi
done

[ \$rc -eq 0 ] && echo "VERIFY OK" || echo "VERIFY FAILED"
exit \$rc
"""

    sh """
        set -o xtrace
        export build_dir=\$(pwd -P)
        ls -l \${build_dir}/${pkgDir}
        chmod +x verify.sh
        docker run --rm -u root \
            -v \${build_dir}/${pkgDir}:/pkg:ro \
            -v \${build_dir}/verify.sh:/verify.sh:ro \
            ${dockerImage} /verify.sh
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    parameters {
        choice(
            choices: [ 'Hetzner', 'AWS' ],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/percona/mysql-shell-packaging.git',
            description: 'URL for mysql-shell packaging repository',
            name: 'BUILD_REPO')
        string(
            defaultValue: 'refactor/upstream-packaging',
            description: 'Branch of the packaging repository to test',
            name: 'BUILD_BRANCH')
        string(
            defaultValue: 'https://github.com/mysql/mysql-shell.git',
            description: 'URL for mysql-shell repository',
            name: 'SHELL_REPO')
        string(
            defaultValue: '9.7.1',
            description: 'Tag/Branch for mysql-shell repository',
            name: 'SHELL_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server.git',
            description: 'URL for percona-server repository',
            name: 'PS_REPO')
        string(
            defaultValue: 'release-9.7.1-1',
            description: 'Tag/Branch for percona-server repository',
            name: 'PS_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        choice(
            choices: [ '1', '0' ],
            description: 'Build the GraalVM JavaScript library',
            name: 'WITH_JS')
        choice(
            choices: [ '1', '0' ],
            description: 'Apply the Percona patch series (0 builds vanilla upstream)',
            name: 'APPLY_PATCHES')
        choice(
            choices: [ '1', '0' ],
            description: 'Refresh Percona patches from the fork before building',
            name: 'REFRESH_PATCHES')
        choice(
            choices: [ 'x64 and aarch64', 'x64 only', 'aarch64 only' ],
            description: 'Architectures to build',
            name: 'ARCHES')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 8, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Build matrix') {
            steps {
                script {
                    def branches = [:]
                    def arches
                    if (params.ARCHES == 'x64 only') {
                        arches = ['x64']
                    } else if (params.ARCHES == 'aarch64 only') {
                        arches = ['aarch64']
                    } else {
                        arches = ['x64', 'aarch64']
                    }
                    echo "matrix: ${osMatrix().size()} OS x ${arches.size()} arch"

                    osMatrix().each { os ->
                        arches.each { arch ->
                            def cell = "${os.name} (${arch})"
                            branches[cell] = {
                                def agentLabel
                                if (arch == 'aarch64') {
                                    agentLabel = params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                                } else {
                                    agentLabel = params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                                }
                                node(agentLabel) {
                                    def started = System.currentTimeMillis()
                                    try {
                                        stage(cell) {
                                            cleanUpWS()
                                            checkoutBuilder()
                                            runBuild(os.image, os.type)
                                            verifyPackage(os.image, os.type)
                                            archiveArtifacts(
                                                artifacts: "packaging/${os.type}/*.${os.type}, packaging/srpm/*.rpm, packaging/source_deb/*.dsc, packaging/mysql-shell.properties",
                                                allowEmptyArchive: true,
                                                fingerprint: false)
                                            RESULTS[cell] = [status: 'PASS',
                                                             minutes: ((System.currentTimeMillis() - started) / 60000) as int]
                                        }
                                    } catch (err) {
                                        RESULTS[cell] = [status: 'FAIL',
                                                         minutes: ((System.currentTimeMillis() - started) / 60000) as int,
                                                         error: err.getMessage()]
                                        // keep the rest of the matrix running
                                        unstable("${cell} failed: ${err.getMessage()}")
                                    } finally {
                                        cleanUpWS()
                                        deleteDir()
                                    }
                                }
                            }
                        }
                    }
                    branches.failFast = false
                    parallel branches
                }
            }
        }
    }
    post {
        always {
            script {
                def pass = RESULTS.count { it.value.status == 'PASS' }
                def fail = RESULTS.size() - pass
                def report = new StringBuilder()
                report << "\n"
                report << "================ mysql-shell build matrix ================\n"
                report << "packaging branch : ${params.BUILD_BRANCH}\n"
                report << "mysql-shell      : ${params.SHELL_BRANCH}\n"
                report << "percona-server   : ${params.PS_BRANCH}\n"
                report << "javascript       : ${params.WITH_JS == '1' ? 'enabled' : 'disabled'}\n"
                report << "architectures    : ${params.ARCHES}\n"
                report << "percona patches  : ${params.APPLY_PATCHES == '1' ? 'applied' : 'disabled (vanilla upstream)'}\n"
                report << "----------------------------------------------------------\n"
                RESULTS.sort { it.key }.each { cell, r ->
                    report << String.format("%-30s %-6s %3d min%s\n",
                        cell, r.status, r.minutes,
                        r.error ? "  ${r.error.take(60)}" : "")
                }
                report << "----------------------------------------------------------\n"
                report << "${pass} passed, ${fail} failed, ${RESULTS.size()} total\n"
                report << "==========================================================\n"
                echo report.toString()

                writeFile file: 'matrix-summary.txt', text: report.toString()
                archiveArtifacts artifacts: 'matrix-summary.txt', allowEmptyArchive: true

                currentBuild.description = "${params.BUILD_BRANCH} | shell ${params.SHELL_BRANCH} | ${pass}/${RESULTS.size()} passed"
            }
        }
    }
}
