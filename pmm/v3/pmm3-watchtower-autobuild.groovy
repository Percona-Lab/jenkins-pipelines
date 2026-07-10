library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label params.USE_ONDEMAND ? 'agent-amd64-ondemand' : 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH'
        )
        string(
            defaultValue: 'dev-latest',
            description: 'Tag type for the watchtower build, e.g. "dev-latest", "rc"',
            name: 'TAG_TYPE'
        )
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
        parallelsAlwaysFailFast()
    }
    triggers {
        upstream upstreamProjects: 'pmm3-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    }
    environment {
        PATH_TO_WATCHTOWER = 'sources/watchtower/src/github.com/percona/watchtower'
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: false, branch: GIT_BRANCH, url: 'http://github.com/Percona-Lab/pmm-submodules'

                script {
                    // Extract the branch for 'watchtower' from the ci.yml file using yq
                    env.WATCHTOWER_BRANCH = sh(script: "yq e '.deps[] | select(.name == \"watchtower\") | .branch' ci.yml", returnStdout: true).trim()
                    echo "Watchtower branch: ${WATCHTOWER_BRANCH}"
                }

                script {
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }

                sh '''
                    git submodule update --init --jobs 10 ${PATH_TO_WATCHTOWER}
                    if [ -n ${WATCHTOWER_BRANCH} ]; then
                        git -C ${PATH_TO_WATCHTOWER} checkout ${WATCHTOWER_BRANCH}
                    fi
                    git submodule status
                '''

                script {
                    env.TIMESTAMP_TAG = "perconalab/watchtower:" + sh(script: "date -u '+%Y%m%d%H%M'", returnStdout: true).trim()
                    if (params.TAG_TYPE == "rc") {
                        env.WATCHTOWER_LATEST_TAG   = "perconalab/watchtower:${VERSION}-rc${BUILD_NUMBER}"
                        env.WATCHTOWER_RC_TAG       = "perconalab/watchtower:${VERSION}-rc"
                    } else if (params.TAG_TYPE.contains('pmm-watchtower-fb')) {
                        env.WATCHTOWER_LATEST_TAG   = params.TAG_TYPE
                    } else {
                        env.WATCHTOWER_LATEST_TAG   = "perconalab/watchtower:dev-latest"
                    }
                }
            }
        }
        stage('Build watchtower binaries') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        for arch in amd64 arm64; do
                            docker run --rm \
                                -v ${WORKSPACE}/${PATH_TO_WATCHTOWER}:/watchtower \
                                -e GOARCH=${arch} \
                                public.ecr.aws/e7j3v3n0/rpmbuild:3 \
                                bash -c "make -C /watchtower build && mv /watchtower/watchtower /watchtower/watchtower-${arch}"
                        done
                    '''
                }
            }
        }

        stage('Build watchtower container') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                        sh '''
                            echo "${PASS}" | docker login -u "${USER}" --password-stdin
                        '''
                }
                sh '''
                    set -o xtrace
                    set -o errexit

                    cd ${PATH_TO_WATCHTOWER}

                    docker buildx create --name multiarch-wt --driver docker-container --use || docker buildx use multiarch-wt

                    for arch in amd64 arm64; do
                        rm -rf ctx-${arch}
                        mkdir -p ctx-${arch}/dockerfiles
                        cp watchtower-${arch} ctx-${arch}/watchtower
                        cp dockerfiles/Dockerfile ctx-${arch}/dockerfiles/Dockerfile
                        docker buildx build \
                            --platform linux/${arch} \
                            -t ${TIMESTAMP_TAG}-${arch} \
                            -f ctx-${arch}/dockerfiles/Dockerfile \
                            --push ctx-${arch}
                    done

                    docker buildx imagetools create -t ${TIMESTAMP_TAG} \
                        ${TIMESTAMP_TAG}-amd64 ${TIMESTAMP_TAG}-arm64
                    if [ -n "${WATCHTOWER_RC_TAG}" ]; then
                        docker buildx imagetools create -t ${WATCHTOWER_RC_TAG} \
                            ${TIMESTAMP_TAG}-amd64 ${TIMESTAMP_TAG}-arm64
                    fi
                    docker buildx imagetools create -t ${WATCHTOWER_LATEST_TAG} \
                        ${TIMESTAMP_TAG}-amd64 ${TIMESTAMP_TAG}-arm64
                    echo "${WATCHTOWER_LATEST_TAG}" > WATCHTOWER_LATEST_TAG
                '''
                script {
                    env.IMAGE = sh(returnStdout: true, script: "cat ${PATH_TO_WATCHTOWER}/WATCHTOWER_LATEST_TAG").trim()
                }
            }
        }
    }
    post {
        success {
            script {
                slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE}, URL: ${BUILD_URL}"
                if (params.TAG_TYPE == "rc") {
                    currentBuild.description = "RC Watchtower Build, Image:" + env.IMAGE
                    slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: RC Watchtower build finished - ${IMAGE}, URL: ${BUILD_URL}"
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
