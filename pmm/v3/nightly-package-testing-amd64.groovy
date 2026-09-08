library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// agent-none variant of pmm3-package-testing-amd64, for pmm3-nightly-orchestrator.
//
// The original holds an agent-amd64-ondemand executor across
// `build job: 'pmm3-aws-staging-start'` — and that job runs on
// agent-amd64-ondemand too. With eight of these in a matrix against a five
// executor pool, every executor ends up held by a parent waiting for a child
// that can never be scheduled. That is the deadlock captured in
// pmm3-rc-testing #34.
//
// Here the staging call runs on the flyweight executor, so no agent is held
// while waiting. Agents are taken only where a shell actually runs: one short
// stage to point the server at itself, the eight OS stages that run the
// playbooks, and the teardown.

// Same default as pmm3-package-testing-*: the playbook runs PMM_VERSION
// through regex_search, and an empty value there becomes None, not ''.
def latestVersion = pmmVersion('v3').last()

properties([
    buildDiscarder(logRotator(numToKeepStr: '30')),
    parameters([
        string(defaultValue: 'main', description: 'Tag/Branch for pmm-qa repository', name: 'GIT_BRANCH', trim: true),
        string(defaultValue: 'perconalab/pmm-server:3-dev-latest', description: 'PMM Server docker container version (image-name:version-tag)', name: 'DOCKER_VERSION', trim: true),
        choice(choices: ['amd64', 'arm64'], description: 'Architecture of the PMM server staging VM', name: 'SERVER_ARCH'),
        string(defaultValue: latestVersion, description: 'PMM Version for testing', name: 'PMM_VERSION', trim: true),
        string(defaultValue: 'pmm3-client_integration', description: 'Name of the playbook, e.g. pmm3-client_integration', name: 'TESTS', trim: true),
        choice(choices: ['experimental', 'testing', 'release'], description: 'Enable repo for client nodes', name: 'INSTALL_REPO'),
        string(defaultValue: '', description: 'PMM Client (x64) tarball link or FB-code', name: 'TARBALL'),
        string(defaultValue: 'pmm3admin!', description: 'Password for the pmm server admin user', name: 'ADMIN_PASSWORD'),
        string(defaultValue: '--help', description: 'Flag for pmm framework', name: 'CLIENTS'),
        choice(choices: ['auto', 'push', 'pull'], description: 'Metrics mode for the client', name: 'METRICS_MODE'),
        booleanParam(defaultValue: false, description: 'Use on-demand instances instead of spot', name: 'USE_ONDEMAND'),
    ]),
])

def onDemand(String label) {
    return params.USE_ONDEMAND ? "${label}-ondemand" : label
}

def setupRhel() {
    sh '''
        sudo dnf install -y epel-release
        sudo dnf -y update
        sudo dnf install -y ansible-core git wget dpkg
    '''
}

def setupRhel10() {
    sh '''
        sudo dnf config-manager --set-enabled crb
        sudo dnf clean all && dnf makecache
        sudo dnf -y install https://dl.fedoraproject.org/pub/epel/epel-release-latest-10.noarch.rpm
        sudo dnf -y update
        sudo dnf install -y ansible-core git wget
    '''
}

def setupDebian() {
    sh '''
        sudo apt-get install -y dirmngr gnupg2
        echo "deb http://ppa.launchpad.net/ansible/ansible/ubuntu trusty main" | sudo tee -a /etc/apt/sources.list > /dev/null
        sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 93C4A3FD7BB9C367
        sudo apt update -y
        sudo apt-get install -y ansible git wget
    '''
}

def setupTrixie() {
    sh '''
        set -e
        sudo apt-get update -y
        sudo apt-get install -y gpg wget dirmngr gnupg2 git ansible
    '''
}

def setupUbuntu() {
    sh '''
        sudo apt update -y
        sudo apt install -y software-properties-common
        sudo apt-add-repository --yes --update ppa:ansible/ansible
        sudo apt-get install -y ansible git wget
    '''
}

def runPlaybook() {
    deleteDir()
    git poll: false, branch: params.GIT_BRANCH, url: 'https://github.com/percona/pmm-qa'
    sh """
        export install_repo=${params.INSTALL_REPO}
        export TARBALL_LINK=${params.TARBALL}
        ls package_tests/
        ansible-playbook \
        -vvvvv \
        --connection=local \
        --inventory 127.0.0.1, \
        --limit 127.0.0.1 package_tests/${params.TESTS}.yml
    """
}

// `family` selects the setup steps by name. A Closure parameter would be a
// MethodClosure here, which the CPS interpreter cannot serialize.
def osStage(String name, String label, String family) {
    return {
        stage(name) {
            node(onDemand(label)) {
                timeout(time: 90, unit: 'MINUTES') {
                    if (family == 'rhel') {
                        setupRhel()
                    } else if (family == 'rhel10') {
                        setupRhel10()
                    } else if (family == 'ubuntu') {
                        setupUbuntu()
                    } else if (family == 'debian') {
                        setupDebian()
                    } else if (family == 'trixie') {
                        setupTrixie()
                    } else {
                        error("unknown setup family: ${family}")
                    }
                    runPlaybook()
                }
            }
        }
    }
}

timestamps {
    def vmName
    def vmIp
    def pmmUrl

    try {
        stage('Start staging server') {
            // Flyweight: no agent is held while pmm3-aws-staging-start runs, so
            // it can never be starved by the jobs waiting on it.
            def stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
                string(name: 'DOCKER_VERSION',       value: params.DOCKER_VERSION),
                string(name: 'SERVER_ARCH',          value: params.SERVER_ARCH),
                string(name: 'CLIENT_VERSION',       value: '3-dev-latest'),
                string(name: 'DOCKER_ENV_VARIABLE',  value: '-e PMM_ENABLE_TELEMETRY=0 -e PMM_DATA_RETENTION=48h -e PMM_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com:443 -e PMM_ENABLE_NOMAD=1'),
                string(name: 'CLIENTS',              value: params.CLIENTS),
                string(name: 'ADMIN_PASSWORD',       value: params.ADMIN_PASSWORD),
                string(name: 'NOTIFY',               value: 'false'),
                string(name: 'DAYS',                 value: '1'),
                booleanParam(name: 'USE_ONDEMAND',   value: params.USE_ONDEMAND),
            ]
            vmIp = stagingJob.buildVariables.IP
            vmName = stagingJob.buildVariables.VM_NAME
            // The playbooks read these from the environment and silently fall
            // back to 127.0.0.1/admin when they are unset.
            env.PMM_SERVER_IP = vmIp
            env.ADMIN_PASSWORD = stagingJob.buildVariables.ADMIN_PASSWORD
            pmmUrl = "https://admin:${params.ADMIN_PASSWORD}@${vmIp}"
            currentBuild.description = "${params.TESTS} on ${params.SERVER_ARCH} — server ${vmIp}"
            echo "staging server ${vmName} at ${vmIp} (${stagingJob.absoluteUrl})"
        }

        stage('Point server at itself') {
            // 'docker' rather than a min-* label: these two orchestration
            // stages only curl and archive, and min-noble-x64 is already the
            // Ubuntu 24.04 test target. agent-amd64 is the pool the sixteen
            // pmm3-aws-staging-start children queue on, so it stays clear too.
            node(onDemand('docker')) {
                try {
                    sh """
                        curl -k --location --request PUT "https://${vmIp}/v1/server/settings" \
                        --header 'Content-Type: application/json' \
                        --user admin:${params.ADMIN_PASSWORD} \
                        --data '{"pmm_public_address": "${vmIp}"}'
                    """
                } finally {
                    deleteDir()
                }
            }
        }

        stage('Package tests') {
            parallel([
                'Oracle Linux 8'   : osStage('Oracle Linux 8', 'min-ol-8-x64', 'rhel'),
                'Oracle Linux 9'   : osStage('Oracle Linux 9', 'min-ol-9-x64', 'rhel'),
                'Almalinux 10'     : osStage('Almalinux 10', 'min-alma-10-x64', 'rhel10'),
                'Ubuntu 22.04'     : osStage('Ubuntu 22.04', 'min-jammy-x64', 'ubuntu'),
                'Ubuntu 24.04'     : osStage('Ubuntu 24.04', 'min-noble-x64', 'ubuntu'),
                'Ubuntu 26.04'     : osStage('Ubuntu 26.04', 'min-resolute-x64', 'ubuntu'),
                'Debian 12'        : osStage('Debian 12', 'min-bookworm-x64', 'debian'),
                'Debian 13'        : osStage('Debian 13', 'min-trixie-x64', 'trixie'),
            ])
        }
    } finally {
        stage('Teardown') {
            if (vmName) {
                node(onDemand('docker')) {
                    try {
                        sh "curl --insecure ${pmmUrl}/logs.zip --output logs.zip || true"
                        archiveArtifacts artifacts: 'logs.zip', allowEmptyArchive: true
                    } finally {
                        deleteDir()
                    }
                }
                build job: 'aws-staging-stop', wait: true, propagate: false, parameters: [
                    booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND),
                    string(name: 'VM', value: vmName),
                ]
            }
        }
    }
}
