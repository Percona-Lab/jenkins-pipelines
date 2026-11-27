
library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// ---------------------------------------------------------------------------
// ps-package-testing-molecule-chaos-iops-bench
//
// OFFLINE IOPS-curve benchmark. Measures the TIME a fixed disk-bound task takes
// at each guest-disk IOPS cap -- no network anywhere.
//
// Why this exists: the original job (ps-package-testing-molecule-chaos-iops)
// used `molecule converge` == "install PS" as the metric. That is a NETWORK
// step (packages come from repo.percona.com), so its time is dominated by
// download contention, not the disk throttle -- the observed curve was
// non-monotonic (1000 IOPS was the SLOWEST) and every run FAILED.
//
// Crucially, swapping PLAYBOOK_VAR was NOT enough: `molecule converge` also runs
// the scenario's converge/prepare, which still pulls in the PS install. So this
// job NEVER calls converge. Instead, per IOPS point it:
//
//     molecule create  -s <os>     # qemu setup + disk throttled to QEMU_IOPS
//     ansible-playbook  <task>      # run a FIXED disk task, time it (NO converge)
//     molecule destroy -s <os>
//
// The task is driven by a throwaway playbook in the WORKSPACE (not in
// package-testing), executed straight against molecule's own ephemeral
// inventory. Nothing from package-testing's converge/prepare/ps_80 runs, so no
// bytes cross the network. The task itself is a zero-dependency Python O_DIRECT
// benchmark (python3 ships in every cloud image for cloud-init) that does a
// fixed number of random 4 KiB ops and reports how long it took.
//
// Output per point: task total seconds (the headline curve), write/read
// seconds, achieved write/read IOPS, write p99 latency -> archived CSV.
// ---------------------------------------------------------------------------

// Where the molecule chaos scenario lives in the package-testing clone. We only
// use it for create/destroy (qemu setup + throttle + teardown).
def chaosMoleculeDir() {
    return "\${HOME}/package-testing/molecule/ps/chaos"
}

// Build the inclusive list of IOPS points: [minv, minv+step, ...] capped at
// maxv, always including maxv. e.g. (100, 1000, 300) -> [100, 400, 700, 1000].
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

// Parse OPERATING_SYSTEMS: comma/whitespace separated scenario names -> list.
def parseOsList(String raw) {
    return raw.split('[,\\s]+').findAll { it?.trim() }.collect { it.trim() }
}

// Write the task artifacts into the Jenkins WORKSPACE (NOT package-testing):
//   iops_task.py  - the offline fixed-work O_DIRECT disk probe
//   iops_task.yml - a tiny playbook that runs the probe on the molecule guest,
//                   stamps the result with the run label/cap and fetches it back
// Using writeFile (not shell heredocs) keeps the content verbatim with no
// escaping games.
def writeTaskArtifacts() {
    // --- the probe: fixed WORK, reports how long it took -------------------
    // argv: <path> <ops>   ops = number of random 4 KiB operations per phase.
    // Prints ONE line:
    //   write_secs,read_secs,total_secs,write_iops,read_iops,write_p99_ms
    writeFile file: 'iops_task.py', text: '''#!/usr/bin/env python3
# Offline fixed-work O_DIRECT disk task. Stdlib only, so it runs on any cloud
# image with python3 (every one -- cloud-init needs it). Does a FIXED number of
# random 4 KiB operations and reports how long that took, so the headline metric
# is "time for a task execution" and falls cleanly as the IOPS cap rises.
import os, sys, time, random, mmap

PATH = sys.argv[1] if len(sys.argv) > 1 else "/var/tmp/iops_probe.bin"
OPS  = int(sys.argv[2]) if len(sys.argv) > 2 else 3000
BS = 4096
FILE_BYTES = 4 * 1024 * 1024    # small file: bounded lay-down even at 100 IOPS
BLOCKS = FILE_BYTES // BS

def open_target(path):
    flags = os.O_RDWR | os.O_CREAT
    # O_DIRECT|O_DSYNC so every op is a real device op (page cache cannot hide
    # the QEMU throttle). getattr keeps it import-safe where the names are
    # absent; fall back if the filesystem rejects O_DIRECT.
    o_direct = getattr(os, "O_DIRECT", 0)
    o_dsync  = getattr(os, "O_DSYNC", 0)
    try:
        return os.open(path, flags | o_direct | o_dsync)
    except OSError:
        try:
            return os.open(path, flags | o_dsync)
        except OSError:
            return os.open(path, flags)

def aligned(nbytes):
    buf = mmap.mmap(-1, nbytes)   # page-aligned, required for O_DIRECT buffers
    buf.write(b"a" * nbytes)
    buf.seek(0)
    return buf

def p99(samples):
    if not samples:
        return 0.0
    s = sorted(samples)
    idx = min(len(s) - 1, int(round(0.99 * len(s) + 0.5)) - 1)
    return s[max(0, idx)] * 1000.0   # ms

rnd = random.Random(1234)
fd = open_target(PATH)
try:
    wbuf = aligned(BS)
    # lay the file down once so reads hit allocated blocks
    for b in range(BLOCKS):
        os.pwrite(fd, wbuf, b * BS)

    # ---- fixed-work random WRITE ----
    wlat = []
    t0 = time.time()
    for _ in range(OPS):
        off = rnd.randrange(BLOCKS) * BS
        s = time.time()
        os.pwrite(fd, wbuf, off)
        wlat.append(time.time() - s)
    write_secs = time.time() - t0

    # ---- fixed-work random READ ----
    t0 = time.time()
    for _ in range(OPS):
        off = rnd.randrange(BLOCKS) * BS
        os.pread(fd, BS, off)
    read_secs = time.time() - t0
finally:
    os.close(fd)
    try:
        os.unlink(PATH)
    except OSError:
        pass

total_secs = write_secs + read_secs
write_iops = OPS / write_secs if write_secs > 0 else 0.0
read_iops  = OPS / read_secs  if read_secs  > 0 else 0.0
print("%.3f,%.3f,%.3f,%.1f,%.1f,%.3f" % (
    write_secs, read_secs, total_secs, write_iops, read_iops, p99(wlat)))
'''

    // --- the task playbook: run the probe on the guest, fetch the result ----
    // Driven by `ansible-playbook -i <molecule inventory>` -- NOT molecule
    // converge -- so the scenario's install playbook never runs.
    writeFile file: 'iops_task.yml', text: '''---
- name: Offline guest disk IOPS task (no network, no converge)
  hosts: all
  gather_facts: false
  vars:
    iops_cap: "unknown"
    bench_label: "run"
    ops: 3000
    probe_file: "/var/tmp/iops_probe.bin"
    result_file: "/tmp/iops_result.csv"
  tasks:
    - name: Run the fixed-work disk task on the guest
      ansible.builtin.script: "{{ probe_script }} {{ probe_file }} {{ ops }}"
      register: probe
      changed_when: false

    - name: Stamp the result with the run label + cap
      ansible.builtin.copy:
        dest: "{{ result_file }}"
        content: "{{ bench_label }},{{ iops_cap }},{{ probe.stdout_lines[-1] | trim }}"
        mode: "0644"

    - name: Fetch the result back to the Jenkins agent
      ansible.builtin.fetch:
        src: "{{ result_file }}"
        dest: "/tmp/iops-bench-{{ bench_label }}.csvline"
        flat: true
'''
    echo "Wrote iops_task.py + iops_task.yml into the workspace."
}

// Sequential OFFLINE sweep. For each OS (one VM at a time) and each IOPS value:
// export QEMU_IOPS, `molecule create` (qemu setup + throttle), run the timed
// task via ansible-playbook against molecule's ephemeral inventory, then
// `molecule destroy`. converge is NEVER called, so the PS install never runs.
//
// Returns per-run maps with the timings/metrics parsed from the fetched result.
def moleculeIopsBenchSweep(operatingSystems, iopsValues, venvDir, ops) {
    def results = []
    operatingSystems.each { os ->
        iopsValues.each { iops ->
            stage("${os} @ ${iops} IOPS") {
                def label = "${os}-${iops}"
                def resultFile = "/tmp/iops-bench-${label}.csvline"
                def runFailed = false
                sh "rm -f ${resultFile} || true"
                try {
                    sh """
                        set +e
                        . ${venvDir}/bin/activate
                        cd ${chaosMoleculeDir()}
                        export QEMU_IOPS=${iops}
                        echo "===== ${os}: OFFLINE disk task, guest disk throttled to ${iops} IOPS ====="

                        molecule create -s ${os}
                        crc=\$?
                        if [ \$crc -ne 0 ]; then
                            echo "===== qemu stderr (${os}) ====="
                            cat /tmp/qemu-${os}-stderr.log 2>/dev/null || echo "(no stderr log)"
                            echo "===== qemu serial log tail (${os}) ====="
                            tail -n 80 /tmp/qemu-${os}-serial.log 2>/dev/null || echo "(no serial log)"
                        fi

                        vrc=1
                        if [ \$crc -eq 0 ]; then
                            # Locate molecule's ephemeral inventory for this
                            # scenario (default path, with a find fallback) and
                            # run OUR task playbook straight against it -- no
                            # converge, so no install playbook is ever invoked.
                            INV="\${HOME}/.cache/molecule/chaos/${os}/inventory"
                            if [ ! -d "\$INV" ]; then
                                INV=\$(dirname "\$(find \${HOME}/.cache/molecule -path '*${os}*/inventory/*' 2>/dev/null | head -1)")
                            fi
                            echo "Using molecule inventory: \$INV"
                            if [ -d "\$INV" ]; then
                                ansible-playbook -i "\$INV" "\${WORKSPACE}/iops_task.yml" \
                                    -e probe_script="\${WORKSPACE}/iops_task.py" \
                                    -e ops=${ops} \
                                    -e bench_label=${label} \
                                    -e iops_cap=${iops}
                                vrc=\$?
                            else
                                echo "ERROR: could not locate molecule inventory for ${os}"
                                vrc=1
                            fi
                        fi

                        molecule destroy -s ${os} || true

                        [ \$crc -eq 0 ] && [ \$vrc -eq 0 ]
                        exit \$?
                    """
                } catch (err) {
                    runFailed = true
                    echo "Run ${os} @ ${iops} IOPS failed: ${err}"
                }

                def m = [totalSec: '0', writeSec: '0', readSec: '0',
                         writeIops: '0', readIops: '0', wP99: '0']
                if (fileExists(resultFile)) {
                    // line: label,cap,write_secs,read_secs,total_secs,write_iops,read_iops,write_p99
                    def parts = readFile(resultFile).trim().split(',')
                    if (parts.size() >= 8) {
                        m.writeSec = parts[2]; m.readSec = parts[3]; m.totalSec = parts[4]
                        m.writeIops = parts[5]; m.readIops = parts[6]; m.wP99 = parts[7]
                    } else {
                        runFailed = true
                    }
                } else {
                    runFailed = true
                }

                def status = runFailed ? 'FAIL' : 'PASS'
                results << [os: os, iops: iops, status: status] + m
                echo "RESULT ${os} @ ${iops} IOPS -> ${status} | " +
                     "task ${m.totalSec}s (write ${m.writeSec}s read ${m.readSec}s) | " +
                     "write ${m.writeIops} iops p99 ${m.wP99}ms"
            }
        }
    }
    return results
}

// Render results as a table + CSV, print, archive. Sandbox-safe string ops only.
def reportIopsResults(results) {
    if (!results) {
        echo "No IOPS runs were executed - nothing to report."
        return
    }
    def table = "\n===== OFFLINE IOPS TASK-TIME SUMMARY =====\n"
    def csv = "os,iops_cap,status,task_total_sec,write_sec,read_sec," +
              "write_iops,read_iops,write_p99_ms\n"
    results.each { r ->
        table += "${r.os} | cap ${r.iops} | ${r.status} | " +
                 "task ${r.totalSec}s (W ${r.writeSec}s / R ${r.readSec}s) | " +
                 "W ${r.writeIops} iops p99 ${r.wP99}ms\n"
        csv += "${r.os},${r.iops},${r.status},${r.totalSec},${r.writeSec},${r.readSec}," +
               "${r.writeIops},${r.readIops},${r.wP99}\n"
    }
    echo table
    writeFile file: 'iops-bench.csv', text: csv
    archiveArtifacts artifacts: 'iops-bench.csv', allowEmptyArchive: true, fingerprint: true
}

// Kill leftover VMs / disks from previous runs on the agent.
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
        pgrep -af 'qemu-system-(x86_64|aarch64)' || true
    '''
}

// Build the molecule + QEMU environment for the agent user.
def installMoleculeChaos() {
    sh '''
        set -e
        bash "${WORKSPACE}/scripts/chaos-setup.sh"
    '''
}

// Clone/refresh package-testing (needed only for the molecule chaos scenario's
// create/destroy wiring; the task workload lives in the workspace).
def clonePackageTesting(gitAccount, gitBranch) {
    sh """
        set -e
        PT_DIR="\${HOME}/package-testing"
        PT_URL="https://github.com/${gitAccount}/package-testing.git"
        if [ -d "\${PT_DIR}/.git" ]; then
            echo "Refreshing \${PT_DIR} (${gitAccount}/${gitBranch})"
            git -C "\${PT_DIR}" remote set-url origin "\${PT_URL}"
            git -C "\${PT_DIR}" fetch --depth 1 origin "${gitBranch}"
            git -C "\${PT_DIR}" checkout -qf FETCH_HEAD
            git -C "\${PT_DIR}" reset --hard FETCH_HEAD
        else
            echo "Cloning \${PT_URL} (${gitBranch}) -> \${PT_DIR}"
            git clone --depth 1 --branch "${gitBranch}" "\${PT_URL}" "\${PT_DIR}"
        fi
    """
}

properties([
    parameters([
        string(
            defaultValue: 'debian12',
            description: 'Scenario(s) to run, one VM at a time (comma/space separated, e.g. "debian12" or "debian12, ubuntu24")',
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
            defaultValue: '200',
            description: 'IOPS step between sweep points (100 -> 1000 step 200 = 100, 300, 500, 700, 900, 1000)',
            name: 'IOPS_STEP',
            trim: true
        ),
        string(
            defaultValue: '3000',
            description: 'Fixed number of random 4 KiB ops the task performs per phase (the "work" whose time is measured)',
            name: 'TASK_OPS',
            trim: true
        ),
        string(
            defaultValue: 'Percona-QA',
            description: 'Git account name (for the molecule chaos scenario / create wiring only)',
            name: 'git_account',
            trim: false
        ),
        string(
            defaultValue: 'master',
            description: 'Git Branch name',
            name: 'git_branch',
            trim: false
        )
    ])
])

pipeline {
    agent {
        label 'lenovo'
    }
    environment {
        VENV_DIR = "${env.HOME}/.venv/molecule_qemu"
        ANSIBLE_DEPRECATION_WARNINGS = "False"
        // Our ansible-playbook talks straight to molecule's inventory; disable
        // host-key prompting so a fresh VM's unknown key never blocks the run.
        ANSIBLE_HOST_KEY_CHECKING = "False"
    }
    options {
        timeout(time: 4, unit: 'HOURS')
    }
    stages {
        stage('Set Build Name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-offline-iops-bench"
                }
            }
        }
        stage('Cleanup stale VMs') {
            steps {
                script { cleanupStaleVMs() }
            }
        }
        stage('Prepare') {
            steps {
                script { installMoleculeChaos() }
            }
        }
        stage('Clone package-testing') {
            steps {
                script { clonePackageTesting(params.git_account, params.git_branch) }
            }
        }
        stage('Write task artifacts') {
            steps {
                script { writeTaskArtifacts() }
            }
        }
        stage('RUN OFFLINE IOPS BENCHMARK') {
            steps {
                script {
                    def osList     = parseOsList(params.OPERATING_SYSTEMS)
                    def iopsValues = iopsSweepValues(
                        params.IOPS_MIN.trim() as int,
                        params.IOPS_MAX.trim() as int,
                        params.IOPS_STEP.trim() as int
                    )
                    if (osList.isEmpty()) {
                        error("OPERATING_SYSTEMS is empty - nothing to test")
                    }
                    echo "Sweeping IOPS ${iopsValues.join(', ')} over scenarios: ${osList.join(', ')} (one VM at a time, OFFLINE, no converge)"

                    def results = moleculeIopsBenchSweep(osList, iopsValues, "${VENV_DIR}", params.TASK_OPS.trim())

                    reportIopsResults(results)
                    if (results.any { it.status == 'FAIL' }) {
                        currentBuild.result = 'UNSTABLE'
                        echo "One or more IOPS runs failed - see the summary above."
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                def venvDir = env.VENV_DIR
                if (venvDir?.trim()) {
                    try {
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
