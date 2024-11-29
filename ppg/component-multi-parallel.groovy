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
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: repoList()
        )
        string(
            defaultValue: 'ppg-17.2',
            description: 'PPG version for test',
            name: 'VERSION'
         )
        string(
            defaultValue: 'main',
            description: 'Base branch for upgrade test',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: '2.1.0',
            description: 'PGSM version',
            name: 'PGSM_VERSION')
        string(
            defaultValue: '4.5.4',
            description: 'pgpool version',
            name: 'PGPOOL_VERSION')
        string(
            defaultValue: '3.3.7',
            description: 'postgis version',
            name: 'POSTGIS_VERSION')
        string(
            defaultValue: '17.0',
            description: ' version',
            name: 'PGAUDIT_VERSION')
        string(
            defaultValue: 'ver_1.5.1',
            description: 'PG_REPACK version',
            name: 'PG_REPACK_VERSION')
        string(
            defaultValue: 'v4.0.3',
            description: 'Patroni version',
            name: 'PATRONI_VERSION')
        string(
            defaultValue: 'release/2.54.0',
            description: 'pgbackrest version',
            name: 'PGBACKREST_VERSION')
        string(
            defaultValue: 'REL4_1_0',
            description: 'pgaudit13_set_user version',
            name: 'SETUSER_VERSION')
        string(
            defaultValue: 'v12.4',
            description: 'pgbadger version',
            name: 'PGBADGER_VERSION')
        string(
            defaultValue: 'pgbouncer_1_23_1',
            description: 'pgbouncer version',
            name: 'PGBOUNCER_VERSION')
        string(
            defaultValue: 'wal2json_2_6',
            description: 'wal2json version',
            name: 'WAL2JSON_VERSION')
        string(
            defaultValue: '0.8.0',
            description: 'pgvector version',
            name: 'PGVECTOR_VERSION')
        string(
            defaultValue: 'yes',
            description: 'Destroy VM after tests',
            name: 'DESTROY_ENV')
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
        stage('Set build name'){
            steps {
                script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}-component-multi-parallel"
                }
            }
        }
        stage ('Test PGSM on all platforms') {
            when {
                expression { env.REPO != 'release' }
            }
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
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test PGSM on all platforms' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pgpool') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "pgpool"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgpool/pgpool2.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGPOOL_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pgpool' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test postgis') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "postgis"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/postgis/postgis.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.POSTGIS_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test postgis' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pgaudit') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "pg_audit"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgaudit/pgaudit.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGAUDIT_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pgaudit' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pg_repack') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "pg_repack"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/reorg/pg_repack.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PG_REPACK_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pg_repack' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test patroni') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "patroni"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/zalando/patroni.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PATRONI_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test patroni' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pgbackrest') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "pgbackrest"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgbackrest/pgbackrest.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGBACKREST_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pgbackrest' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pgbadger') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "pgbadger"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/darold/pgbadger.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGBADGER_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pgbadger' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test wal2json') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "wal2json"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/eulerto/wal2json.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.WAL2JSON_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test wal2json' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test set_user') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "pgaudit13_set_user"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgaudit/set_user.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.SETUSER_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test set_user' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pgbouncer') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "pgbouncer"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgbouncer/pgbouncer.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGBOUNCER_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pgbouncer' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pgvector') {
            steps {
                script {
                    try {
                        build job: 'component-generic-parallel', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.REPO}"),
                        string(name: 'PRODUCT', value: "pgvector"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgvector/pgvector.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGVECTOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'DESTROY_ENV', value: "${env.DESTROY_ENV}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pgvector' failed, but we continue"
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
