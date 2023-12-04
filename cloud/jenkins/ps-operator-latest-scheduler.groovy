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
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mysql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mysql-operator',
            description: 'percona-server-mysql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'GKE version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'NO\nYES',
            description: 'GKE alpha/stable',
            name: 'IS_GKE_ALPHA')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mysql-operator:main',
            name: 'OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'PS for MySQL image: perconalab/percona-server-mysql-operator:main-ps8.0',
            name: 'IMAGE_MYSQL')
        string(
            defaultValue: '',
            description: 'Orchestrator image: perconalab/percona-server-mysql-operator:main-orchestrator',
            name: 'IMAGE_ORCHESTRATOR')
        string(
            defaultValue: '',
            description: 'MySQL Router image: perconalab/percona-server-mysql-operator:main-router',
            name: 'IMAGE_ROUTER')
        string(
            defaultValue: '',
            description: 'XtraBackup image: perconalab/percona-server-mysql-operator:main-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'Toolkit image: perconalab/percona-server-mysql-operator:main-toolkit',
            name: 'IMAGE_TOOLKIT')
        string(
            defaultValue: '',
            description: 'HAProxy image: perconalab/percona-server-mysql-operator:main-haproxy',
            name: 'IMAGE_HAPROXY')
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
    stages {
        stage("Run parallel") {
            parallel{

                stage('Trigger ps-operator-gke-latest job 3 times') {
                    steps {
                        build job: 'ps-operator-gke-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'IS_GKE_ALPHA', value: "${IS_GKE_ALPHA}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_MYSQL', value: "${IMAGE_MYSQL}"),string(name: 'IMAGE_ORCHESTRATOR', value: "${IMAGE_ORCHESTRATOR}"),string(name: 'IMAGE_ROUTER', value: "${IMAGE_ROUTER}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_TOOLKIT', value: "${IMAGE_TOOLKIT}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_CLIENT}")]
                        build job: 'ps-operator-gke-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'IS_GKE_ALPHA', value: "${IS_GKE_ALPHA}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_MYSQL', value: "${IMAGE_MYSQL}"),string(name: 'IMAGE_ORCHESTRATOR', value: "${IMAGE_ORCHESTRATOR}"),string(name: 'IMAGE_ROUTER', value: "${IMAGE_ROUTER}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_TOOLKIT', value: "${IMAGE_TOOLKIT}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_CLIENT}")]
                        build job: 'ps-operator-gke-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'IS_GKE_ALPHA', value: "${IS_GKE_ALPHA}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_MYSQL', value: "${IMAGE_MYSQL}"),string(name: 'IMAGE_ORCHESTRATOR', value: "${IMAGE_ORCHESTRATOR}"),string(name: 'IMAGE_ROUTER', value: "${IMAGE_ROUTER}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_TOOLKIT', value: "${IMAGE_TOOLKIT}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_CLIENT}")]
                    }
                }

                stage('Trigger ps-operator-eks-latest job 3 times') {
                    steps {
                        build job: 'ps-operator-eks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_MYSQL', value: "${IMAGE_MYSQL}"),string(name: 'IMAGE_ORCHESTRATOR', value: "${IMAGE_ORCHESTRATOR}"),string(name: 'IMAGE_ROUTER', value: "${IMAGE_ROUTER}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_TOOLKIT', value: "${IMAGE_TOOLKIT}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_CLIENT}")]
                        build job: 'ps-operator-eks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_MYSQL', value: "${IMAGE_MYSQL}"),string(name: 'IMAGE_ORCHESTRATOR', value: "${IMAGE_ORCHESTRATOR}"),string(name: 'IMAGE_ROUTER', value: "${IMAGE_ROUTER}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_TOOLKIT', value: "${IMAGE_TOOLKIT}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_CLIENT}")]
                        build job: 'ps-operator-eks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),string(name: 'TEST_LIST', value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_MYSQL', value: "${IMAGE_MYSQL}"),string(name: 'IMAGE_ORCHESTRATOR', value: "${IMAGE_ORCHESTRATOR}"),string(name: 'IMAGE_ROUTER', value: "${IMAGE_ROUTER}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_TOOLKIT', value: "${IMAGE_TOOLKIT}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_CLIENT}")]
                    }
                }
            }
        }
    }
    post {
        always {

            copyArtifacts(projectName: 'ps-operator-gke-latest', selector: lastCompleted(), target: 'ps-operator-gke-latest')
            copyArtifacts(projectName: 'ps-operator-eks-latest', selector: lastCompleted(), target: 'ps-operator-eks-latest')
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])

        }
    }
}
