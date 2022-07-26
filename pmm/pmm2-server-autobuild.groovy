library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        choice(
            // default is choices.get(0) - experimental
            choices: ['experimental', 'testing', 'laboratory'],
            description: 'Repo component to push packages to',
            name: 'DESTINATION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: true,
                    branch: GIT_BRANCH,
                    url: 'http://github.com/Percona-Lab/pmm-submodules'
                sh '''
                    set -o errexit
                    git submodule update --init --jobs 10
                    git submodule status

                    git rev-parse --short HEAD > shortCommit
                    echo "UPLOAD/pmm2-components/yum/${DESTINATION}/${JOB_NAME}/pmm/\$(cat VERSION)/${GIT_BRANCH}/\$(cat shortCommit)/${BUILD_NUMBER}" > uploadPath
                '''

                script {
                    def versionTag = sh(returnStdout: true, script: "cat VERSION").trim()
                    if ("${DESTINATION}" == "testing") {
                        env.DOCKER_LATEST_TAG = "${versionTag}-rc${BUILD_NUMBER}"
                        env.DOCKER_RC_TAG = "${versionTag}-rc"
                    } else {
                        env.DOCKER_LATEST_TAG = "dev-latest"
                    }
                }

                archiveArtifacts 'uploadPath'
                stash includes: 'uploadPath', name: 'uploadPath'
                archiveArtifacts 'shortCommit'
                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Build client source') {
            steps {
                sh './build/bin/build-client-source'
                stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                uploadTarball('source')
            }
        }
        stage('Build client binary') {
            steps {
                sh './build/bin/build-client-binary'
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                uploadTarball('binary')
            }
        }
        stage('Build client source rpm') {
            steps {
                sh './build/bin/build-client-srpm centos:7'
                stash includes: 'results/srpm/pmm*-client-*.src.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build client binary rpm') {
            steps {
                sh '''
                    sg docker -c "
                        set -o errexit

                        ./build/bin/build-client-rpm centos:7

                        mkdir -p tmp/pmm-server/RPMS/
                        cp results/rpm/pmm*-client-*.rpm tmp/pmm-server/RPMS/
                    "
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

                        export PATH=$PATH:$(pwd -P)/build/bin

                        # 1st-party
                        build-server-rpm percona-dashboards grafana-dashboards
                        build-server-rpm pmm-managed pmm
                        build-server-rpm percona-qan-api2 qan-api2
                        build-server-rpm pmm-server
                        build-server-rpm pmm-update
                        build-server-rpm dbaas-controller
                        build-server-rpm dbaas-tools
                        build-server-rpm pmm-dump

                        # 3rd-party
                        build-server-rpm victoriametrics
                        build-server-rpm alertmanager
                        build-server-rpm grafana
                    '''
                }
                stash includes: 'tmp/pmm-server/RPMS/*/*/*.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build server docker') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'hub.docker.com',
                    passwordVariable: 'PASS',
                    usernameVariable: 'USER'
                    )]) {
                    sh """
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                    """
                }
                withCredentials([aws(
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'ECRRWUser',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                    sh '''
                        set -o errexit

                        export PUSH_DOCKER=1
                        export DOCKER_TAG=perconalab/pmm-server:$(date -u '+%Y%m%d%H%M')

                        ./build/bin/build-server-docker

                        if [ ! -z \${DOCKER_RC_TAG+x} ]; then
                            docker tag  \${DOCKER_TAG} perconalab/pmm-server:${DOCKER_RC_TAG}
                            docker push perconalab/pmm-server:\${DOCKER_RC_TAG}
                            docker rmi perconalab/pmm-server:\${DOCKER_RC_TAG}
                        fi
                        docker tag \${DOCKER_TAG} perconalab/pmm-server:${DOCKER_LATEST_TAG}
                        docker push \${DOCKER_TAG}
                        docker push perconalab/pmm-server:${DOCKER_LATEST_TAG}
                        docker rmi  \${DOCKER_TAG}
                        docker rmi perconalab/pmm-server:${DOCKER_LATEST_TAG}
                    '''
                }
                stash includes: 'results/docker/TAG', name: 'IMAGE'
                archiveArtifacts 'results/docker/TAG'
            }
        }
        stage('Sign packages') {
            steps {
                signRPM()
            }
        }
        stage('Push to public repository') {
            steps {
                sync2ProdPMM("pmm2-components/yum/${DESTINATION}", 'no')
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    unstash 'IMAGE'
                    def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE} - ${BUILD_URL}"
                    slackSend botUser: true, channel: '@nailya.kutlubaeva', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE}"
                    if ("${DESTINATION}" == "testing")
                    {
                      currentBuild.description = "Release Candidate Build"
                      slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: ${BUILD_URL} Release Candidate build finished"
                    }
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    slackSend botUser: true, channel: '#pmm-qa', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                }
            }
        }
    }
}
