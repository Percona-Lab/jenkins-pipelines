library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'min-ol-9-x64'
    }

    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }

    parameters {
        choice(name: 'REPO', description: 'Repo for testing', choices: repoList())
        string(defaultValue: 'ppg-18.3', description: 'PPG version for test', name: 'VERSION')
        string(defaultValue: 'main', description: 'Base branch for ppg-testing repo', name: 'TESTING_BRANCH')
        string(defaultValue: '2.3.2', description: 'PGSM version', name: 'PGSM_VERSION')
        string(defaultValue: '4.7.0', description: 'pgpool version', name: 'PGPOOL_VERSION')
        string(defaultValue: '3.5.5', description: 'postgis version', name: 'POSTGIS_VERSION')
        string(defaultValue: '18.0', description: 'PGAUDIT version', name: 'PGAUDIT_VERSION')
        string(defaultValue: 'ver_1.5.3', description: 'PG_REPACK version', name: 'PG_REPACK_VERSION')
        string(defaultValue: 'v4.1.0', description: 'Patroni version', name: 'PATRONI_VERSION')
        string(defaultValue: 'release/2.58.0', description: 'pgbackrest version', name: 'PGBACKREST_VERSION')
        string(defaultValue: 'REL4_2_0', description: 'pgaudit13_set_user version', name: 'SETUSER_VERSION')
        string(defaultValue: 'v13.2', description: 'pgbadger version', name: 'PGBADGER_VERSION')
        string(defaultValue: 'pgbouncer_1_25_1', description: 'pgbouncer version', name: 'PGBOUNCER_VERSION')
        string(defaultValue: 'wal2json_2_6', description: 'wal2json version', name: 'WAL2JSON_VERSION')
        string(defaultValue: 'v0.8.1', description: 'pgvector version', name: 'PGVECTOR_VERSION')
    }

    options {
        withCredentials(moleculeDistributionJenkinsCreds())
    }

    stages {
        // --- PRE-PARALLEL STAGE ---
        stage('Setup Metadata') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}-component-multi-parallel"
                }
            }
        }

        // --- PARALLEL EXECUTION STAGE ---
        stage('Parallel Components Tests') {
            parallel {
                stage('Test PGSM') {
                    steps {
                        script {
                            try {
                                build job: 'pgsm-parallel', parameters: [
                                    string(name: 'REPO', value: "${env.REPO}"),
                                    string(name: 'PGSM_REPO', value: "https://github.com/percona/pg_stat_monitor.git"),
                                    string(name: 'VERSION', value: "${env.VERSION}"),
                                    string(name: 'PGSM_BRANCH', value: "${env.PGSM_VERSION}"),
                                    booleanParam(name: 'PGSM_PACKAGE_INSTALL', value: true),
                                    string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                                    booleanParam(name: 'MAJOR_REPO', value: false),
                                ]
                            } catch (err) {
                                currentBuild.result = "FAILURE"
                                echo "Stage 'Test PGSM' failed: ${err.message}"
                            }
                        }
                    }
                }

                stage('Test pgpool') {
                    steps {
                        script {
                            executeComponentTest("pgpool", "https://github.com/pgpool/pgpool2.git", env.PGPOOL_VERSION)
                        }
                    }
                }

                stage('Test postgis') {
                    steps {
                        script {
                            executeComponentTest("postgis", "https://github.com/postgis/postgis.git", env.POSTGIS_VERSION)
                        }
                    }
                }

                stage('Test pgaudit') {
                    steps {
                        script {
                            executeComponentTest("pg_audit", "https://github.com/pgaudit/pgaudit.git", env.PGAUDIT_VERSION)
                        }
                    }
                }

                stage('Test pg_repack') {
                    steps {
                        script {
                            executeComponentTest("pg_repack", "https://github.com/reorg/pg_repack.git", env.PG_REPACK_VERSION)
                        }
                    }
                }

                stage('Test patroni') {
                    steps {
                        script {
                            executeComponentTest("patroni", "https://github.com/zalando/patroni.git", env.PATRONI_VERSION)
                        }
                    }
                }

                stage('Test pgbackrest') {
                    steps {
                        script {
                            executeComponentTest("pgbackrest", "https://github.com/pgbackrest/pgbackrest.git", env.PGBACKREST_VERSION)
                        }
                    }
                }

                stage('Test pgbadger') {
                    steps {
                        script {
                            executeComponentTest("pgbadger", "https://github.com/darold/pgbadger.git", env.PGBADGER_VERSION)
                        }
                    }
                }

                stage('Test wal2json') {
                    steps {
                        script {
                            executeComponentTest("wal2json", "https://github.com/eulerto/wal2json.git", env.WAL2JSON_VERSION)
                        }
                    }
                }

                stage('Test set_user') {
                    steps {
                        script {
                            executeComponentTest("pgaudit13_set_user", "https://github.com/pgaudit/set_user.git", env.SETUSER_VERSION)
                        }
                    }
                }

                stage('Test pgbouncer') {
                    steps {
                        script {
                            executeComponentTest("pgbouncer", "https://github.com/pgbouncer/pgbouncer.git", env.PGBOUNCER_VERSION)
                        }
                    }
                }

                stage('Test pgvector') {
                    steps {
                        script {
                            executeComponentTest("pgvector", "https://github.com/pgvector/pgvector.git", env.PGVECTOR_VERSION)
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                sendSlackNotificationPPG()
            }
        }
    }
}

/**
 * Helper function to reduce code duplication in the parallel stages
 */
def executeComponentTest(productName, repoUrl, componentVersion) {
    try {
        build job: 'component-generic-parallel', parameters: [
            string(name: 'VERSION', value: "${env.VERSION}"),
            string(name: 'REPO', value: "${env.REPO}"),
            string(name: 'PRODUCT', value: productName),
            string(name: 'COMPONENT_REPO', value: repoUrl),
            string(name: 'COMPONENT_VERSION', value: componentVersion),
            string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
            string(name: 'DESTROY_ENV', value: "false"),
        ]
    } catch (err) {
        currentBuild.result = "FAILURE"
        echo "Stage 'Test ${productName}' failed: ${err.message}"
    }
}