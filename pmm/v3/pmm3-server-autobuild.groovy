library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'agent-amd64-ol9'
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
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm3-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
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
                        env.DOCKER_LATEST_TAG     = "${VERSION}-rc${BUILD_NUMBER}"
                        env.DOCKER_RC_TAG         = "${VERSION}-rc"
                    } else {
                        env.DOCKER_LATEST_TAG     = "3-dev-latest"
                    }
                }

                archiveArtifacts 'uploadPath'
                stash includes: 'uploadPath', name: 'uploadPath'
                archiveArtifacts 'shortCommit'
                slackSend botUser: true, channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
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

                    export DOCKER_TAG=perconalab/pmm-server:$(date -u '+%Y%m%d%H%M')
                    export DOCKERFILE=Dockerfile.el9
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
                withCredentials([string(credentialsId: 'LAUNCHABLE_TOKEN', variable: 'LAUNCHABLE_TOKEN')]) {
                    sh '''
                        set -o errexit
                        pip3 install --user --upgrade launchable~=1.0 || true
                        launchable verify || true
                        echo "$(git submodule status)" || true

                        export DOCKER_IMAGE_ID=$(docker inspect ${IMAGE} -f "{{.Id}}") || true

                        launchable record build --name "${DOCKER_IMAGE_ID}" --lineage "${IMAGE}" || true
                    '''
                }
            }
        }
        stage('Trigger a devcontainer build') {
            when {
                // a guard to avoid unnecessary builds
                expression { params.GIT_BRANCH == "v3" && params.DESTINATION == "experimental" }
            }
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                    sh '''
                        # 'ref' is a required parameter, it should always equal 'v3' (or 'main' for v2)
                        curl -L -X POST \
                            -H "Accept: application/vnd.github+json" \
                            -H "Authorization: token ${GITHUB_API_TOKEN}" \
                            "https://api.github.com/repos/percona/pmm/actions/workflows/devcontainer.yml/dispatches" \
                            -d '{"ref":"v3"}'
                    '''
                }
            }
        }
        stage('Start API Tests') {
            steps {
                build job: 'pmm3-api-tests', propagate: false
            }
        }
    }
    post {
        success {
            script {
                slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE}, URL: ${BUILD_URL}"
                if (params.DESTINATION == "testing") {
                    currentBuild.description = "RC Build v3, Image:" + env.IMAGE
                    slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: RC build finished - ${IMAGE}, URL: ${BUILD_URL}"
                }
            }
        }
        failure {
            script {
                echo "Pipeline failed"
                slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
                slackSend botUser: true, channel: '#pmm-qa', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
            }
        }
    }
}
