library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _


pipeline {
    parameters {
        choice(
            choices: ['run-release.csv', 'run-distro.csv'],
            description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.',
            name: 'TEST_SUITE')
        text(
            defaultValue: '',
            description: 'List of tests to run separated by new line',
            name: 'TEST_LIST')
        choice(
            choices: 'NO\nYES',
            description: 'Ignore passed tests in previous run (run all)',
            name: 'IGNORE_PREVIOUS_RUN'
        )
        choice(
            choices: 'YES\nNO',
            description: 'Run tests with cluster wide',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: 'latest',
            description: 'Kubernetes target version',
            name: 'PLATFORM_VER')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-postgresql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-postgresql-operator',
            description: 'percona-postgresql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '',
            description: 'PG version',
            name: 'PG_VERSION')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-postgresql-operator:main',
            name: 'OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators pgBouncer image: perconalab/percona-postgresql-operator:main-ppg15-pgbouncer',
            name: 'PGO_PGBOUNCER_IMAGE')
        string(
            defaultValue: '',
            description: 'For EKS Operators postgres image: perconalab/percona-postgresql-operator:main-ppg15-postgres',
            name: 'PGO_POSTGRES_IMAGE')
        string(
            defaultValue: '',
            description: 'For GKE/OPENSHIFT) Operators postgres image: perconalab/percona-postgresql-operator:main-ppg15-postgres',
            name: 'PGO_POSTGRES_HA_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators backrest utility image: perconalab/percona-postgresql-operator:main-ppg15-pgbackrest',
            name: 'PGO_BACKREST_IMAGE')
        string(
            defaultValue: '',
            description: 'PMM client image: perconalab/pmm-client:dev-latest',
            name: 'IMAGE_PMM_CLIENT')
        string(
            defaultValue: '',
            description: 'PMM server image: perconalab/pmm-server:dev-latest',
            name: 'IMAGE_PMM_SERVER')
    }
    agent {
        label 'docker'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    triggers {
        cron('0 15 * * 0')
    }
    stages {
        stage("Run parallel") {
            parallel{

                stage('Trigger pgo-operator-gke-latest job 3 times') {
                    steps {
                        script {
                            for (int i = 1; i <= 3; i++) {
                                build job: 'pgo-operator-gke-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PG_VERSION', value: "${PG_VERSION}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'PGO_PGBOUNCER_IMAGE', value: "${PGO_PGBOUNCER_IMAGE}"),string(name: 'PGO_POSTGRES_HA_IMAGE', value: "${PGO_POSTGRES_HA_IMAGE}"),string(name: 'PGO_BACKREST_IMAGE', value: "${PGO_BACKREST_IMAGE}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                            }
                        }
                    }
                }

                stage('Trigger pgo-operator-eks-latest job 3 times') {
                    steps {
                        script {
                            for (int i = 1; i <= 3; i++) {
                                build job: 'pgo-operator-eks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PG_VERSION', value: "${PG_VERSION}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'PGO_PGBOUNCER_IMAGE', value: "${PGO_PGBOUNCER_IMAGE}"),string(name: 'PGO_POSTGRES_IMAGE', value: "${PGO_POSTGRES_IMAGE}"),string(name: 'PGO_BACKREST_IMAGE', value: "${PGO_BACKREST_IMAGE}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                            }
                        }
                    }
                }
                stage('Trigger pgo-operator-aws-openshift-latest job 3 times') {
                    steps {
                        script {
                            for (int i = 1; i <= 3; i++) {
                                build job: 'pgo-operator-aws-openshift-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PG_VERSION', value: "${PG_VERSION}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'PGO_PGBOUNCER_IMAGE', value: "${PGO_PGBOUNCER_IMAGE}"),string(name: 'PGO_POSTGRES_HA_IMAGE', value: "${PGO_POSTGRES_HA_IMAGE}"),string(name: 'PGO_BACKREST_IMAGE', value: "${PGO_BACKREST_IMAGE}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {

            copyArtifacts(projectName: 'pgo-operator-gke-latest', selector: lastCompleted(), target: 'pgo-operator-gke-latest')

            copyArtifacts(projectName: 'pgo-operator-eks-latest', selector: lastCompleted(), target: 'pgo-operator-eks-latest')
            
            copyArtifacts(projectName: 'pgo-operator-aws-openshift-latest', selector: lastCompleted(), target: 'pgo-operator-aws-openshift-latest')

            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])

        }
    }
}
