library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
  agent {
    label 'min-centos-7-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
  parameters {
        choice(
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: repoList()
        )
        choice(
            name: 'FROM_REPO',
            description: 'Repo for testing',
            choices: repoList()
        )
        string(
            defaultValue: 'ppg-14.3',
            description: 'PPG version for test',
            name: 'VERSION'
         )
        string(
            defaultValue: 'ppg-14.2',
            description: 'PPG Version for minor upgradetests',
            name: 'FROM_MINOR_VERSION')
        string(
            defaultValue: 'ppg-13.6',
            description: 'PPG Version for major upgrade tests',
            name: 'FROM_MAJOR_VERSION')
        string(
            defaultValue: 'main',
            description: 'Base branch for upgrade test',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: '14',
            description: 'PPG Major Version to test',
            name: 'MAJOR_VERSION')
        string(
            defaultValue: '1.0.1',
            description: 'PGSM version',
            name: 'PGSM_VERSION')
        string(
            defaultValue: '1.6.1',
            description: ' version',
            name: 'PGAUDIT_VERSION')
        string(
            defaultValue: 'ver_1.4.7',
            description: 'PG_REPACK version',
            name: 'PG_REPACK_VERSION')
        string(
            defaultValue: 'v2.1.2',
            description: 'Patroni version',
            name: 'PATRONI_VERSION')
        string(
            defaultValue: 'release/2.37',
            description: 'pgbackrest version',
            name: 'PGBACKREST_VERSION')
        string(
            defaultValue: 'REL3_0_0',
            description: 'pgaudit13_set_user version',
            name: 'SETUSER_VERSION')
        string(
            defaultValue: 'v11.7',
            description: 'pgbadger version',
            name: 'PGBADGER_VERSION')
        string(
            defaultValue: 'pgbouncer_1_16_1',
            description: 'pgbouncer version',
            name: 'PGBOUNCER_VERSION')
        string(
            defaultValue: 'wal2json_2_4',
            description: 'wal2json version',
            name: 'WAL2JSON_VERSION')
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
        stage ('Test install packages') {
            when {
                expression { env.TO_REPO != 'release' }
            }
            steps {
                script {
                    try {
                        build job: 'ppg-parallel', parameters: [
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}"),
                        booleanParam(name: 'MAJOR_REPO', value: false),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test install' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test install meta HA packages') {
            when {
                expression { env.TO_REPO != 'release' }
            }
            steps {
                script {
                    try {
                        build job: 'ppg-parallel', parameters: [
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-meta-ha"),
                        booleanParam(name: 'MAJOR_REPO', value: false),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test install' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test install meta server packages') {
            when {
                expression { env.TO_REPO != 'release' }
            }
            steps {
                script {
                    try {
                        build job: 'ppg-parallel', parameters: [
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-meta-server"),
                        booleanParam(name: 'MAJOR_REPO', value: false),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test install' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pg_stat_monitor') {
            steps {
                script {
                    try {
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "pg_stat_monitor"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/percona/pg_stat_monitor.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGSM_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pg_stat_monitor' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test pgaudit') {
            steps {
                script {
                    try {
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "pg_audit"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgaudit/pgaudit.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGAUDIT_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
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
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "pg_repack"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/reorg/pg_repack.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PG_REPACK_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
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
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "patroni"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/zalando/patroni.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PATRONI_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
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
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "pgbackrest"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgbackrest/pgbackrest.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGBACKREST_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
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
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "pgbadger"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/darold/pgbadger.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGBADGER_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
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
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "wal2json"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/eulerto/wal2json.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.WAL2JSON_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
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
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "pgaudit13_set_user"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgaudit/set_user.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.SETUSER_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
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
                        build job: 'component', parameters: [
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PRODUCT', value: "pgbouncer"),
                        string(name: 'COMPONENT_REPO', value: "https://github.com/pgbouncer/pgbouncer.git"),
                        string(name: 'COMPONENT_VERSION', value: "${env.PGBOUNCER_VERSION}"),
                        string(name: 'SCENARIO', value: "ppg-${env.MAJOR_VERSION}"),
                        string(name: 'TEST_BRANCH', value: "${env.TESTING_BRANCH}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test pgbouncer' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test minor upgrade') {
            steps {
                script {
                    try {
                        build job: 'ppg-upgrade-parallel', parameters: [
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.FROM_MINOR_VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-minor-upgrade"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test minor upgrade' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test minor downgrade') {
            steps {
                script {
                    try {
                        build job: 'ppg-upgrade-parallel', parameters: [
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.FROM_MINOR_VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-minor-upgrade"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test minor downgrade' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test major upgrade') {
            when {
                expression { env.MAJOR_VERSION != '11' }
            }
            steps {
                script {
                    try {
                        build job: 'ppg-upgrade-parallel', parameters: [
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.FROM_MAJOR_VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-major-upgrade"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test major upgrade' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test major downgrade') {
            when {
                expression { env.MAJOR_VERSION != '11' }
            }
            steps {
                script {
                    try {
                        build job: 'ppg-upgrade-parallel', parameters: [
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.FROM_MAJOR_VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-major-upgrade"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test major downgrade' failed, but we continue"
                    }
                }
            }
        }

        stage ('Test Percona Components with Vanila Postgresql') {
            steps {
                script {
                    try {
                        build job: 'ppg-parallel', parameters: [
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-components-with-vanila"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test Percona Components with Vanila Postgresql' failed, but we continue"
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
