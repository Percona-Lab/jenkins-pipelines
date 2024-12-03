library changelog: false, identifier: 'lib@add-version-parameter-support', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Grishma123-Eng/jenkins-pipelines.git'
]) _

List all_nodes = [
    'ubuntu-noble',
    'ubuntu-focal',
    'ubuntu-jammy',
    'debian-11',
    'debian-12',
    'centos-7',
    'oracle-8',
    'oracle-9',
    'rhel-8',
    'rhel-9',
    'rhel-8-arm',
    'rhel-9-arm',
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
        job: 'test-ps-innodb-cluster-test-grishma',
        parameters: [
           // string(name: "UPSTREAM_VERSION", value: UPSTREAM_VERSION),
           // string(name: "PS_VERSION", value: params.PS_VERSION),
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

    parameters {
        choice(
            choices: ['PS80', 'PS84', 'PS_INN_LTS', 'client_test'],
            description: 'Choose the product version to test',
            name: 'PRODUCT_TO_TEST'
        )
        string(
            name: 'PS_VERSION',
            defaultValue: '24',
            description: 'Percona part of version'
        )
        string(
            name: 'PS_REVISION',
            defaultValue: 'e5c6e9d2',
            description: 'Short git hash for release'
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
                'centos-7',
                'oracle-8',
                'oracle-9',
                'rhel-8',
                'rhel-9',
                'rhel-8-arm',
                'rhel-9-arm',
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
    }

    stages {
        stage('SET PS_VERSION and PS_REVISION') {
            steps {
                script {
                    sh '''
                        rm -rf /package-testing
                        rm -f master.zip
                        wget https://github.com/Percona-QA/package-testing/archive/master.zip
                        unzip master.zip
                        rm -f master.zip
                        rm -rf /package-testing
                        mv "package-testing-master" package-testing
                    '''
                    
                    def VERSION = sh(
                        script: '''grep ${PRODUCT_TO_TEST}_VER VERSIONS | awk -F= '{print \$2}' | sed 's/"//g' ''',
                        returnStdout: true
                        ).trim()

                    def REVISION = sh(
                        script: ''' grep ${PRODUCT_TO_TEST}_REV VERSIONS | awk -F= '{print \$2}' | sed 's/"//g' ''',
                        returnStdout: true
                        ).trim()
                    
    
                    env.PS_VERSION = VERSION
                    env.PS_REVISION = REVISION

                    echo "PS_VERSION fetched: ${env.PS_VERSION}"
                    echo "PS_REVISION fetched: ${env.PS_REVISION}"

                }
            }
        }
        stage('Set environmental variable'){
            steps{
                 script {
                    // Now, you can access these global environment variables
                    echo "Using PS_VERSION: ${env.PS_VERSION}"
                    echo "Using PS_REVISION: ${env.PS_REVISION}"
                }
            }
        }
        stage("Prepare") {
            steps {
	        script {
                   currentBuild.displayName = "#${BUILD_NUMBER}-${PS_VERSION}"
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

                stage("Centos 7") {
                    when {
                        expression {
                            TEST_DISTS.contains("centos-7")
                        }
                    }

                    steps {
                        runNodeBuild("centos-7")
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
            }
        }
    }
}
