
library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// ---------------------------------------------------------------------------
// ps-package-testing-molecule-chaos-iops
//
// First "chaos / IOPS" job for the local Jenkins setup on the "lenovo" node
// (a Mac agent running QEMU). Unlike ps-package-testing-molecule-chaos.groovy
// -- which runs the OS scenarios in parallel -- this job runs the VMs ONE BY
// ONE and, for each VM, sweeps the guest disk IOPS limit across a range
// (default 100 -> 1000). For every IOPS point it brings the VM up with the
// disk throttled to that value, runs the install PS playbook (converge), and
// tears the VM down again.
//
//  - QEMU create/converge/destroy lifecycle  -> package-testing molecule/ps/chaos
//  - Disk IOPS throttle                       -> QEMU_IOPS env var consumed by
//                                                 shared/create.yml (see README
//                                                 note at the bottom of this file)
//
// The disk throttle is applied by QEMU itself via
//   -drive ...,throttling.iops-total=${QEMU_IOPS}
// which shared/create.yml appends to the VM's -drive line when QEMU_IOPS is set.
// ---------------------------------------------------------------------------

// ubuntu24 is the only scenario this first job targets; the list is kept as a
// helper so the sweep can be pointed at more OSes later without touching the
// pipeline body.
def ps80PackageTesting() {
    return [
        'debian11',
        'debian12',
        'ubuntu22',
        'ubuntu24',
        'oraclelinux9',
        'rocky9',
        'al2023'
    ]
}

// Chaos QEMU scenarios live in the package-testing clone under
// molecule/ps/chaos/. Molecule resolves scenarios as molecule/<os>/ relative
// to this dir, and each scenario's converge reuses package-testing's shared
// playbooks (../../../../../playbooks/${PLAYBOOK_VAR}.yml).
def chaosMoleculeDir() {
    return "\${HOME}/package-testing/molecule/ps/chaos"
}

// Build the inclusive list of IOPS points to sweep: [minv, minv+step, ...]
// capped at maxv, and always including maxv so the top of the range is tested.
// e.g. (100, 1000, 300) -> [100, 400, 700, 1000].
def iopsSweepValues(int minv, int maxv, int step) {
    def vals = []
    for (int v = minv; v <= maxv; v += step) {
        vals << v
    }
    if (vals.isEmpty() || vals.last() != maxv) {
        vals << maxv
    }
    return vals
}

// Parse the OPERATING_SYSTEMS parameter: accepts comma and/or whitespace
// separated scenario names and returns a clean list.
def parseOsList(String raw) {
    return raw.split('[,\\s]+').findAll { it?.trim() }.collect { it.trim() }
}

// Sequential IOPS sweep. For each OS (one VM at a time) and for each IOPS value
// in the range, export QEMU_IOPS so create.yml throttles the guest disk, then
// run the create/converge/destroy lifecycle. converge runs the install PS
// playbook selected via PLAYBOOK_VAR. Nothing here runs in parallel -- the
// lenovo node brings up a single VM at a time.
def moleculeIopsSweepChaos(operatingSystems, iopsValues, venvDir) {
    operatingSystems.each { os ->
        iopsValues.each { iops ->
            stage("${os} @ ${iops} IOPS") {
                sh """
                    set -e
                    . ${venvDir}/bin/activate
                    cd ${chaosMoleculeDir()}
                    export QEMU_IOPS=${iops}
                    echo "===== ${os}: installing PS with guest disk throttled to ${iops} IOPS ====="
                    # On a create failure the real qemu error is redirected to
                    # /tmp/qemu-<os>-stderr.log by the create playbook and never
                    # reaches the molecule output - dump it (plus the serial
                    # console tail) so the cause is visible in Jenkins.
                    if ! molecule create -s ${os}; then
                        echo "===== qemu stderr (${os}) ====="
                        cat /tmp/qemu-${os}-stderr.log 2>/dev/null || echo "(no stderr log)"
                        echo "===== qemu serial log tail (${os}) ====="
                        tail -n 80 /tmp/qemu-${os}-serial.log 2>/dev/null || echo "(no serial log)"
                        echo "===== /dev/kvm status ====="
                        ls -l /dev/kvm 2>&1 || echo "(no /dev/kvm - expected on macOS/hvf)"
                        id
                        exit 1
                    fi
                    molecule converge -s ${os}
                    molecule destroy  -s ${os}
                """
            }
        }
    }
}

// Kill leftover VMs / disks from previous runs on the agent. Everything here is
// owned by the agent user (molecule launches the qemu processes and writes the
// /tmp artifacts as this user), so no sudo is needed.
def cleanupStaleVMs() {
    sh '''
        set +e
        echo "Killing any running qemu-system processes..."
        pkill -9 -f 'qemu-system-(x86_64|aarch64)' || true
        echo "Removing stale pidfiles, disks, cloud-init artifacts, and qemu logs..."
        rm -f /tmp/qemu-*.pid
        rm -f /tmp/qemu-*-serial.log /tmp/qemu-*-qemu.log /tmp/qemu-*-stderr.log
        rm -f /tmp/qemu-*-vars.fd
        rm -f /tmp/molecule-*.raw
        rm -f /tmp/cloud-init-*.iso
        rm -rf /tmp/cloud-init-*
        echo "Removing ansible caches (keeping ${HOME}/qemu-images intact)..."
        rm -rf ${HOME}/.ansible/tmp ${HOME}/.cache/molecule || true
        echo "Remaining qemu processes (should be none):"
        pgrep -af 'qemu-system-(x86_64|aarch64)' || true
    '''
}

// Build the molecule + QEMU environment (venv, cloud images) for the agent user
// by running scripts/chaos-setup.sh straight from this jenkins-pipelines
// checkout (it carries the molecule/ansible-core version pins).
def installMoleculeChaos() {
    sh '''
        set -e
        bash "${WORKSPACE}/scripts/chaos-setup.sh"
    '''
}

// Clone/refresh the package-testing repo used by the shared converge playbook.
def clonePackageTesting(gitAccount, gitBranch) {
    sh """
        set -e
        PT_DIR="\${HOME}/package-testing"
        PT_URL="https://github.com/${gitAccount}/package-testing.git"
        if [ -d "\${PT_DIR}/.git" ]; then
            echo "Refreshing \${PT_DIR} (${gitAccount}/${gitBranch})"
            git -C "\${PT_DIR}" remote set-url origin "\${PT_URL}"
            # Fetch the requested branch into FETCH_HEAD and hard-reset onto it.
            git -C "\${PT_DIR}" fetch --depth 1 origin "${gitBranch}"
            git -C "\${PT_DIR}" checkout -qf FETCH_HEAD
            git -C "\${PT_DIR}" reset --hard FETCH_HEAD
        else
            echo "Cloning \${PT_URL} (${gitBranch}) -> \${PT_DIR}"
            git clone --depth 1 --branch "${gitBranch}" "\${PT_URL}" "\${PT_DIR}"
        fi
    """
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

properties([
    parameters([
        [
            $class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Product version to install-test (install action only)',
            name: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: 'return ["ps_80", "ps_57", "ps_84", "ps_innovation", "ps_97"]'
                ]
            ]
        ],
        string(
            defaultValue: 'ubuntu24',
            description: 'Scenario(s) to run, one VM at a time (comma/space separated, e.g. "ubuntu24" or "ubuntu24, rocky9")',
            name: 'OPERATING_SYSTEMS',
            trim: true
        ),
        string(
            defaultValue: '100',
            description: 'Lowest guest-disk IOPS limit to test',
            name: 'IOPS_MIN',
            trim: true
        ),
        string(
            defaultValue: '1000',
            description: 'Highest guest-disk IOPS limit to test',
            name: 'IOPS_MAX',
            trim: true
        ),
        string(
            defaultValue: '300',
            description: 'IOPS step between sweep points (100 -> 1000 step 300 = 100, 400, 700, 1000)',
            name: 'IOPS_STEP',
            trim: true
        ),
        string(
            defaultValue: 'Percona-QA',
            description: 'Git account name',
            name: 'git_account',
            trim: false
        ),
        string(
            defaultValue: 'master',
            description: 'Git Branch name',
            name: 'git_branch',
            trim: false
        ),
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Install Repo',
            name: 'install_repo'
        ),
        choice(
            choices: ['yes', 'no'],
            description: 'check_warnings',
            name: 'check_warnings'
        ),
        choice(
            choices: ['yes', 'no'],
            description: 'Install MySQL Shell',
            name: 'install_mysql_shell'
        )
    ])
])

pipeline {
    agent {
        label 'lenovo'
    }
    environment {
        VENV_DIR                     = "${env.HOME}/.venv/molecule_qemu"
        ANSIBLE_DEPRECATION_WARNINGS = "False"
        product_to_test              = "${params.product_to_test}"
        install_repo                 = "${params.install_repo}"
        action_to_test              = "install"
        check_warnings               = "${params.check_warnings}"
        install_mysql_shell          = "${params.install_mysql_shell}"
        EOL                          = "yes" // PS 57 has default EOL to yes
        TESTING_BRANCH               = "${params.git_branch}"
        TESTING_GIT_ACCOUNT          = "${params.git_account}"
    }
    options {
        timeout(time: 6, unit: 'HOURS')
    }
    stages {
        stage('Set Build Name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-install-iops"
                }
            }
        }
        stage('Cleanup stale VMs') {
            steps {
                script {
                    cleanupStaleVMs()
                }
            }
        }
        stage('Prepare') {
            steps {
                script {
                    installMoleculeChaos()
                }
            }
        }
        stage('Clone package-testing') {
            steps {
                script {
                    clonePackageTesting(params.git_account, params.git_branch)
                }
            }
        }
        stage('RUN IOPS TESTS') {
            steps {
                script {
                    // install action -> PLAYBOOK_VAR is just the product name,
                    // which selects playbooks/${product_to_test}.yml on converge.
                    sh """
                        echo PLAYBOOK_VAR="${product_to_test}" > .env.ENV_VARS
                        echo IIT_BILLING_TAG="${product_to_test}_package_testing" >> .env.ENV_VARS
                    """

                    def envMap     = loadEnvFile('.env.ENV_VARS')
                    def osList     = parseOsList(params.OPERATING_SYSTEMS)
                    def iopsValues = iopsSweepValues(
                        params.IOPS_MIN.trim() as int,
                        params.IOPS_MAX.trim() as int,
                        params.IOPS_STEP.trim() as int
                    )

                    if (osList.isEmpty()) {
                        error("OPERATING_SYSTEMS is empty - nothing to test")
                    }
                    echo "Sweeping IOPS ${iopsValues.join(', ')} over scenarios: ${osList.join(', ')} (one VM at a time)"

                    withEnv(envMap) {
                        if (product_to_test == "ps_57") {
                            withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                moleculeIopsSweepChaos(osList, iopsValues, "${VENV_DIR}")
                            }
                        } else {
                            moleculeIopsSweepChaos(osList, iopsValues, "${VENV_DIR}")
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                // The post block also runs after an early failure (e.g. a failed
                // SCM checkout) where the environment{} block never initialised
                // VENV_DIR and no VM work ran. Read it via env (null-safe) and
                // skip teardown when there is nothing to tear down, so the post
                // block never masks the real failure with a secondary
                // MissingPropertyException. Wrap in try/catch so a dead/busy
                // node can't turn teardown into the headline error either.
                def venvDir = env.VENV_DIR
                if (venvDir?.trim()) {
                    try {
                        // Best-effort teardown of every scenario in case a
                        // converge failed before its destroy ran.
                        def osList = parseOsList(params.OPERATING_SYSTEMS)
                        sh """
                            if [ -d "${venvDir}" ] && [ -d "${chaosMoleculeDir()}" ]; then
                                . ${venvDir}/bin/activate
                                cd ${chaosMoleculeDir()}
                                for s in ${osList.join(' ')}; do
                                    molecule destroy -s \${s} || true
                                done
                            fi
                        """
                        cleanupStaleVMs()
                    } catch (err) {
                        echo "Post-build teardown skipped (node unavailable or no VMs): ${err}"
                    }
                } else {
                    echo "Skipping teardown: build failed before the environment was set up (no VENV_DIR)."
                }
            }
            echo "Pipeline completed."
        }
    }
}
