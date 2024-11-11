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
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'GKE kubernetes version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'YES\nNO',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mongodb-operator:main',
            name: 'IMAGE_OPERATOR')
        string(
            defaultValue: '',
            description: 'MONGOD image: perconalab/percona-server-mongodb-operator:main-mongod5.0',
            name: 'IMAGE_MONGOD')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-server-mongodb-operator:main-backup',
            name: 'IMAGE_BACKUP')
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
        cron('0 15 * * 6')
    }
    stages {
        stage("Run parallel") {
            parallel{

                stage('Trigger psmdb-operator-aks-latest job 3 times') {
                    steps {
                        script {
                            for (int i = 1; i <= 3; i++) {
                                build job: 'psmdb-operator-aks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST',value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'IMAGE_OPERATOR', value: "${IMAGE_OPERATOR}"),string(name: 'IMAGE_MONGOD', value: "${IMAGE_MONGOD}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                            }   
                        }
                    }
                }

                stage('Trigger psmdb-operator-gke-latest job 3 times') {
                    steps {
                        script {
                            for (int i = 1; i <= 3; i++) {
                                build job: 'psmdb-operator-gke-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST',value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'IMAGE_OPERATOR', value: "${IMAGE_OPERATOR}"),string(name: 'IMAGE_MONGOD', value: "${IMAGE_MONGOD}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                            }
                        }
                    }
                }

                stage('Trigger psmdb-operator-eks-latest job 3 times') {
                    steps {
                        script {
                            for (int i = 1; i <= 3; i++) {
                                build job: 'psmdb-operator-eks-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST',value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'IMAGE_OPERATOR', value: "${IMAGE_OPERATOR}"),string(name: 'IMAGE_MONGOD', value: "${IMAGE_MONGOD}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                            }
                        }
                    }
                }

                stage('Trigger psmdb-operator-aws-openshift-latest job 3 times') {
                    steps {
                        script {
                            for (int i = 1; i <= 3; i++) {
                                build job: 'psmdb-operator-aws-openshift-latest', propagate: false, wait: true, parameters: [string(name: 'TEST_SUITE', value: "${TEST_SUITE}"),text(name: 'TEST_LIST',value: "${TEST_LIST}"),string(name: 'IGNORE_PREVIOUS_RUN', value: "${IGNORE_PREVIOUS_RUN}"),string(name: 'GIT_REPO', value: "${GIT_REPO}"),string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),string(name: 'PLATFORM_VER', value: "${PLATFORM_VER}"),string(name: 'CLUSTER_WIDE', value: "${CLUSTER_WIDE}"),string(name: 'IMAGE_OPERATOR', value: "${IMAGE_OPERATOR}"),string(name: 'IMAGE_MONGOD', value: "${IMAGE_MONGOD}"),string(name: 'IMAGE_BACKUP', value: "${IMAGE_BACKUP}"),string(name: 'IMAGE_PMM_CLIENT', value: "${IMAGE_PMM_CLIENT}"),string(name: 'IMAGE_PMM_SERVER', value: "${IMAGE_PMM_SERVER}")]
                            }
                        }
                    }
                }

            }
        }
    }
    post {
        always {

            copyArtifacts(projectName: 'psmdb-operator-aks-latest', selector: lastCompleted(), target: 'psmdb-operator-aks-latest')

            copyArtifacts(projectName: 'psmdb-operator-gke-latest', selector: lastCompleted(), target: 'psmdb-operator-gke-latest')

            copyArtifacts(projectName: 'psmdb-operator-eks-latest', selector: lastCompleted(), target: 'psmdb-operator-eks-latest')
            
            copyArtifacts(projectName: 'psmdb-operator-aws-openshift-latest', selector: lastCompleted(), target: 'psmdb-operator-aws-openshift-latest')

            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])

        }
    }
}
