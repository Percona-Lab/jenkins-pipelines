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
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'EKS kubernetes version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'YES\nNO',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-xtradb-cluster-operator:main',
            name: 'OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'PXC image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0',
            name: 'IMAGE_PXC')
        string(
            defaultValue: '',
            description: 'PXC proxy image: perconalab/percona-xtradb-cluster-operator:main-proxysql',
            name: 'IMAGE_PROXY')
        string(
            defaultValue: '',
            description: 'PXC haproxy image: perconalab/percona-xtradb-cluster-operator:main-haproxy',
            name: 'IMAGE_HAPROXY')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PXC logcollector image: perconalab/percona-xtradb-cluster-operator:main-logcollector',
            name: 'IMAGE_LOGCOLLECTOR')
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
        cron('0 8 * * 6')
    }
    stages {
        stage("Run parallel") {
            parallel{

                stage('Trigger pxc-operator-aks-latest job 3 times') {
                    steps {
                        build job: 'pxc-operator-aks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                        build job: 'pxc-operator-aks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                        build job: 'pxc-operator-aks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                    }
                }

                stage('Trigger pxc-operator-gke-latest job 3 times') {
                    steps {
                        build job: 'pxc-operator-gke-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                        build job: 'pxc-operator-gke-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                        build job: 'pxc-operator-gke-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                    }
                }

                stage('Trigger pxc-operator-eks-latest job 3 times') {
                    steps {
                        build job: 'pxc-operator-eks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                        build job: 'pxc-operator-eks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                        build job: 'pxc-operator-eks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                    }
                }
                stage('Trigger pxc-operator-aws-openshift-latest job 3 times') {
                    steps {
                        build job: 'pxc-operator-aws-openshift-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                        build job: 'pxc-operator-aws-openshift-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                        build job: 'pxc-operator-aws-openshift-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST', value:"${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'OPERATOR_IMAGE', value: "${OPERATOR_IMAGE}"),string(name: 'IMAGE_PXC', value: "${IMAGE_PXC}"),string(name: 'IMAGE_PROXY', value: "${IMAGE_PROXY}"),string(name: 'IMAGE_HAPROXY', value: "${IMAGE_HAPROXY}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_LOGCOLLECTOR', value: "${IMAGE_LOGCOLLECTOR}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                    }
                }
            }
        }
    }
    post {
        always {

            copyArtifacts(projectName: 'pxc-operator-aks-latest', selector: lastCompleted(), target: 'pxc-operator-aks-latest')

            copyArtifacts(projectName: 'pxc-operator-gke-latest', selector: lastCompleted(), target: 'pxc-operator-gke-latest')

            copyArtifacts(projectName: 'pxc-operator-eks-latest', selector: lastCompleted(), target: 'pxc-operator-eks-latest')
            
            copyArtifacts(projectName: 'pxc-operator-aws-openshift-latest', selector: lastCompleted(), target: 'pxc-operator-aws-openshift-latest')

            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])

        }
    }
}
