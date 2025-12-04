/**
 * PMM API Tests - Dagger Pipeline
 *
 * This pipeline uses the Dagger module in dagger/ to run PMM API tests.
 * It replaces the Groovy-based pmm3-api-tests.groovy with a portable,
 * cacheable implementation.
 *
 * Benefits:
 *   - Run tests locally: cd dagger && dagger call run-tests
 *   - Built-in caching for faster builds
 *   - Same behavior locally and in CI
 *
 * Related:
 *   - Original: pmm/v3/pmm3-api-tests.groovy
 *   - Dagger module: dagger/main.py
 */
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    environment {
        DAGGER_VERSION = '0.19.7'
        DAGGER_BIN = "${WORKSPACE}/.dagger/bin"
        PATH = "${DAGGER_BIN}:${PATH}"
    }

    parameters {
        string(
            defaultValue: 'https://github.com/percona/pmm',
            description: 'URL for PMM repository',
            name: 'GIT_URL'
        )
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for PMM repository',
            name: 'GIT_BRANCH'
        )
        string(
            defaultValue: '',
            description: 'Commit hash for the branch (optional)',
            name: 'GIT_COMMIT_HASH'
        )
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker image version',
            name: 'DOCKER_VERSION'
        )
        string(
            defaultValue: 'percona:8.0',
            description: 'Percona Server Docker Container Image',
            name: 'MYSQL_IMAGE'
        )
        string(
            defaultValue: 'postgres:14',
            description: 'PostgreSQL Docker Container Image',
            name: 'POSTGRES_IMAGE'
        )
        string(
            defaultValue: 'percona/percona-server-mongodb:5.0',
            description: 'Percona Server MongoDB Docker Container Image',
            name: 'MONGO_IMAGE'
        )
    }

    options {
        skipDefaultCheckout()
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    triggers {
        upstream upstreamProjects: 'pmm3-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                // Clone jenkins-pipelines to get Dagger module
                checkout scm

                slackSend botUser: true,
                          channel: '#pmm-notifications',
                          color: '#0000FF',
                          message: "[${JOB_NAME}]: build started - ${BUILD_URL}"

                script {
                    // Set envvars OWNER, OWNER_SLACK for notifications
                    getPMMBuildParams('pmm-')
                }
            }
        }

        stage('Install Dagger') {
            steps {
                sh '''
                    mkdir -p "${DAGGER_BIN}"
                    curl -fsSL https://dl.dagger.io/dagger/install.sh | BIN_DIR="${DAGGER_BIN}" DAGGER_VERSION="${DAGGER_VERSION}" sh
                    dagger version
                '''
            }
        }

        stage('Run API Tests') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'hub.docker.com',
                        passwordVariable: 'DOCKER_PASSWORD',
                        usernameVariable: 'DOCKER_USER'
                    )
                ]) {
                    sh '''
                        cd dagger

                        # Build dagger call command with parameters
                        DAGGER_CMD="dagger call run-tests"
                        DAGGER_CMD="${DAGGER_CMD} --git-url='${GIT_URL}'"
                        DAGGER_CMD="${DAGGER_CMD} --git-branch='${GIT_BRANCH}'"
                        DAGGER_CMD="${DAGGER_CMD} --docker-version='${DOCKER_VERSION}'"
                        DAGGER_CMD="${DAGGER_CMD} --mysql-image='${MYSQL_IMAGE}'"
                        DAGGER_CMD="${DAGGER_CMD} --postgres-image='${POSTGRES_IMAGE}'"
                        DAGGER_CMD="${DAGGER_CMD} --mongo-image='${MONGO_IMAGE}'"

                        # Add commit hash if provided
                        if [ -n "${GIT_COMMIT_HASH}" ]; then
                            DAGGER_CMD="${DAGGER_CMD} --git-commit='${GIT_COMMIT_HASH}'"
                        fi

                        # Add Docker Hub credentials
                        DAGGER_CMD="${DAGGER_CMD} --docker-username='${DOCKER_USER}'"
                        DAGGER_CMD="${DAGGER_CMD} --docker-password=env:DOCKER_PASSWORD"

                        # Export JUnit results
                        DAGGER_CMD="${DAGGER_CMD} export --path ../pmm3-api-tests.xml"

                        echo "Running: ${DAGGER_CMD}"
                        eval ${DAGGER_CMD}
                    '''
                }
            }
        }
    }

    post {
        always {
            // Try to get logs for debugging
            sh '''
                cd dagger
                dagger call get-logs \
                    --docker-version="${DOCKER_VERSION}" \
                    export --path ../logs.zip || true
            '''

            script {
                if (fileExists('pmm3-api-tests.xml')) {
                    junit testResults: 'pmm3-api-tests.xml', skipPublishingChecks: true
                }
                if (fileExists('logs.zip')) {
                    archiveArtifacts artifacts: 'logs.zip', allowEmptyArchive: true
                }
            }
        }

        failure {
            slackSend botUser: true,
                      channel: '#pmm-notifications',
                      color: '#FF0000',
                      message: "[${JOB_NAME}]: build failed, URL: ${BUILD_URL}, owner: @${OWNER}"
        }

        success {
            slackSend botUser: true,
                      channel: '#pmm-notifications',
                      color: '#00FF00',
                      message: "[${JOB_NAME}]: build finished, URL: ${BUILD_URL}, owner: @${OWNER}"
        }
    }
}
