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
            name: 'GIT_BRANCH'
        )
        choice(
            choices: ['experimental', 'testing'],
            description: 'Select the destination environment for tagging the image.',
            name: 'DESTINATION'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    environment {
        PATH_TO_WATCHTOWER = 'sources/watchtower/src/github.com/percona/watchtower'
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: true, branch: GIT_BRANCH, url: 'http://github.com/Percona-Lab/pmm-submodules'
                script {
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }
                sh '''
                    git reset --hard
                    git clean -xdf
                    git submodule update --init --jobs 10 ${PATH_TO_WATCHTOWER}
                    git submodule status

                '''
                script {
                    if (params.DESTINATION == "testing") {
                        env.WATCHTOWER_LATEST_TAG   = "${VERSION}-rc${BUILD_NUMBER}"
                        env.WATCHTOWER_RC_TAG       = "${VERSION}-rc"
                    } else {
                        env.WATCHTOWER_LATEST_TAG   = "dev-latest"
                    }
                }
            }
        }
        stage('Build watchtower binary') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        docker run --rm \
                            -v ${WORKSPACE}/${PATH_TO_WATCHTOWER}:/watchtower \
                            public.ecr.aws/e7j3v3n0/rpmbuild:3 \
                            /bin/bash -c "
                                cd /watchtower && \
                                GO111MODULE=on CGO_ENABLED=0 GOOS=linux go build -a -ldflags '-extldflags \\"-static\\" -X github.com/containrrr/watchtower/internal/meta.Version=$(git describe --tags || echo 'unknown')' .
                            "
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

                    export TIMESTAMP_TAG=perconalab/watchtower:$(date -u '+%Y%m%d%H%M')
                    cd ${PATH_TO_WATCHTOWER}
                    docker build -t ${TIMESTAMP_TAG} -f dockerfiles/Dockerfile .

                    if [ -n "${WATCHTOWER_RC_TAG}" ]; then
                        docker tag ${TIMESTAMP_TAG} perconalab/watchtower:${WATCHTOWER_RC_TAG}
                        docker push perconalab/watchtower:${WATCHTOWER_RC_TAG}
                    fi
                    docker tag ${TIMESTAMP_TAG} perconalab/pmm-server:${WATCHTOWER_LATEST_TAG}
                    docker push ${TIMESTAMP_TAG}
                    docker push perconalab/pmm-server:${WATCHTOWER_LATEST_TAG}
                    echo "${WATCHTOWER_LATEST_TAG}" > WATCHTOWER_LATEST_TAG
                '''
                script {
                    env.IMAGE = sh(returnStdout: true, script: "cat WATCHTOWER_LATEST_TAG").trim()
                }
            }
        }
    }
    post {        
        success {
            script {
                // slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE}, URL: ${BUILD_URL}"
                if (params.DESTINATION == "testing") {
                    currentBuild.description = "RC Watchtower Build, Image:" + env.IMAGE
                    // slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: RC Watchtower build finished - ${IMAGE}, URL: ${BUILD_URL}"
                }
            }
        }
        failure {
            script {
                echo "Pipeline failed"
                // slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
                // slackSend botUser: true, channel: '#pmm-qa', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
            }
        }
    }
}