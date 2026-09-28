library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Runs BUILD/percona/verify_packages.sh (from the MaxScale branch being tested) against the
// packages of one build. The script installs the packages of each platform in a container of
// that distribution and checks that MaxScale routes queries through two real MariaDB servers.
void verifyPlatforms(String PLATFORMS, String PORT_BASE, String FOLDER, String STASH_PATH) {
    cleanUpWS()
    popArtifactFolder(params.CLOUD, "${FOLDER}/", STASH_PATH)
    sh """
        set -o xtrace
        wget \$(echo ${params.GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${params.BRANCH}/BUILD/percona/verify_packages.sh -O verify_packages.sh
        chmod +x verify_packages.sh
        ./verify_packages.sh --packages=\$(pwd -P) --platforms="${PLATFORMS}" --port-base=${PORT_BASE}
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    parameters {
        choice(
            choices: [ 'Hetzner','AWS' ],
            description: 'Cloud infra for the test run',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/MaxScale.git',
            description: 'URL for MaxScale repository (provides BUILD/percona/verify_packages.sh)',
            name: 'GIT_REPO')
        string(
            defaultValue: 'percona-23.08',
            description: 'Tag/Branch for MaxScale repository',
            name: 'BRANCH')
        string(
            defaultValue: '',
            description: 'Path of the packages to test, as printed by the build job, e.g. BUILDS/percona-maxscale/percona-maxscale-23.08.12/percona-23.08/<revision>/<build number>',
            name: 'AWS_STASH_PATH')
        string(
            defaultValue: 'el8 el9 el10 amzn2023',
            description: 'RPM platforms to verify',
            name: 'RPM_PLATFORMS')
        string(
            defaultValue: 'jammy noble bookworm trixie',
            description: 'DEB platforms to verify',
            name: 'DEB_PLATFORMS')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Check parameters') {
            steps {
                script {
                    ['GIT_REPO', 'BRANCH', 'AWS_STASH_PATH', 'RPM_PLATFORMS', 'DEB_PLATFORMS'].each { name ->
                        if (!(params[name] ==~ /[A-Za-z0-9._\/:@+ -]*/)) {
                            error("Parameter ${name} contains characters that are not allowed")
                        }
                    }
                    if (!params.AWS_STASH_PATH?.trim()) {
                        error('AWS_STASH_PATH is empty; give the path the build job printed')
                    }
                }
            }
        }
        stage('Verify packages') {
            parallel {
                stage('RPM platforms') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        // The port base keeps the two stages apart if they share a machine.
                        verifyPlatforms(params.RPM_PLATFORMS, '3000', 'rpm', params.AWS_STASH_PATH)
                    }
                }
                stage('DEB platforms') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        verifyPlatforms(params.DEB_PLATFORMS, '3100', 'deb', params.AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
    }
    post {
        success {
            script {
                currentBuild.description = "Verified ${params.RPM_PLATFORMS} ${params.DEB_PLATFORMS} - [${BUILD_URL}]"
            }
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
