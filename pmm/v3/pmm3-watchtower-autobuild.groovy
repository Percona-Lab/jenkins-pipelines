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
        string(
            defaultValue: 'dev-latest',
            description: 'Tag type for the watchtower build, e.g. "dev-latest", "rc"',
            name: 'TAG_TYPE'
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
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }
                sh '''
                    git submodule update --init --jobs 10 ${PATH_TO_WATCHTOWER}
                    git submodule status
                    cd ${PATH_TO_WATCHTOWER}
                    git fetch --no-tags
                    git checkout ${GIT_BRANCH}
                    git rev-parse --short HEAD
                    cd -
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
        stage('Build watchtower binary') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        docker run --rm \
                            -v ${WORKSPACE}/${PATH_TO_WATCHTOWER}:/watchtower \
                            public.ecr.aws/e7j3v3n0/rpmbuild:3 \
                            make -C /watchtower build
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

                    cd ${PATH_TO_WATCHTOWER}
                    docker build -t ${TIMESTAMP_TAG} -f dockerfiles/Dockerfile .
                    docker push ${TIMESTAMP_TAG}

                    if [ -n "${WATCHTOWER_RC_TAG}" ]; then
                        docker tag ${TIMESTAMP_TAG} ${WATCHTOWER_RC_TAG}
                        docker push ${WATCHTOWER_RC_TAG}
                    fi
                    docker tag ${TIMESTAMP_TAG} ${WATCHTOWER_LATEST_TAG}
                    docker push ${WATCHTOWER_LATEST_TAG}
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
