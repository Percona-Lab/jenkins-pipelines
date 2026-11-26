
library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// ---------------------------------------------------------------------------
// ps-package-testing-molecule-chaos-iops-bench
//
// OFFLINE sibling of ps-package-testing-molecule-chaos-iops.groovy.
//
// The original job used converge (= installing PS) as the IOPS metric. But
// "install PS" is a NETWORK step -- it downloads packages from repo.percona.com
// -- so under network contention its wall-time is dominated by the download,
// not the guest disk throttle. The observed curve proved it: times were
// non-monotonic in IOPS (1000 IOPS was the SLOWEST) and every run FAILED, so
// converge-time was really time-to-failure of a network operation. Useless as
// a disk metric.
//
// This job removes the network entirely. At each QEMU_IOPS point it:
//     molecule create   (offline boot, disk throttled to QEMU_IOPS)
//     molecule converge (runs a LOCAL disk probe -- NOT a PS install)
//     molecule destroy
// The probe is a zero-dependency Python O_DIRECT random read/write benchmark
// that generates its own I/O inside the guest: no apt/yum, no repo, no network.
// Network contention physically cannot affect the numbers.
//
// No fork, no manually-shipped file: after cloning package-testing onto the
// agent this pipeline WRITES the probe playbook into that local clone's
// playbooks/ dir and sets PLAYBOOK_VAR to it, so converge imports it exactly
// the way it would import a product playbook. Everything lives in this repo.
//
// Per IOPS point we record: achieved write IOPS, write p95/p99 latency,
// achieved read IOPS, read p95/p99 latency, and write bandwidth -- a clean
// IOPS curve you can graph from the archived CSV.
// ---------------------------------------------------------------------------

// Chaos QEMU scenarios live in the package-testing clone under
// molecule/ps/chaos/. Molecule resolves scenarios as molecule/<os>/ relative
// to this dir, and converge imports playbooks/${PLAYBOOK_VAR}.yml.
def chaosMoleculeDir() {
    return "\${HOME}/package-testing/molecule/ps/chaos"
}

// Name (without .yml) of the offline probe playbook this pipeline drops into
// the local package-testing clone and sets as PLAYBOOK_VAR.
def benchPlaybookName() {
    return "disk_iops_benchmark"
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

// Materialise the OFFLINE probe playbook into the freshly-cloned
// package-testing/playbooks/ dir on the agent. Converge imports it by name via
// PLAYBOOK_VAR, so no fork and no out-of-repo file is needed.
//
// The playbook runs a small Python O_DIRECT benchmark inside the guest:
//   * lays down a 4 MiB file on the throttled root disk
//   * timed random-WRITE pass, then timed random-READ pass, O_DIRECT|O_DSYNC so
//     every op is a real device op (page cache can't hide the throttle)
//   * emits one CSV line and fetches it back to the agent as
//     /tmp/iops-bench-<label>.csvline
// Written with a QUOTED heredoc (<<'YAML_EOF') so neither Groovy nor the shell
// expands anything -- the file lands verbatim. The embedded Python likewise
// uses a quoted heredoc so Ansible/the shell leave it untouched.
def writeBenchPlaybook() {
    sh '''
        set -e
        PB_DIR="${HOME}/package-testing/playbooks"
        mkdir -p "${PB_DIR}"
        cat > "${PB_DIR}/disk_iops_benchmark.yml" <<'YAML_EOF'
---
- name: Offline guest disk IOPS benchmark (no network)
  hosts: all
  gather_facts: false
  vars:
    iops_cap: "unknown"
    bench_label: "run"
    probe_py: "/var/tmp/iops_probe.py"
    probe_file: "/var/tmp/iops_probe.bin"
    result_file: "/tmp/iops_result.csv"
  tasks:
    - name: Install the probe script into the guest (no packages, just a file)
      ansible.builtin.copy:
        dest: "{{ probe_py }}"
        mode: "0755"
        content: |
          #!/usr/bin/env python3
          # Offline O_DIRECT disk IOPS probe. No imports beyond the stdlib, so it
          # runs on any cloud image that has python3 (all of them -- cloud-init).
          # Measures achieved random-write then random-read IOPS with per-op
          # latency, bypassing the page cache so the QEMU disk throttle is what
          # we actually see. Prints ONE csv line:
          #   write_iops,write_p95_ms,write_p99_ms,read_iops,read_p95_ms,read_p99_ms,bw_kib_s
          import os, sys, time, random, mmap

          PATH = sys.argv[1] if len(sys.argv) > 1 else "/var/tmp/iops_probe.bin"
          BS = 4096                 # 4 KiB ops ~= 1 device IOP
          FILE_BYTES = 4 * 1024 * 1024   # small: bounded lay-down even at 100 IOPS
          BLOCKS = FILE_BYTES // BS
          PHASE_SECS = 10           # duration of each timed pass

          def open_direct(path, write):
              flags = os.O_RDWR | os.O_CREAT
              # O_DIRECT / O_DSYNC are Linux-only names; getattr keeps the probe
              # import-safe on platforms that lack them (falls back to 0 = no-op).
              o_direct = getattr(os, "O_DIRECT", 0)
              o_dsync = getattr(os, "O_DSYNC", 0)
              direct = False
              # Prefer O_DIRECT|O_DSYNC so every op hits the device. Fall back
              # gracefully if the filesystem rejects O_DIRECT (EINVAL).
              try:
                  fd = os.open(path, flags | o_direct | o_dsync)
                  direct = bool(o_direct)
              except OSError:
                  try:
                      fd = os.open(path, flags | o_dsync)
                  except OSError:
                      fd = os.open(path, flags)
              return fd, direct

          def aligned(nbytes, fill):
              # mmap pages are page-aligned (required for O_DIRECT buffers).
              buf = mmap.mmap(-1, nbytes)
              buf.write(fill * nbytes)
              buf.seek(0)
              return buf

          def pct(samples, p):
              if not samples:
                  return 0.0
              s = sorted(samples)
              idx = min(len(s) - 1, int(round((p / 100.0) * len(s) + 0.5)) - 1)
              return s[max(0, idx)] * 1000.0   # seconds -> ms

          # Deterministic offset sequence (no network, no /dev/urandom needed).
          rnd = random.Random(1234)

          fd, _ = open_direct(PATH, True)
          try:
              # Lay the file down once so reads hit allocated blocks. O_DIRECT
              # makes even this small file force real device reads later.
              wbuf = aligned(BS, b"\\xab")
              for b in range(BLOCKS):
                  os.pwrite(fd, wbuf, b * BS)

              # ---- timed random WRITE pass ----
              wlat = []
              end = time.time() + PHASE_SECS
              wcount = 0
              while time.time() < end:
                  off = rnd.randrange(BLOCKS) * BS
                  t0 = time.time()
                  os.pwrite(fd, wbuf, off)
                  wlat.append(time.time() - t0)
                  wcount += 1
              wsecs = sum(wlat) if wlat else PHASE_SECS
              # Use wall time of the phase for IOPS (includes everything).
              wiops = wcount / PHASE_SECS

              # ---- timed random READ pass ----
              rlat = []
              end = time.time() + PHASE_SECS
              rcount = 0
              while time.time() < end:
                  off = rnd.randrange(BLOCKS) * BS
                  t0 = time.time()
                  os.pread(fd, BS, off)
                  rlat.append(time.time() - t0)
                  rcount += 1
              riops = rcount / PHASE_SECS
          finally:
              os.close(fd)
              try:
                  os.unlink(PATH)
              except OSError:
                  pass

          bw_kib = (wiops * BS) / 1024.0
          print("%.1f,%.3f,%.3f,%.1f,%.3f,%.3f,%.1f" % (
              wiops, pct(wlat, 95), pct(wlat, 99),
              riops, pct(rlat, 95), pct(rlat, 99), bw_kib))

    - name: Run the offline probe
      ansible.builtin.command: "python3 {{ probe_py }} {{ probe_file }}"
      register: probe
      changed_when: false

    - name: Record the result on the guest (label + cap + measurements)
      ansible.builtin.copy:
        dest: "{{ result_file }}"
        content: "{{ bench_label }},{{ iops_cap }},{{ probe.stdout_lines[-1] | trim }}\\n"
        mode: "0644"

    - name: Fetch the result back to the Jenkins agent
      ansible.builtin.fetch:
        src: "{{ result_file }}"
        dest: "/tmp/iops-bench-{{ bench_label }}.csvline"
        flat: true
YAML_EOF
        echo "Wrote offline probe playbook -> ${PB_DIR}/disk_iops_benchmark.yml"
    '''
}

// Sequential IOPS sweep, OFFLINE. For each OS (one VM at a time) and each IOPS
// value, export QEMU_IOPS so create.yml throttles the guest disk, then run
// create/converge(probe)/destroy. converge runs the local disk benchmark, NOT
// a PS install, so no bytes cross the network. The probe result is fetched to
// /tmp/iops-bench-<os>-<iops>.csvline on the agent and read back here.
//
// Returns per-run maps: [os, iops, status, writeIops, wP95, wP99, readIops,
// rP95, rP99, bwKib].
def moleculeIopsBenchSweep(operatingSystems, iopsValues, venvDir) {
    def results = []
    operatingSystems.each { os ->
        iopsValues.each { iops ->
            stage("${os} @ ${iops} IOPS") {
                def label = "${os}-${iops}"
                def resultFile = "/tmp/iops-bench-${label}.csvline"
                def runFailed = false
                sh "rm -f ${resultFile} || true"
                try {
                    // set +e so a create/probe failure never skips teardown; the
                    // explicit test at the end re-propagates failure to Jenkins.
                    sh """
                        set +e
                        . ${venvDir}/bin/activate
                        cd ${chaosMoleculeDir()}
                        export QEMU_IOPS=${iops}
                        echo "===== ${os}: OFFLINE disk probe, guest disk throttled to ${iops} IOPS ====="

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
                            # Pass the label + cap into the probe playbook as
                            # extra-vars (everything after -- goes to ansible).
                            molecule converge -s ${os} -- \
                                -e bench_label=${label} -e iops_cap=${iops}
                            vrc=\$?
                        fi

                        molecule destroy -s ${os} || true

                        [ \$crc -eq 0 ] && [ \$vrc -eq 0 ]
                        exit \$?
                    """
                } catch (err) {
                    runFailed = true
                    echo "Run ${os} @ ${iops} IOPS failed: ${err}"
                }

                // Read back the probe result the guest produced and the fetch
                // module copied to the agent. Missing/short -> zeros + FAIL.
                def m = [writeIops: '0', wP95: '0', wP99: '0',
                         readIops: '0', rP95: '0', rP99: '0', bwKib: '0']
                if (fileExists(resultFile)) {
                    // line: label,cap,wiops,wp95,wp99,riops,rp95,rp99,bw
                    def parts = readFile(resultFile).trim().split(',')
                    if (parts.size() >= 9) {
                        m.writeIops = parts[2]; m.wP95 = parts[3]; m.wP99 = parts[4]
                        m.readIops  = parts[5]; m.rP95 = parts[6]; m.rP99 = parts[7]
                        m.bwKib     = parts[8]
                    } else {
                        runFailed = true
                    }
                } else {
                    runFailed = true
                }

                def status = runFailed ? 'FAIL' : 'PASS'
                results << [os: os, iops: iops, status: status] + m
                echo "RESULT ${os} @ ${iops} IOPS -> ${status} | " +
                     "write ${m.writeIops} iops (p99 ${m.wP99}ms) | " +
                     "read ${m.readIops} iops (p99 ${m.rP99}ms) | bw ${m.bwKib} KiB/s"
            }
        }
    }
    return results
}

// Render the sweep results as a human-readable table and a CSV, print the
// table, write + archive the CSV so the IOPS curve can be graphed.
def reportIopsResults(results) {
    if (!results) {
        echo "No IOPS runs were executed - nothing to report."
        return
    }
    // Sandbox-safe: only GString interpolation and String += (no StringBuilder
    // <<, no String.format/padRight).
    def table = "\n===== OFFLINE IOPS BENCHMARK SUMMARY =====\n"
    def csv = "os,iops_cap,status,write_iops,write_p95_ms,write_p99_ms," +
              "read_iops,read_p95_ms,read_p99_ms,write_bw_kib_s\n"
    results.each { r ->
        table += "${r.os} | cap ${r.iops} | ${r.status} | " +
                 "W ${r.writeIops} iops (p95 ${r.wP95} p99 ${r.wP99} ms) | " +
                 "R ${r.readIops} iops (p95 ${r.rP95} p99 ${r.rP99} ms) | " +
                 "bw ${r.bwKib} KiB/s\n"
        csv += "${r.os},${r.iops},${r.status},${r.writeIops},${r.wP95},${r.wP99}," +
               "${r.readIops},${r.rP95},${r.rP99},${r.bwKib}\n"
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

// Clone/refresh the package-testing repo (needed only for the molecule chaos
// scenario + shared create/destroy wiring; the converge WORKLOAD is the offline
// probe playbook this pipeline writes into its playbooks/ dir).
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
        // converge imports playbooks/${PLAYBOOK_VAR}.yml -- point it at the
        // offline probe this pipeline writes, NOT a PS-install playbook.
        PLAYBOOK_VAR = "disk_iops_benchmark"
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
        stage('Write offline probe playbook') {
            steps {
                script { writeBenchPlaybook() }
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
                    echo "Sweeping IOPS ${iopsValues.join(', ')} over scenarios: ${osList.join(', ')} (one VM at a time, OFFLINE)"

                    def results = moleculeIopsBenchSweep(osList, iopsValues, "${VENV_DIR}")

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
