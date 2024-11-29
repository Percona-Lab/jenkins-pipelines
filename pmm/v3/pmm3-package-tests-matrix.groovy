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

def matrixVariant = generateVariants();

def generateVariants() {
    def results = [];
    def labels = ["min-bookworm-arm64", "min-bullseye-arm64", "min-noble-arm64", "min-jammy-arm64", "min-focal-arm64", "min-ol-9-arm64", "min-ol-8-arm64"]
    def playbooks = ["pmm3-client_integration", "pmm3-client_integration_custom_path", "pmm3-client_integration_custom_port"]
    labels.each { labelsKey, labelsValue ->
        playbooks.each { playbooksKey, playbooksValue ->
            results.putAll([label: labelsValue, playbook: playbooksValue])
        }
    }

    println results

    return results;
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
