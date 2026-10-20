library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Runs BUILD/percona/verify_packages.sh (from the MaxScale branch being tested) against the
// packages in a repo.percona.com component. The script installs percona-maxscale the way a
// user would, with "percona-release enable maxscale <component>", in a container of each
// platform, and checks that MaxScale routes queries through two real MariaDB servers.
void verifyPlatforms(String PLATFORMS, String PORT_BASE) {
    cleanUpWS()
    sh """
        set -o xtrace
        wget \$(echo ${params.GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${params.BRANCH}/BUILD/percona/verify_packages.sh -O verify_packages.sh
        chmod +x verify_packages.sh
        ./verify_packages.sh --repo-component=${params.COMPONENT} --version=${params.VERSION} \
            --platforms="${PLATFORMS}" --port-base=${PORT_BASE}
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
        choice(
            choices: 'experimental\ntesting\nlaboratory\nrelease',
            description: 'Repository component to install the packages from',
            name: 'COMPONENT')
        string(
            defaultValue: '23.08.12',
            description: 'Version the installed packages must report',
            name: 'VERSION')
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/MaxScale.git',
            description: 'URL for MaxScale repository (provides BUILD/percona/verify_packages.sh)',
            name: 'GIT_REPO')
        string(
            defaultValue: 'percona-23.08',
            description: 'Tag/Branch for MaxScale repository',
            name: 'BRANCH')
        string(
            defaultValue: 'el8 el9 el10 amzn2023',
            description: 'RPM platforms to verify',
            name: 'RPM_PLATFORMS')
        string(
            defaultValue: 'bookworm trixie',
            description: 'DEB platforms to verify. Ubuntu (jammy, noble) once the repository carries them',
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
                    ['GIT_REPO', 'BRANCH', 'VERSION', 'RPM_PLATFORMS', 'DEB_PLATFORMS'].each { name ->
                        if (!(params[name] ==~ /[A-Za-z0-9._\/:@+ -]*/)) {
                            error("Parameter ${name} contains characters that are not allowed")
                        }
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
                    when {
                        expression { params.RPM_PLATFORMS?.trim() }
                    }
                    steps {
                        // The port base keeps the two stages apart if they share a machine.
                        verifyPlatforms(params.RPM_PLATFORMS, '3000')
                    }
                }
                stage('DEB platforms') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    when {
                        expression { params.DEB_PLATFORMS?.trim() }
                    }
                    steps {
                        verifyPlatforms(params.DEB_PLATFORMS, '3100')
                    }
                }
            }  //parallel
        } // stage
    }
    post {
        success {
            script {
                currentBuild.description = "${params.COMPONENT} ${params.VERSION}: ${params.RPM_PLATFORMS} ${params.DEB_PLATFORMS} - [${BUILD_URL}]"
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
