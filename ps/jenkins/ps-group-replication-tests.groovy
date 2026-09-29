library changelog: false, identifier: 'lib@PS-11589-gr-jenkins', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Removes containers, networks and volumes left behind by an aborted run of the framework.
// Every container it starts is attached to a grnet-<workerid> network; volumes are
// <node_prefix><n>-data, psrestore_<prefix>-data and grbackup_<prefix>.
void cleanupGrLeftovers() {
    sh '''
        for net in $(docker network ls --filter name=grnet- --format '{{.Name}}'); do
            docker ps -aq --filter network="${net}" | xargs -r docker rm -f || true
            docker network rm "${net}" || true
        done
        docker volume ls -q | grep -E '^(ps[^-_]*-[0-9]+-data|psrestore_.*-data|grbackup_)' | xargs -r docker volume rm -f || true
    '''
}

pipeline {
    agent {
        label 'docker'
    }
    environment {
        GR_VERBOSE = '1'
        GR_DIR = 'test_scripts/ps/group-replication'
    }
    parameters {
        string(
            defaultValue: 'percona/percona-server:8.4',
            description: 'Percona Server image',
            name: 'SERVER_IMAGE')
        string(
            defaultValue: 'percona/haproxy:2',
            description: 'HAProxy image',
            name: 'HAPROXY_IMAGE')
        string(
            defaultValue: 'percona/percona-mysql-router:8.4',
            description: 'MySQL Router image',
            name: 'ROUTER_IMAGE')
        string(
            defaultValue: 'percona/percona-xtrabackup:8.4',
            description: 'Percona XtraBackup image',
            name: 'XTRABACKUP_IMAGE')
        string(
            defaultValue: 'pingwinator/sysbench:latest',
            description: 'Sysbench image',
            name: 'SYSBENCH_IMAGE')
        string(
            defaultValue: 'all',
            description: 'Tests to run: "all" runs the whole suite, otherwise the value is passed to pytest as-is. Examples: test_basic.py; test_basic.py::test_replicates_table_across_nodes[router]; "test_basic.py test_scaling.py"; -k replicates',
            name: 'TEST')
        string(
            defaultValue: 'main',
            description: 'Branch for server-qa repository',
            name: 'TESTING_BRANCH')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
        timeout(time: 6, unit: 'HOURS')
        timestamps()
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.SERVER_IMAGE}-${params.TEST}"
                    currentBuild.description = "server-qa@${params.TESTING_BRANCH}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: params.TESTING_BRANCH, url: 'https://github.com/Percona-QA/server-qa.git'
            }
        }
        stage('Prepare') {
            steps {
                cleanupGrLeftovers()
                sh '''
                    docker version

                    PYTHON=""
                    for py in python3.13 python3.12 python3.11 python3.10; do
                        if command -v "${py}" >/dev/null 2>&1; then
                            PYTHON="${py}"
                            break
                        fi
                    done
                    if [ -z "${PYTHON}" ]; then
                        if command -v dnf >/dev/null 2>&1; then
                            sudo dnf install -y python3.11 python3.11-pip
                        elif command -v yum >/dev/null 2>&1; then
                            sudo yum install -y python3.11 python3.11-pip
                        else
                            sudo apt-get update && sudo apt-get install -y python3 python3-venv
                        fi
                        PYTHON=$(command -v python3.11 || command -v python3)
                    fi
                    ${PYTHON} --version

                    ${PYTHON} -m venv "${WORKSPACE}/venv"
                    "${WORKSPACE}/venv/bin/python" -m pip install --upgrade pip
                    "${WORKSPACE}/venv/bin/python" -m pip install -r "${GR_DIR}/requirements.txt"

                    for image in "${SERVER_IMAGE}" "${HAPROXY_IMAGE}" "${ROUTER_IMAGE}" "${XTRABACKUP_IMAGE}" "${SYSBENCH_IMAGE}"; do
                        docker pull "${image}"
                    done
                '''
            }
        }
        stage('Test') {
            steps {
                sh '''
                    cd "${GR_DIR}"
                    TARGET=""
                    if [ "${TEST}" != "all" ]; then
                        TARGET="${TEST}"
                    fi
                    # exit code 1 means some tests failed - junit marks the build UNSTABLE,
                    # anything else (usage/internal error, no tests collected) fails the build
                    "${WORKSPACE}/venv/bin/python" -m pytest -v --junitxml="${WORKSPACE}/junit.xml" ${TARGET} || [ $? = 1 ]
                '''
            }
        }
    }
    post {
        always {
            junit testResults: 'junit.xml', keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
            cleanupGrLeftovers()
            deleteDir()
        }
    }
}
