def operatorMap = [
    'percona-server-mongodb-operator': 'psmdb',
    'percona-xtradb-cluster-operator': 'pxc',
    'percona-server-mysql-operator': 'ps',
    'percona-postgresql-operator': 'pg'
]

def checkoutIfBranchExists(String repoUrl, String branch, String targetDir) {
    def exists = sh(
        script: "git ls-remote --exit-code --heads '${repoUrl}' 'refs/heads/${branch}' >/dev/null 2>&1",
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
        echo "Branch '${branch}' does not exist in ${repoUrl}; its check will be skipped"
        sh "mkdir -p '${targetDir}'"
    }
}

pipeline {
    agent {
        label 'docker-x64-min'
    }

    parameters {
        choice(
            name: 'OPERATOR',
            choices: [
                'percona-server-mongodb-operator',
                'percona-xtradb-cluster-operator',
                'percona-server-mysql-operator',
                'percona-postgresql-operator'
            ],
            description: 'Select the operator'
        )
        string(
            name: 'VERSION',
            defaultValue: '',
            description: 'Version being released (for example, 1.20.0)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
        timeout(time: 10, unit: 'MINUTES')
        skipDefaultCheckout()
    }

    stages {
        stage('Checkout Pipeline Repository') {
            steps {
                deleteDir()
                checkout scm
            }
        }

        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.VERSION || !(params.VERSION ==~ /^\d+\.\d+\.\d+$/)) {
                        error("VERSION must use x.y.z format. Provided: ${params.VERSION}")
                    }

                    env.ABBREV = operatorMap[params.OPERATOR]
                    env.OPERATOR_REPO_URL = "https://github.com/percona/${params.OPERATOR}.git"
                    env.HELM_REPO_URL = 'https://github.com/percona/percona-helm-charts.git'
                    env.VS_REPO_URL = 'https://github.com/Percona-Lab/percona-version-service.git'
                    env.OPERATOR_BRANCH = "release-${params.VERSION}"
                    env.HELM_BRANCH = "release-${env.ABBREV}-${params.VERSION}"
                    env.VS_BRANCH_NONPROD = "release-${env.ABBREV}-${params.VERSION}"
                    env.VS_BRANCH_PROD = "release-${env.ABBREV}-${params.VERSION}-prod"

                    echo "Operator branch: ${env.OPERATOR_BRANCH}"
                    echo "Helm branch: ${env.HELM_BRANCH}"
                    echo "Version Service branches: ${env.VS_BRANCH_NONPROD}, ${env.VS_BRANCH_PROD}"
                }
            }
        }

        stage('Install Dependencies') {
            steps {
                sh '''
                    export PATH="$HOME/.local/bin:$PATH"
                    mkdir -p "$HOME/.local/bin"

                    if ! command -v uv >/dev/null 2>&1; then
                        curl -LsSf https://astral.sh/uv/install.sh | sh
                    fi

                    if ! command -v helm >/dev/null 2>&1; then
                        curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
                        chmod +x get_helm.sh
                        USE_SUDO=false HELM_INSTALL_DIR="$HOME/.local/bin" ./get_helm.sh
                        rm -f get_helm.sh
                    fi
                '''
            }
        }

        stage('Checkout Release Repositories') {
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
                    checkoutIfBranchExists(
                        env.VS_REPO_URL,
                        env.VS_BRANCH_NONPROD,
                        'version-service-repo-nonprod'
                    )
                    checkoutIfBranchExists(
                        env.VS_REPO_URL,
                        env.VS_BRANCH_PROD,
                        'version-service-repo-prod'
                    )
                }
            }
        }

        stage('Confirm Release') {
            steps {
                script {
                    def result = sh(
                        script: '''
                            export PATH="$HOME/.local/bin:$PATH"
                            set +e
                            uv run --with pyyaml cloud/scripts/confirm-release.py \
                                "$ABBREV" \
                                "$VERSION" \
                                operator-repo \
                                helm-charts-repo \
                                version-service-repo-nonprod \
                                version-service-repo-prod \
                                > confirm-release-report.txt 2>&1
                            status=$?
                            cat confirm-release-report.txt
                            exit $status
                        ''',
                        returnStatus: true
                    )

                    archiveArtifacts(
                        artifacts: 'confirm-release-report.txt',
                        allowEmptyArchive: false,
                        fingerprint: true
                    )
                    env.CONFIRM_RELEASE_REPORT = readFile('confirm-release-report.txt')

                    if (result != 0) {
                        error(
                            "Release repositories are OUT OF SYNC for " +
                            "${params.OPERATOR} ${params.VERSION}. See confirm-release-report.txt."
                        )
                    }
                }
            }
        }
    }

    post {
        success {
            slackSend(
                channel: '#cloud-dev-ci',
                color: 'good',
                message: """:white_check_mark: *Confirm Release Passed*
*Version:* ${params.VERSION}
*Operator:* ${params.OPERATOR}
*Build:* ${env.BUILD_URL}"""
            )
        }
        failure {
            script {
                def report = (env.CONFIRM_RELEASE_REPORT ?: 'See build log for details.').take(12000)
                slackSend(
                    channel: '#cloud-dev-ci',
                    color: '#FF0000',
                    message: """:x: *Confirm Release Failed*
*Version:* ${params.VERSION}
*Operator:* ${params.OPERATOR}
*Build:* ${env.BUILD_URL}
```
${report}
```"""
                )
            }
        }
        always {
            deleteDir()
        }
    }
}
