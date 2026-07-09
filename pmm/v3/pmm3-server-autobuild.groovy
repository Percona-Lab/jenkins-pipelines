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
                        env.DOCKER_RC_TAG = "${versionTag}-rc-test"
                    } else {
                        env.DOCKER_LATEST_TAG = "3-dev-latest-test"
                    }
                }
            }
        }
        stage('Build pmm3 server') {
            parallel {
                stage('Build pmm3 server for amd64') {
                    steps {
                        script {
                            def pmmServerAmd64 = build job: 'arm64-pmm3-server-autobuild-amd-test', parameters: [
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
                            def pmmServerArm64 = build job: 'arm64-pmm3-server-autobuild-arm-test', parameters: [
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

                        TIMESTAMP_TAG=perconalab/pmm-server:$(date -u '+%Y%m%d%H%M')-test
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
        stage('Start API Tests') {
            parallel {
                stage('API tests on amd64') {
                    steps {
                        build job: 'arm64-pmm3-api-tests-test', propagate: false, parameters: [
                            string(name: 'AGENT_ARCH', value: 'amd64'),
                            string(name: 'DOCKER_VERSION', value: "perconalab/pmm-server:${env.IMAGE}")
                        ]
                    }
                }
                stage('API tests on arm64') {
                    steps {
                        build job: 'arm64-pmm3-api-tests-test', propagate: false, parameters: [
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
                echo "Multi-arch build finished: ${IMAGE} (timestamp: ${TIMESTAMP_TAG})"
                if (params.DESTINATION == "testing") {
                    currentBuild.description = "RC Build v3 (multi-arch), Image:" + env.IMAGE
                }
            }
        }
        failure {
            script {
                echo "Pipeline failed"
            }
        }
        cleanup {
            deleteDir()
        }
    }
}
