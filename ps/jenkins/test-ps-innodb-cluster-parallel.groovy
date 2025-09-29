library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

List all_nodes = [
    'ubuntu-noble',
    'ubuntu-focal',
    'ubuntu-jammy',
    'debian-11',
    'debian-12',
    'oracle-8',
    'oracle-9',
    'rhel-8',
    'rhel-9',
    'rhel-10',
    'rhel-8-arm',
    'rhel-9-arm',
    'rhel-10-arm',
    'debian-11-arm',
    'debian-12-arm',
    'ubuntu-focal-arm',
    'ubuntu-jammy-arm',
    'ubuntu-noble-arm'
]

List TEST_DISTS = []
if (params.TEST_DIST == "all") {
    TEST_DISTS = all_nodes
} else {
    TEST_DISTS = [params.TEST_DIST]
}

void runNodeBuild(String TEST_DIST) {
    build(
        job: 'test-ps-innodb-cluster',
        parameters: [
            string(name: "PRODUCT_TO_TEST", value: params.PRODUCT_TO_TEST),
            string(name: "TEST_DIST", value: TEST_DIST),
            string(name: "INSTALL_REPO", value: params.INSTALL_REPO),          
        ],
        propagate: true,
        wait: true
    )
}

pipeline {
    agent {
        label 'docker'
    }
    environment {
        PRODUCT_TO_TEST = "${params.PRODUCT_TO_TEST}"
    }
    parameters {
        choice(
            choices: ['PS80','PS84','PS_LTS_INN'],
            description: 'Product for which the packages will be tested',
            name: 'PRODUCT_TO_TEST'
        )
        choice(
            name: 'TEST_DIST',
            choices: [
                'all',
                'ubuntu-noble',
                'ubuntu-focal',
                'ubuntu-jammy',
                'debian-11',
                'debian-12',
                'oracle-8',
                'oracle-9',
                'rhel-8',
                'rhel-9',
                'rhel-10', 
                'rhel-8-arm',
                'rhel-9-arm',
                'rhel-10-arm',
                'debian-11-arm',
                'debian-12-arm',
                'ubuntu-focal-arm',
                'ubuntu-jammy-arm',
                'ubuntu-noble-arm'
            ],
            description: 'Distribution to run test'
        )
        choice(
            name: 'INSTALL_REPO',
            choices: [
                'testing',
                'main',
                'experimental'
            ],
            description: 'Repo to install packages from'
        )
        string(
            name: 'git_repo',
            defaultValue: "Percona-QA/package-testing",
            description: 'Git repository to use for testing'
        )
        string(
            name: 'BRANCH',
            defaultValue: 'master',
            description: 'Git branch to use for testing'
        )
    }
    stages {
        stage('SET UPSTREAM_VERSION,PS_VERSION and PS_REVISION') {
            steps {
                script {
                    echo "PRODUCT_TO_TEST is: ${env.PRODUCT_TO_TEST}"
                    
                    sh """
                        echo "BRANCH is: \${BRANCH}"
                        echo "git_repo is: \${git_repo}"
                        rm -rf /tmp/package-testing
                        mkdir /tmp/package-testing
                        wget -O /tmp/package-testing/VERSIONS https://raw.githubusercontent.com/\${git_repo}/refs/heads/\${BRANCH}/VERSIONS
                    """

                    def UPSTREAM_VERSION = sh(
                        script: ''' 
                            grep ${PRODUCT_TO_TEST}_VER /tmp/package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/"//g' | awk -F- '{print \$1}'
                         ''',
                        returnStdout: true
                        ).trim()

                    def PS_VERSION = sh(
                        script: ''' 
                            grep ${PRODUCT_TO_TEST}_VER /tmp/package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/"//g' | awk -F- '{print \$2}'
                        ''',
                        returnStdout: true
                        ).trim()

                    def PS_REVISION = sh(
                        script: '''
                             grep ${PRODUCT_TO_TEST}_REV /tmp/package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/"//g' 
                        ''',
                        returnStdout: true
                        ).trim()
                    
                    
                    env.UPSTREAM_VERSION = UPSTREAM_VERSION
                    env.PS_VERSION = PS_VERSION
                    env.PS_REVISION = PS_REVISION

                    echo "UPSTREAM_VERSION fetched: ${env.UPSTREAM_VERSION}"
                    echo "PS_VERSION fetched: ${env.PS_VERSION}"
                    echo "PS_REVISION fetched: ${env.PS_REVISION}"

                }
            }
        }
        stage('Set environmental variable'){
            steps{
                 script {
                    // Now, you can access these global environment variables
                    echo "Using UPSTREAM_VERSION: ${env.UPSTREAM_VERSION}"
                    echo "Using PS_VERSION: ${env.PS_VERSION}"
                    echo "Using PS_REVISION: ${env.PS_REVISION}"
                }
            }
        }
        stage("Prepare") {
            steps {
	        script {
                   currentBuild.displayName = "#${BUILD_NUMBER}-${UPSTREAM_VERSION}-${PS_VERSION}"
                   currentBuild.description = "${PS_REVISION}-${INSTALL_REPO}"
                }
            }
        }

        stage("Run parallel") {
            parallel {
           
                stage("Ubuntu Noble") {
                    when {
                        expression {
                            TEST_DISTS.contains("ubuntu-noble")
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-noble")
                    }
                }

                stage("Ubuntu Focal") {
                    when {
                        expression {
                            TEST_DISTS.contains("ubuntu-focal")
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-focal")
                    }
                }

                stage("Ubuntu Jammy") {
                    when {
                        expression {
                            TEST_DISTS.contains("ubuntu-jammy")
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-jammy")
                    }
                }

                stage("Ubuntu Noble ARM") {
                    when {
                        expression {
                            TEST_DISTS.contains("ubuntu-noble-arm")
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-noble-arm")
                    }
                }

                stage("Ubuntu Jammy ARM") {
                    when {
                        expression {
                            TEST_DISTS.contains("ubuntu-jammy-arm")
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-jammy-arm")
                    }
                }

                stage("Ubuntu Focal ARM") {
                    when {
                        expression {
                            TEST_DISTS.contains("ubuntu-focal-arm")
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-focal-arm")
                    }
                }

                stage("Debian Bullseye") {
                    when {
                        expression {
                            TEST_DISTS.contains("debian-11")
                        }
                    }

                    steps {
                        runNodeBuild("debian-11")
                    }
                }

                stage("Debian Bullseye ARM") {
                    when {
                        expression {
                            TEST_DISTS.contains("debian-11-arm")
                        }
                    }

                    steps {
                        runNodeBuild("debian-11-arm")
                    }
                }

                stage("Debian Bookworm") {
                    when {
                        expression {
                            TEST_DISTS.contains("debian-12")
                        }
                    }

                    steps {
                        runNodeBuild("debian-12")
                    }
                }

                stage("Debian Bookworm-arm") {
                    when {
                        expression {
                            TEST_DISTS.contains("debian-12-arm")
                        }
                    }

                    steps {
                        runNodeBuild("debian-12-arm")
                    }
                }

                stage("Oracle Linux 8") {
                    when {
                        expression {
                            TEST_DISTS.contains("oracle-8")
                        }
                    }

                    steps {
                        runNodeBuild("oracle-8")
                    }
                }

                stage("Oracle Linux 9") {
                    when {
                        expression {
                            TEST_DISTS.contains("oracle-9")
                        }
                    }

                    steps {
                        runNodeBuild("oracle-9")
    
                    }
                }

                stage("Rhel-8") {
                    when {
                        expression {
                            TEST_DISTS.contains("rhel-8")
                        }
                    }

                    steps {
                        runNodeBuild("rhel-8")

                    }
                }

                stage("Rhel-8-arm") {
                    when {
                        expression {
                            TEST_DISTS.contains("rhel-8-arm")
                        }
                    }

                    steps {
                        runNodeBuild("rhel-8-arm")

                    }
                }

                stage("Rhel-9") {
                    when {
                        expression {
                            TEST_DISTS.contains("rhel-9")
                        }
                    }

                    steps {
                        runNodeBuild("rhel-9")

                    }
                }

                stage("Rhel-9-arm") {
                    when {
                        expression {
                            TEST_DISTS.contains("rhel-9-arm")
                        }
                    }

                    steps {
                        runNodeBuild("rhel-9-arm")

                    }
                }

                stage("Rhel-10") {
                    when {
                        expression {
                            TEST_DISTS.contains("rhel-10")
                        }
                    }

                    steps {
                        runNodeBuild("rhel-10")

                    }
                }

                stage("Rhel-10-arm") {
                    when {
                        expression {
                            TEST_DISTS.contains("rhel-10-arm")
                        }
                    }

                    steps {
                        runNodeBuild("rhel-10-arm")

                    }
                }
            }
        }
    }
}
