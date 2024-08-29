pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        choice(
            choices: ['experimental', 'testing', 'laboratory'],
            description: 'Publish packages to repositories: testing (for RC), experimental: (for dev-latest), laboratory: (for FBs)',
            name: 'DESTINATION')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-submodules-rewind', threshold: hudson.model.Result.SUCCESS
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
                sh '''
                    git reset --hard
                    git clean -xdf
                    git submodule update --init --jobs 10
                    git submodule status
                '''
                script {
                    def versionTag = sh(returnStdout: true, script: "cat VERSION").trim()
                    if (params.DESTINATION == "testing") {
                        env.DOCKER_RC_TAG = "${versionTag}-rc"
                    } else {
                        env.DOCKER_LATEST_TAG = "dev-latest"
                    }
                }
            }
        }
        stage('Build pmm2 client for amd64') {
            steps {
                build job: 'pmm2-client-autobuilds-amd', parameters: [
                    string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                    string(name: 'DESTINATION', value: params.DESTINATION)
                ]
            }
        }
        stage('Build pmm2 client for arm64') {
            steps {
                build job: 'pmm2-client-autobuilds-arm', parameters: [
                    string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                    string(name: 'DESTINATION', value: params.DESTINATION)
                ]
            }
        }
        stage('Push pmm2 client multi-arch images') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                        set -o xtrace

                        if [ -n "${DOCKER_RC_TAG}" ]; then
                            docker manifest create perconalab/pmm-client:${DOCKER_RC_TAG} \
                                --amend perconalab/pmm-client:${DOCKER_RC_TAG}-amd64 \
                                --amend perconalab/pmm-client:${DOCKER_RC_TAG}-arm64

                            docker manifest annotate --arch amd64 perconalab/pmm-client:${DOCKER_RC_TAG} perconalab/pmm-client:${DOCKER_RC_TAG}-amd64
                            docker manifest annotate --arch arm64 perconalab/pmm-client:${DOCKER_RC_TAG} perconalab/pmm-client:${DOCKER_RC_TAG}-arm64

                            docker manifest push perconalab/pmm-client:${DOCKER_RC_TAG}
                        else
                            docker manifest create perconalab/pmm-client:${DOCKER_LATEST_TAG} \
                                --amend perconalab/pmm-client:${DOCKER_LATEST_TAG}-amd64 \
                                --amend perconalab/pmm-client:${DOCKER_LATEST_TAG}-arm64

                            docker manifest annotate --arch amd64 perconalab/pmm-client:${DOCKER_LATEST_TAG} perconalab/pmm-client:${DOCKER_LATEST_TAG}-amd64
                            docker manifest annotate --arch arm64 perconalab/pmm-client:${DOCKER_LATEST_TAG} perconalab/pmm-client:${DOCKER_LATEST_TAG}-arm64

                            docker manifest push perconalab/pmm-client:${DOCKER_LATEST_TAG}
                        fi
                    '''
                }
            }
        }
    }
    post {
        cleanup {
            deleteDir()
        }
    }
}
