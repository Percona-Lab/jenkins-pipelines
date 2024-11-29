library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void run_package_tests(String GIT_BRANCH, String TESTS, String INSTALL_REPO)
{
    deleteDir()
    git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-QA/package-testing'
    sh '''
        export install_repo=\${INSTALL_REPO}
        export TARBALL_LINK=\${TARBALL}
        git clone https://github.com/Percona-QA/ppg-testing
        ansible-playbook \
        -vvv \
        --connection=local \
        --inventory 127.0.0.1, \
        --limit 127.0.0.1 playbooks/\${TESTS}.yml
    '''
}

void runStaging(String DOCKER_VERSION, CLIENTS) {
    stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: '3-dev-latest'),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_ENABLE_TELEMETRY=false -e PMM_DATA_RETENTION=48h -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com:443 -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX'),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.PMM_SERVER_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.ADMIN_PASSWORD = stagingJob.buildVariables.ADMIN_PASSWORD
    env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${VM_IP}"
}

def matrixVariant = generateVariants();

def generateVariants() {
    def results = new HashMap<>();
    def labels = ["min-bookworm-arm64", "min-bullseye-arm64", "min-noble-arm64", "min-jammy-arm64", "min-focal-arm64", "min-ol-9-arm64", "min-ol-8-arm64"]
    def playbooks = ["pmm3-client_integration", "pmm3-client_integration_custom_path", "pmm3-client_integration_custom_port"]

    for(label in labels) {
        for(playbook in playbooks) {
            results.put("${label}-${playbook}", generateStage(label, playbook))
        }
    }

    println results

    return results;
}

def generateStage(LABEL, PLAYBOOK) {
    return {
        stage("${LABEL}-${PLAYBOOK}") {
            agent {
                label "${LABEL}"
            }
            node(LABEL) {
                run_package_tests(
                    GIT_BRANCH,
                    PLAYBOOK,
                    INSTALL_REPO,
                )
            }

//             steps {
//                 run_package_tests(
//                     GIT_BRANCH,
//                     PLAYBOOK,
//                     INSTALL_REPO,
//                 )
//             }
        }
    }
}

void setup_rhel_package_tests()
{
    sh '''
        DISTRIBUTION=$(lsb_release -a | grep Description)
        echo "$DISTRIBUTION"
    '''
}

def latestVersion = pmmVersion()

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for package-testing repository',
            name: 'GIT_BRANCH',
            trim: true)
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH',
            trim: true)
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION',
            trim: true)
        string(
            defaultValue: latestVersion,
            description: 'PMM Version for testing',
            name: 'PMM_VERSION',
            trim: true)
        choice(
            choices: ['experimental', 'testing', 'main', 'pmm-client-main'],
            description: 'Enable Repo for Client Nodes',
            name: 'INSTALL_REPO')
        string(
            defaultValue: '',
            description: 'PMM Client tarball link or FB-code',
            name: 'TARBALL')
        choice(
            choices: ['auto', 'push', 'pull'],
            description: 'Select the Metrics Mode for Client',
            name: 'METRICS_MODE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 4 * * *')
    }
    stages {
        stage('Setup Server Instance') {
            steps {
                runStaging(DOCKER_VERSION, '--help')
            }
        }
        stage('Package tests matrix') {
            steps {
                script {
                    parallel matrixVariant
                }
            }
        }
    }
    post {
        always {
            script {
                if(env.VM_NAME)
                {
                    archiveArtifacts artifacts: 'logs.zip'
                    destroyStaging(VM_NAME)
                }
            }
            deleteDir()
        }
    }
}
