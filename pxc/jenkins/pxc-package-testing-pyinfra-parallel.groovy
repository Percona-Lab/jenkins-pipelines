def non_pro_pxc80 = [
    'ubuntu-noble',
    'ubuntu-jammy',
    'ubuntu-noble-arm',
    'ubuntu-jammy-arm',
    'debian-12',
    'debian-11',
    'debian-12-arm',
    'debian-11-arm',
    'ol-8',
    'ol-9',
    'rhel-8',
    'rhel-9',
    'rhel-8-arm',
    'rhel-9-arm',
    'rocky-linux-8',
    'rocky-linux-8-arm',
    'rocky-linux-9',
    'rocky-linux-9-arm',
    'amazon-linux-2023',
    'amazon-linux-2023-arm'
]

def non_pro_pxc84 = [
    'ubuntu-noble',
    'ubuntu-jammy',
    'ubuntu-noble-arm',
    'ubuntu-jammy-arm',
    'debian-13',
    'debian-12',
    'debian-11',
    'debian-13-arm',
    'debian-12-arm',
    'debian-11-arm',
    'ol-8',
    'ol-9',
    'rhel-8',
    'rhel-9',
    'rhel-10',
    'rhel-8-arm',
    'rhel-9-arm',
    'rhel-10-arm',
    'rocky-linux-8',
    'rocky-linux-8-arm',
    'rocky-linux-9',
    'rocky-linux-9-arm',
    'amazon-linux-2023',
    'amazon-linux-2023-arm'
]

def all_possible_nodes = (non_pro_pxc80 + non_pro_pxc84).unique()

void runNodeBuild(String node_to_test) {
    build(
        job: 'pxc-package-testing-pyinfra',
        parameters: [
            string(name: 'product_to_test', value: params.product_to_test),
            string(name: 'node_to_test', value: node_to_test),
            string(name: 'test_repo', value: params.test_repo),
            string(name: 'test_type', value: 'install'),
            string(name: 'git_repo', value: params.git_repo),
            string(name: 'BRANCH', value: params.BRANCH)
        ],
        propagate: true,
        wait: true
    )
}

properties([
    parameters([
        [
            $class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'PXC product to test',
            name: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: 'return ["pxc84", "pxc80"]'
                ]
            ]
        ],
        choice(
            name: 'test_repo',
            choices: ['testing', 'main', 'experimental'],
            description: 'Repo to install packages from'
        ),
        string(
            defaultValue: 'Percona-QA/package-testing',
            description: 'Git repository to use for testing',
            name: 'git_repo',
            trim: false
        ),
        string(
            defaultValue: 'master',
            description: 'Git branch to use for testing',
            name: 'BRANCH',
            trim: false
        )
    ])
])

pipeline {
    agent {
        label 'docker'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
    }

    stages {
        stage('Show Configuration') {
            steps {
                script {
                    echo "=== Test Configuration ==="
                    echo "Product: ${params.product_to_test}"
                    echo "Test Repo: ${params.test_repo}"
                    echo "Test Type: install"
                    echo "Git Repo: ${params.git_repo}"
                    echo "Branch: ${params.BRANCH}"
                }
            }
        }

        stage('Prepare') {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.product_to_test}-${params.test_repo}-install"
                }
            }
        }

        stage('Run parallel') {
            steps {
                script {
                    def selectedNodes
                    def jobType

                    if (params.product_to_test == 'pxc80') {
                        selectedNodes = non_pro_pxc80
                        jobType = 'PXC 8.0 Non Pro (pyinfra)'
                    } else if (params.product_to_test == 'pxc84') {
                        selectedNodes = non_pro_pxc84
                        jobType = 'PXC 8.4 Non Pro (pyinfra)'
                    } else {
                        error("Unsupported product_to_test: ${params.product_to_test}")
                    }

                    echo "Testing ${selectedNodes.size()}/${all_possible_nodes.size()} nodes for ${jobType} job"

                    def parallelStages = [:]

                    all_possible_nodes.each { node ->
                        parallelStages[node] = {
                            stage("${node}") {
                                if (selectedNodes.contains(node)) {
                                    echo "Running ${jobType} tests for: ${node}"
                                    runNodeBuild(node)
                                } else {
                                    echo "SKIPPED: ${node} - not in ${jobType} job"
                                }
                            }
                        }
                    }

                    parallel parallelStages
                }
            }
        }
    }
}
