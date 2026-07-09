library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label params.USE_ONDEMAND ? 'agent-arm64-ondemand' : 'agent-arm64'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        choice(
            // default is 'experimental'
            choices: ['experimental', 'testing'],
            description: 'Repository to push packages to',
            name: 'DESTINATION')
        booleanParam(
            defaultValue: false,
            description: 'Use on-demand instances instead of spot (for RC/Release builds)',
            name: 'USE_ONDEMAND'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
        GOARCH = 'arm64'
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: true,
                    branch: GIT_BRANCH,
                    url: 'http://github.com/Percona-Lab/pmm-submodules'
                script {
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }
                sh '''
                    set -o errexit
                    git submodule update --init --jobs 10
                    git submodule status

                    git rev-parse --short HEAD > shortCommit
                    echo "UPLOAD/pmm3-components/yum/${DESTINATION}/${JOB_NAME}/pmm/${VERSION}/${GIT_BRANCH}/$(cat shortCommit)/${BUILD_NUMBER}" > uploadPath
                '''
                script {
                    if (params.DESTINATION == "testing") {
                        env.DOCKER_LATEST_TAG     = "${VERSION}-rc${BUILD_NUMBER}-test-arm64"
                        env.DOCKER_RC_TAG         = "${VERSION}-rc-test-arm64"
                    } else {
                        env.DOCKER_LATEST_TAG     = "3-dev-latest-test-arm64"
                    }
                }

                archiveArtifacts 'uploadPath'
                stash includes: 'uploadPath', name: 'uploadPath'
                archiveArtifacts 'shortCommit'
            }
        }
        stage('Build client source') {
            steps {
                sh "${PATH_TO_SCRIPTS}/build-client-source"
                stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                uploadTarball('source')
            }
        }
        stage('Build client binary') {
            steps {
                sh "${PATH_TO_SCRIPTS}/build-client-binary"
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                uploadTarball('binary')
            }
        }
        stage('Build client source rpm') {
            steps {
                sh "${PATH_TO_SCRIPTS}/build-client-srpm"
                stash includes: 'results/srpm/pmm*-client-*.src.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build client binary rpm') {
            steps {
                sh '''
                    set -o errexit

                    ${PATH_TO_SCRIPTS}/build-client-rpm

                    mkdir -p tmp/pmm-server/RPMS/
                    cp results/rpm/pmm*-client-*.rpm tmp/pmm-server/RPMS/
                '''
                stash includes: 'tmp/pmm-server/RPMS/*.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build server packages') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    '''
                }
                stash includes: 'tmp/pmm-server/RPMS/*/*/*.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build server docker') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                        sh '''
                            echo "${PASS}" | docker login -u "${USER}" --password-stdin
                        '''
                }
                sh '''
                    set -o errexit

                    export DOCKER_TAG=perconalab/pmm-server:$(date -u '+%Y%m%d%H%M')-test-arm64
                    export DOCKERFILE=Dockerfile.el9
                    if [ -n "${DOCKER_RC_TAG}" ]; then
                        export PMM_PERCONA_PLATFORM_ADDRESS=https://check.percona.com
                    fi

                    ${PATH_TO_SCRIPTS}/build-server-docker

                    if [ -n "${DOCKER_RC_TAG}" ]; then
                        docker tag ${DOCKER_TAG} perconalab/pmm-server:${DOCKER_RC_TAG}
                        docker push perconalab/pmm-server:${DOCKER_RC_TAG}
                    fi
                    docker tag ${DOCKER_TAG} perconalab/pmm-server:${DOCKER_LATEST_TAG}
                    docker push ${DOCKER_TAG}
                    docker push perconalab/pmm-server:${DOCKER_LATEST_TAG}
                    echo "${DOCKER_LATEST_TAG}" > DOCKER_TAG
                    echo "${DOCKER_TAG}" > TIMESTAMP_TAG
                '''
                script {
                    env.IMAGE = sh(returnStdout: true, script: "cat DOCKER_TAG").trim()
                    env.TIMESTAMP_TAG = sh(returnStdout: true, script: "cat TIMESTAMP_TAG").trim()
                }
            }
        }
    }
    post {
        success {
            script {
                echo "Build finished: ${IMAGE}"
                if (params.DESTINATION == "testing") {
                    currentBuild.description = "RC Build v3 (arm64), Image:" + env.IMAGE
                }
            }
        }
        failure {
            script {
                echo "Pipeline failed"
            }
        }
    }
}
