library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label params.USE_ONDEMAND ? 'cli-ondemand' : 'cli'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        choice(
            choices: ['experimental', 'testing'],
            description: 'Publish packages to repositories: testing (for RC), experimental (for dev-latest)',
            name: 'DESTINATION')
        booleanParam(
            defaultValue: false,
            description: 'Use on-demand instances instead of spot (for RC/Release builds)',
            name: 'USE_ONDEMAND'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    triggers {
        upstream upstreamProjects: 'pmm3-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Prepare') {
            steps {
                checkout([$class: 'GitSCM',
                          branches: [[name: "*/${params.GIT_BRANCH}"]],
                          extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]],
                          userRemoteConfigs: [[url: 'https://github.com/Percona-Lab/pmm-submodules']]
                ])
                script {
                    def versionTag = sh(returnStdout: true, script: "cat VERSION").trim()
                    if (params.DESTINATION == "testing") {
                        env.DOCKER_RC_TAG = "${versionTag}-rc"
                    } else {
                        env.DOCKER_LATEST_TAG = "3-dev-latest"
                    }
                }
                slackSend botUser: true, channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Build pmm3 server') {
            parallel {
                stage('Build pmm3 server for amd64') {
                    steps {
                        script {
                            def pmmServerAmd64 = build job: 'pmm3-server-autobuild-amd', parameters: [
                                string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                                string(name: 'DESTINATION', value: params.DESTINATION),
                                booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND)
                            ]
                            env.TIMESTAMP_TAG_AMD64 = pmmServerAmd64.buildVariables.TIMESTAMP_TAG
                        }
                    }
                }
                stage('Build pmm3 server for arm64') {
                    steps {
                        script {
                            def pmmServerArm64 = build job: 'pmm3-server-autobuild-arm', parameters: [
                                string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                                string(name: 'DESTINATION', value: params.DESTINATION),
                                booleanParam(name: 'USE_ONDEMAND', value: params.USE_ONDEMAND)
                            ]
                            env.TIMESTAMP_TAG_ARM64 = pmmServerArm64.buildVariables.TIMESTAMP_TAG
                        }
                    }
                }
            }
        }
        stage('Push pmm3 server multi-arch images') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                        set -o xtrace

                        TIMESTAMP_TAG=perconalab/pmm-server:$(date -u '+%Y%m%d%H%M')
                        docker buildx imagetools create -t ${TIMESTAMP_TAG} \
                            ${TIMESTAMP_TAG_AMD64} \
                            ${TIMESTAMP_TAG_ARM64}
                        echo "${TIMESTAMP_TAG}" > TIMESTAMP_TAG

                        if [ -n "${DOCKER_RC_TAG}" ]; then
                            docker buildx imagetools create -t perconalab/pmm-server:${DOCKER_RC_TAG} \
                                perconalab/pmm-server:${DOCKER_RC_TAG}-amd64 \
                                perconalab/pmm-server:${DOCKER_RC_TAG}-arm64
                            echo "${DOCKER_RC_TAG}" > DOCKER_TAG
                        else
                            docker buildx imagetools create -t perconalab/pmm-server:${DOCKER_LATEST_TAG} \
                                perconalab/pmm-server:${DOCKER_LATEST_TAG}-amd64 \
                                perconalab/pmm-server:${DOCKER_LATEST_TAG}-arm64
                            echo "${DOCKER_LATEST_TAG}" > DOCKER_TAG
                        fi
                    '''
                }
                script {
                    env.IMAGE = sh(returnStdout: true, script: "cat DOCKER_TAG").trim()
                    env.TIMESTAMP_TAG = sh(returnStdout: true, script: "cat TIMESTAMP_TAG").trim()
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
                        # 'ref' is a required parameter, it should always equal 'main' (the default branch of percona/pmm)
                        curl -L -X POST \
                            -H "Accept: application/vnd.github+json" \
                            -H "Authorization: token ${GITHUB_API_TOKEN}" \
                            "https://api.github.com/repos/percona/pmm/actions/workflows/devcontainer.yml/dispatches" \
                            -d '{"ref":"main"}'
                    '''
                }
            }
        }
        stage('Start API Tests') {
            parallel {
                stage('API tests on amd64') {
                    steps {
                        build job: 'pmm3-api-tests', propagate: false, parameters: [
                            string(name: 'AGENT_ARCH', value: 'amd64'),
                            string(name: 'DOCKER_VERSION', value: "perconalab/pmm-server:${env.IMAGE}")
                        ]
                    }
                }
                stage('API tests on arm64') {
                    steps {
                        build job: 'pmm3-api-tests', propagate: false, parameters: [
                            string(name: 'AGENT_ARCH', value: 'arm64'),
                            string(name: 'DOCKER_VERSION', value: "perconalab/pmm-server:${env.IMAGE}")
                        ]
                    }
                }
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
        cleanup {
            deleteDir()
        }
    }
}
