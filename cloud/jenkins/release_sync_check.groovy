def operatorMap = [
    'percona-server-mongodb-operator': 'psmdb',
    'percona-xtradb-cluster-operator': 'pxc',
    'percona-server-mysql-operator': 'ps',
    'percona-postgresql-operator': 'pg'
]

def checkoutIfBranchExists(String repoUrl, String branch, String targetDir) {
    def exists = sh(
        script: "git ls-remote --exit-code --heads ${repoUrl} ${branch} >/dev/null 2>&1",
        returnStatus: true
    ) == 0

    if (exists) {
        checkout([
            $class: 'GitSCM',
            branches: [[name: "*/${branch}"]],
            extensions: [
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CloneOption', depth: 1, noTags: true, shallow: true],
                [$class: 'RelativeTargetDirectory', relativeTargetDir: targetDir]
            ],
            userRemoteConfigs: [[url: repoUrl]]
        ])
    } else {
        echo "Branch '${branch}' does not exist in ${repoUrl} yet - skipping (${targetDir} will be empty, that check will be reported as skipped)"
        sh "mkdir -p ${targetDir}"
    }
    return exists
}

pipeline {
    agent {
        label 'docker-x64-min'
    }

    parameters {
        choice(
            name: 'OPERATOR',
            choices: ['percona-server-mongodb-operator', 'percona-xtradb-cluster-operator', 'percona-server-mysql-operator', 'percona-postgresql-operator'],
            description: 'Select the operator'
        )
        string(
            name: 'VERSION',
            defaultValue: '',
            description: 'Version being released (e.g., 1.2.3)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
        timeout(time: 10, unit: 'MINUTES')
    }

    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.VERSION || !(params.VERSION ==~ /^\d+\.\d+\.\d+$/)) {
                        error("VERSION must follow semantic versioning format (x.y.z). Provided: ${params.VERSION}")
                    }

                    def abbrev = operatorMap[params.OPERATOR]
                    env.ABBREV = abbrev
                    env.OPERATOR_REPO_URL = "https://github.com/percona/${params.OPERATOR}.git"
                    env.HELM_REPO_URL = 'https://github.com/percona/percona-helm-charts.git'
                    env.VS_REPO_URL = 'https://github.com/Percona-Lab/percona-version-service.git'

                    env.OPERATOR_BRANCH = "release-${params.VERSION}"
                    env.HELM_BRANCH = "release-${abbrev}-${params.VERSION}"
                    env.VS_BRANCH_NONPROD = "release-${abbrev}-${params.VERSION}"
                    env.VS_BRANCH_PROD = "release-${abbrev}-${params.VERSION}-prod"

                    echo "Checking sync for ${params.OPERATOR} (${abbrev}) version ${params.VERSION}"
                    echo "Operator branch: ${env.OPERATOR_BRANCH}"
                    echo "Helm charts branch: ${env.HELM_BRANCH}"
                    echo "Version service branches: ${env.VS_BRANCH_NONPROD}, ${env.VS_BRANCH_PROD}"
                }
            }
        }
        stage('Install dependencies') {
            steps {
                sh '''
                    curl -LsSf https://astral.sh/uv/install.sh | sh
                    export PATH="$HOME/.local/bin:$PATH"
                    uv init
                    uv add pyyaml

                    mkdir -p "$HOME/.local/bin"
                    curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
                    chmod +x get_helm.sh
                    USE_SUDO=false HELM_INSTALL_DIR="$HOME/.local/bin" ./get_helm.sh
                '''
            }
        }
        stage('Checkout repos') {
            steps {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${env.OPERATOR_BRANCH}"]],
                    extensions: [
                        [$class: 'CleanBeforeCheckout'],
                        [$class: 'CloneOption', depth: 1, noTags: true, shallow: true],
                        [$class: 'RelativeTargetDirectory', relativeTargetDir: 'operator-repo']
                    ],
                    userRemoteConfigs: [[url: env.OPERATOR_REPO_URL]]
                ])
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${env.HELM_BRANCH}"]],
                    extensions: [
                        [$class: 'CleanBeforeCheckout'],
                        [$class: 'CloneOption', depth: 1, noTags: true, shallow: true],
                        [$class: 'RelativeTargetDirectory', relativeTargetDir: 'helm-charts-repo']
                    ],
                    userRemoteConfigs: [[url: env.HELM_REPO_URL]]
                ])
                script {
                    checkoutIfBranchExists(env.VS_REPO_URL, env.VS_BRANCH_NONPROD, 'version-service-repo-nonprod')
                    checkoutIfBranchExists(env.VS_REPO_URL, env.VS_BRANCH_PROD, 'version-service-repo-prod')
                }
            }
        }
        stage('Check sync') {
            steps {
                script {
                    def result = sh(
                        script: '''
                            set -o pipefail
                            export PATH="$HOME/.local/bin:$PATH"
                            uv run cloud/scripts/check_release_sync.py "$ABBREV" "$VERSION" operator-repo helm-charts-repo version-service-repo-nonprod version-service-repo-prod | tee sync_report.txt
                        ''',
                        returnStatus: true
                    )
                    archiveArtifacts artifacts: 'sync_report.txt', allowEmptyArchive: false, fingerprint: true
                    env.SYNC_REPORT = readFile('sync_report.txt')

                    if (result != 0) {
                        error("Operator repo, helm-charts repo, version-service repo (CRDs/images/README/RBAC/Deployment) are OUT OF SYNC for ${params.OPERATOR} ${params.VERSION}. See sync_report.txt for details.")
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                def message = """
                    :white_check_mark: *Release Sync Check Passed*
                    *Version:* ${params.VERSION}
                    *Operator:* ${params.OPERATOR}
                    *Build:* ${env.BUILD_URL}
                """.stripIndent()

                //slackSend(channel: '#cloud-dev-ci', color: 'good', message: message)
            }
        }
        failure {
            script {
                def message = """
                    :x: *Release Sync Check Failed*
                    *Version:* ${params.VERSION}
                    *Operator:* ${params.OPERATOR}
                    *Build:* ${env.BUILD_URL}
                    ```
                    ${env.SYNC_REPORT ?: 'See build log for details.'}
                    ```
                """.stripIndent()

                //slackSend(channel: '#cloud-dev-ci', color: '#FF0000', message: message)
            }
        }
        always {
            deleteDir()
        }
    }
}
