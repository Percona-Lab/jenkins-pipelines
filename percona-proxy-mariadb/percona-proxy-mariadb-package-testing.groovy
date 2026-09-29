library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Runs BUILD/percona/verify_packages.sh from the branch being tested against the packages in a
// repo.percona.com component. The script installs percona-proxy-mariadb the way a user would,
// with "percona-release enable <product> <component>", in a container of each platform, and
// checks that Percona Proxy routes queries through two real MariaDB servers, that the migration
// path from MariaDB MaxScale works, and that runtime administration is persisted.
void verifyPlatforms(String PLATFORMS, String PORT_BASE) {
    cleanUpWS()
    // The repository is private, so the script is taken from a shallow clone with a token
    // rather than from raw.githubusercontent.com, which does not accept one.
    withCredentials([string(credentialsId: params.GIT_TOKEN_ID, variable: 'GIT_TOKEN')]) {
        sh """
            set -o errexit
            GIT_REPO_URL='${params.GIT_REPO}'
            AUTH_REPO="https://\${GIT_TOKEN}@\${GIT_REPO_URL#https://}"
            rm -rf verify-src
            git clone --quiet --depth 1 --branch '${params.BRANCH}' "\${AUTH_REPO}" verify-src
            cp verify-src/BUILD/percona/verify_packages.sh .
            rm -rf verify-src
            chmod +x verify_packages.sh
        """
    }
    sh """
        set -o xtrace
        export REPO_PRODUCT='${params.REPO_PRODUCT}'
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
            defaultValue: 'percona-proxy',
            description: 'Product name percona-release enables, as in "percona-release enable <product> <component>"',
            name: 'REPO_PRODUCT')
        string(
            defaultValue: '1.0.0',
            description: 'Version the installed packages must report',
            name: 'VERSION')
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/percona-proxy-mariadb.git',
            description: 'Repository providing BUILD/percona/verify_packages.sh',
            name: 'GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch to take the test script from',
            name: 'BRANCH')
        string(
            defaultValue: 'GITHUB_API_TOKEN',
            description: 'Secret text credential holding a GitHub token that can read GIT_REPO. The repository is private',
            name: 'GIT_TOKEN_ID')
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
                    ['GIT_REPO', 'BRANCH', 'GIT_TOKEN_ID', 'VERSION', 'REPO_PRODUCT',
                     'RPM_PLATFORMS', 'DEB_PLATFORMS'].each { name ->
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
