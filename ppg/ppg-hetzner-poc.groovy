library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(scenario, version) {
    if (currentBuild.result == "SUCCESS") {
        buildSummary = "Job: ${env.JOB_NAME}\nScenario: ${scenario}\nVersion: ${version}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
        slackSend color: "good", message: "${buildSummary}", channel: '#postgresql-test'
    } else {
        buildSummary = "Job: ${env.JOB_NAME}\nScenario: ${scenario}\nVersion: ${version}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
        slackSend color: "danger", message: "${buildSummary}", channel: '#postgresql-test'
    }
}


// Hetzner Cloud pilot: single-OS variant of ppg-multiOS-parallel running one
// molecule scenario on the factory snapshots (ppg/packer-hetzner) through the
// delegated driver. SCENARIO here is the molecule scenario name inside
// MOLECULE_DIR (ppg/pg-17), not the ppgScenarios() product name.
pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        string(
            defaultValue: 'ppg-17.10',
            description: 'PG version for test',
            name: 'VERSION'
        )
        string(
            defaultValue: 'rocky-9-hetzner',
            description: 'Molecule scenario (Hetzner delegated driver) inside ppg/pg-17',
            name: 'SCENARIO'
        )
        choice(
            name: 'ARCH',
            description: 'Server architecture: picks the hcloud server type and the snapshot arch label',
            choices: [
                'x86_64',
                'arm64'
            ]
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'https://github.com/Percona-QA/ppg-testing.git',
            description: 'Testing repository URL (override for fork-based validation)',
            name: 'TESTING_REPO'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (ppg-17) repo instead of ppg-17.0"
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "ppg/pg-17"
    }
    options {
        // No pipeline-wide credential binding: HCLOUD_TOKEN is scoped to the
        // Test and destroy sh blocks below, so the checkout and prepare of an
        // arbitrary ppg-testing fork never see it.
        buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '100', artifactNumToKeepStr: '10'))
        retry(conditions: [agent()], count: 2)
    }
    stages {
        stage('Validate parameters') {
            steps {
                script {
                    // Fail fast on anything that could smuggle shell into the
                    // sh blocks (SCENARIO) or point the checkout at a
                    // non-ppg-testing repository (TESTING_REPO).
                    if (!(params.SCENARIO ==~ /^[A-Za-z0-9._-]+$/)) {
                        error("SCENARIO '${params.SCENARIO}' is not a plain scenario name (allowed: A-Z a-z 0-9 . _ -)")
                    }
                    if (!(params.TESTING_REPO ==~ /^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/ppg-testing(\.git)?$/)) {
                        error("TESTING_REPO '${params.TESTING_REPO}' must be an https github.com ppg-testing repository (fork or upstream)")
                    }
                }
            }
        }
        stage('Set build name') {
            steps {
                script {
                    if (params.MAJOR_REPO) {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${env.ARCH}-${env.VERSION}-Major_Repo"
                    } else {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${env.ARCH}-${env.VERSION}"
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: TESTING_REPO
            }
        }
        stage('Prepare') {
            steps {
                script {
                    installMoleculePython39()
                }
                // The pip 'ansible' package already bundles both collections.
                // Prepare re-installs them at pinned releases, mirroring how
                // installMoleculePython39 refreshes amazon.aws for the EC2 path.
                sh '''
                    . virtenv/bin/activate
                    # Per-workspace collections dir: concurrent builds on one worker must
                    # not race each other's install + patch of a shared ~/.ansible copy.
                    # The Test and destroy sh blocks export the same path so runtime uses
                    # this patched copy.
                    export ANSIBLE_COLLECTIONS_PATH="$WORKSPACE/.collections"
                    # --force with a pinned 4.x: the venv's ansible bundle ships hetzner.hcloud 1.x
                    # (pre-rename hcloud_* module names), which galaxy otherwise reports as
                    # "already installed". 4.x is the newest line supporting ansible-core 2.15.
                    # community.crypto is pinned to the validated release too, so Prepare
                    # is reproducible instead of floating to whatever galaxy serves.
                    ansible-galaxy collection install --force 'hetzner.hcloud:4.3.0' 'community.crypto:3.3.0'
                    # The 2026 Hetzner API dropped the per-server datacenter field. Every
                    # collection release that handles it needs ansible-core >=2.17, which
                    # needs python >=3.10, and this venv is the canon python3.9/core-2.15
                    # stack. Guard the two result lines that dereference it, fail-closed:
                    # the asserts abort Prepare if a version bump moves the anchors.
                    python3 - <<'PYEOF'
import os
import pathlib
base = pathlib.Path(os.environ["ANSIBLE_COLLECTIONS_PATH"]) / "ansible_collections/hetzner/hcloud/plugins/modules"
for fname, obj in (("server.py", "self.hcloud_server"), ("server_info.py", "server")):
    f = base / fname
    s = f.read_text()
    dc_old = '"datacenter": %s.datacenter.name,' % obj
    dc_new = '"datacenter": %s.datacenter.name if %s.datacenter is not None else None,' % (obj, obj)
    loc_old = '"location": %s.datacenter.location.name,' % obj
    loc_new = '"location": %s.datacenter.location.name if %s.datacenter is not None else None,' % (obj, obj)
    assert s.count(dc_old) == 1 and s.count(loc_old) == 1, "datacenter guard anchors missing in " + fname
    f.write_text(s.replace(dc_old, dc_new).replace(loc_old, loc_new))
    print("patched", fname)
PYEOF
                '''
            }
        }
        stage('Test') {
            steps {
                script {
                    // HCLOUD_TOKEN scope starts here, after checkout + prepare.
                    // The only groovy interpolation is moleculeEnvPPGHetzner()
                    // (static library text): params reach the shell as quoted
                    // env vars (\$-escaped), never spliced into the script.
                    withCredentials(moleculeDistributionJenkinsCredsHetzner()) {
                        sh """
                            . virtenv/bin/activate
                            export ANSIBLE_COLLECTIONS_PATH="\$WORKSPACE/.collections"
                            cd "\$MOLECULE_DIR"
                            ${moleculeEnvPPGHetzner()}
                            export HETZNER_ARCH="\$ARCH"
                            if [ "\$ARCH" = "arm64" ]; then
                                export HETZNER_SERVER_TYPE="\${htz_server_type_arm64}"
                            else
                                export HETZNER_SERVER_TYPE="\${htz_server_type_x86_64}"
                            fi
                            molecule test -s "\$SCENARIO"
                        """
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                // A failed destroy must not eat the Slack notification: log it
                // and keep going (the hourly janitor sweeps any leftovers).
                try {
                    withCredentials(moleculeDistributionJenkinsCredsHetzner()) {
                        sh """
                            . virtenv/bin/activate
                            export ANSIBLE_COLLECTIONS_PATH="\$WORKSPACE/.collections"
                            cd "\$MOLECULE_DIR"
                            ${moleculeEnvPPGHetzner()}
                            export HETZNER_ARCH="\$ARCH"
                            molecule destroy -s "\$SCENARIO"
                        """
                    }
                } catch (err) {
                    echo "molecule destroy failed (janitor sweeps leftovers): ${err}"
                }
                sendSlackNotification(env.SCENARIO, env.VERSION)
            }
        }
    }
}
