
library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// ---------------------------------------------------------------------------
// ps-package-testing-molecule-chaos
//
// Runs the Percona-QA/package-testing molecule jobs on the "chaos-amd" metal
// node using local QEMU/KVM VMs (via panchal-yash/qemu-kvm-molecule) instead
// of the EC2 driver used by ps-package-testing-molecule.groovy.
//
//  - Parameters / PLAYBOOK_VAR logic  -> ps-package-testing-molecule.groovy
//  - QEMU setup / parallel molecule   -> workingsetup.groovy
//
// The qemu-kvm-molecule scenarios (molecule/<scenario>/) drive the package
// testing playbooks that live in ~/package-testing/playbooks. The pipeline
// exports PLAYBOOK_VAR (and the other package-testing env vars) so the shared
// converge playbook selects the correct product/action playbook.
// ---------------------------------------------------------------------------

// The qemu-kvm-molecule repo only exposes amd64 scenarios (no ARM on chaos-amd).
// Available scenarios: al2023, debian11, debian12, debian13, oraclelinux9,
// oraclelinux10, rocky9, rocky10, ubuntu22, ubuntu24.

def ps90PackageTesting() {
    return [
        'debian12',
        'debian13',
        'ubuntu22',
        'ubuntu24',
        'oraclelinux9',
        'oraclelinux10',
        'rocky9',
        'rocky10',
        'al2023'
    ]
}

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

def ps84PackageTesting() {
    return [
        'debian12',
        'debian13',
        'ubuntu22',
        'ubuntu24',
        'oraclelinux9',
        'oraclelinux10',
        'rocky9',
        'rocky10',
        'al2023'
    ]
}

def ps97PackageTesting() {
    return [
        'debian12',
        'debian13',
        'ubuntu22',
        'ubuntu24',
        'oraclelinux9',
        'oraclelinux10',
        'rocky9',
        'rocky10',
        'al2023'
    ]
}

def ps57PackageTesting() {
    return [
        'debian11',
        'ubuntu22',
        'oraclelinux9',
        'rocky9'
    ]
}

List allOS = (ps90PackageTesting() + ps80PackageTesting() + ps84PackageTesting() + ps57PackageTesting() + ps97PackageTesting()).unique()

// Run each scenario's create/converge/destroy cycle in parallel on the local
// QEMU host. Mirrors moleculeParallelTestALL() but uses the qemu venv and the
// create/converge/destroy lifecycle from workingsetup.groovy.
def moleculeParallelTestChaos(allOS, operatingSystems, venvDir) {
    def tests = [:]
    allOS.each { os ->
        tests["${os}"] = {
            stage("${os}") {
                if (operatingSystems.contains(os)) {
                    sh """
                        set -e
                        . ${venvDir}/bin/activate
                        molecule create   -s ${os}
                        molecule converge -s ${os}
                        molecule destroy  -s ${os}
                    """
                } else {
                    echo "Skipping ${os} as it's not in operatingSystems"
                }
            }
        }
    }
    parallel tests
}

// Kill leftover VMs / disks from previous runs on the shared metal node.
def cleanupStaleVMs() {
    sh '''
        set +e
        echo "Killing any running qemu-system processes..."
        sudo pkill -9 -f 'qemu-system-(x86_64|aarch64)' || true
        echo "Removing stale pidfiles, disks, cloud-init artifacts, and qemu logs..."
        sudo rm -f /tmp/qemu-*.pid
        sudo rm -f /tmp/qemu-*-serial.log /tmp/qemu-*-qemu.log /tmp/qemu-*-stderr.log
        sudo rm -f /tmp/qemu-*-vars.fd
        sudo rm -f /tmp/molecule-*.raw
        sudo rm -f /tmp/cloud-init-*.iso
        sudo rm -rf /tmp/cloud-init-*
        echo "Removing ansible caches (keeping ${HOME}/qemu-images intact)..."
        rm -rf ${HOME}/.ansible/tmp ${HOME}/.cache/molecule || true
        echo "Disk free after cleanup:"
        df -h /tmp ${HOME} || true
        echo "Remaining qemu processes (should be none):"
        pgrep -af 'qemu-system-(x86_64|aarch64)' || true
    '''
}

// Build the molecule + QEMU environment (venv, system packages, cloud images).
def installMoleculeChaos() {
    sh '''
        set -e
        bash setup.sh
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
            git -C "\${PT_DIR}" fetch --depth 1 origin "${gitBranch}"
            git -C "\${PT_DIR}" checkout -f "${gitBranch}"
            git -C "\${PT_DIR}" reset --hard "origin/${gitBranch}"
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
            description: 'Choose the product version to test: PS8.0 OR ps_innovation',
            name: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: 'return ["ps_57", "ps_80", "ps_84", "ps_innovation", "ps_97", "client_test"]'
                ]
            ]
        ],

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
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Action To Test',
            name: 'action_to_test',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "ps57") {
                            return ["install", "upgrade", "major_upgrade", "kmip", "kms"]
                        }
                        else if (product_to_test == "ps_80" || product_to_test == "ps_84" || product_to_test == "ps_97") {
                            return ["install", "upgrade", "major_upgrade", "kmip", "kms"]
                        }
                        else {
                            return ["install", "upgrade", "kmip", "kms"]
                        }
                    '''
                ]
            ]
        ],

        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Install Repo',
            name: 'install_repo',
            referencedParameters: 'action_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            return ["NA"]
                        }
                        else {
                            return ["testing", "main", "experimental"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'from',
            name: 'major_upgrade_from_product',
            referencedParameters: 'action_to_test,product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            if (product_to_test == "ps_80") {
                                return ["ps_80","ps_57", "ps_84"]
                            }
                            else if (product_to_test == "ps_84") {
                                return ["ps_80","ps_84"]
                            }
                            else if (product_to_test == "ps_57") {
                                return ["ps_57", "ps_80"]
                            }
                            else if (product_to_test == "ps_97") {
                                return ["ps_84"]
                            }
                            else {
                                return ["NA"]
                            }
                        }
                        else {
                            return ["NA"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'major upgrade from repo',
            name: 'major_upgrade_from_repo',
            referencedParameters: 'action_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            return ["testing", "main", "experimental"]
                        }
                        else {
                            return ["NA"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'from',
            name: 'major_upgrade_to_product',
            referencedParameters: 'action_to_test,product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            if (product_to_test == "ps_80") {
                                return ["ps_80","ps_57", "ps_84"]
                            }
                            else if (product_to_test == "ps_84") {
                                return ["ps_80","ps_84"]
                            }
                            else if (product_to_test == "ps_57") {
                                return ["ps_57", "ps_80"]
                            }
                            else if (product_to_test == "ps_97") {
                                return ["ps_97"]
                            }
                            else {
                                return ["NA"]
                            }
                        }
                        else {
                            return ["NA"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'major upgrade to repo',
            name: 'major_upgrade_to_repo',
            referencedParameters: 'action_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            return ["testing", "main", "experimental"]
                        }
                        else {
                            return ["NA"]
                        }
                    '''
                ]
            ]
        ],
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
        label 'chaos-amd'
    }
    environment {
        REPO_URL                     = 'https://github.com/panchal-yash/qemu-kvm-molecule.git'
        VENV_DIR                     = "${env.HOME}/.venv/molecule_qemu"
        ANSIBLE_DEPRECATION_WARNINGS = "False"
        product_to_test = "${params.product_to_test}"
        install_repo = "${params.install_repo}"
        action_to_test  = "${params.action_to_test}"
        check_warnings = "${params.check_warnings}"
        install_mysql_shell = "${params.install_mysql_shell}"
        EOL="yes"//PS 57 has default EOL to yes
        major_upgrade_from_product = "${params.major_upgrade_from_product}"
        major_upgrade_from_repo = "${params.major_upgrade_from_repo}"
        major_upgrade_to_product = "${params.major_upgrade_to_product}"
        major_upgrade_to_repo = "${params.major_upgrade_to_repo}"
        TESTING_BRANCH = "${params.git_branch}"
        TESTING_GIT_ACCOUNT = "${params.git_account}"
    }
    options {
        timeout(time: 6, unit: 'HOURS')
    }
        stages {
            stage('Set Build Name'){
                steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-${action_to_test}-chaos"
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
            stage('Checkout') {
                steps {
                    deleteDir()
                    git poll: false, branch: 'main', url: "${REPO_URL}"
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
            stage('RUN TESTS') {
                        steps {
                            script {
                                if (action_to_test == 'install') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}" > .env.ENV_VARS
                                    """
                                }
                                else if (action_to_test == 'upgrade') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_upgrade" > .env.ENV_VARS
                                    """
                                }
                                else if (action_to_test == 'major_upgrade')     {
                                    sh """
                                         echo PLAYBOOK_VAR="${product_to_test}_major_upgrade_to" > .env.ENV_VARS
                                    """
                                }
                                else {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_${action_to_test}" > .env.ENV_VARS
                                    """
                                }

                                sh """
                                    echo IIT_BILLING_TAG="${product_to_test}_package_testing" >> .env.ENV_VARS
                                """

                                def envMap = loadEnvFile('.env.ENV_VARS')

                                withEnv(envMap) {
                                    if (product_to_test == "ps_innovation") {
                                        moleculeParallelTestChaos(allOS, ps90PackageTesting(), "${VENV_DIR}")
                                    }
                                    else if (product_to_test == "ps_57") {
                                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                            moleculeParallelTestChaos(allOS, ps57PackageTesting(), "${VENV_DIR}")
                                        }
                                    }
                                    else if (product_to_test == "ps_80") {
                                        moleculeParallelTestChaos(allOS, ps80PackageTesting(), "${VENV_DIR}")
                                    }
                                    else if (product_to_test == "ps_84") {
                                        moleculeParallelTestChaos(allOS, ps84PackageTesting(), "${VENV_DIR}")
                                    }
                                    else if (product_to_test == "ps_97") {
                                        moleculeParallelTestChaos(allOS, ps97PackageTesting(), "${VENV_DIR}")
                                    }
                                    else {
                                        error("Unsupported product_to_test: ${product_to_test}")
                                    }
                                }
                            }
                        }
            }
        }
    post {
        always {
            script {
                // Best-effort teardown of every scenario in case a converge
                // failed before its destroy ran.
                sh """
                    if [ -d "${VENV_DIR}" ]; then
                        . ${VENV_DIR}/bin/activate
                        for s in ${allOS.join(' ')}; do
                            molecule destroy -s \${s} || true
                        done
                    fi
                """
                cleanupStaleVMs()
            }
            echo "Pipeline completed."
        }
    }
}
