library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'large-amazon'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        choice(
            choices: ['experimental', 'testing', 'laboratory'],
            description: 'publish result package to: testing (internal RC), experimental: (dev-latest repository), laboratory: (internal repository for FB packages)',
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
                installDocker()

                git poll: true, branch: GIT_BRANCH, url: 'http://github.com/Percona-Lab/pmm-submodules'
                sh '''
                    git reset --hard
                    sudo git clean -xdf
                    git submodule update --init --jobs 10
                    git submodule status

                    git rev-parse --short HEAD > shortCommit
                    echo "UPLOAD/${DESTINATION}/${JOB_NAME}/pmm2/\$(cat VERSION)/${GIT_BRANCH}/\$(cat shortCommit)/${BUILD_NUMBER}" > uploadPath
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
                sh '''
                    sg docker -c "
                        env
                        ./build/bin/build-client-source
                    "
                '''
                stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                uploadTarball('source')
            }
        }
        stage('Build client binary') {
            steps {
                sh '''
                    sg docker -c "
                        env
                        ./build/bin/build-client-binary
                    "
                '''
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                uploadTarball('binary')
            }
        }
        stage('Build client docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u "${USER}" -p "${PASS}"
                        "
                    """
                }
                sh '''
                    sg docker -c "
                        set -o xtrace

                        export PUSH_DOCKER=1
                        export DOCKER_CLIENT_TAG=perconalab/pmm-client:$(date -u '+%Y%m%d%H%M')

                        ./build/bin/build-client-docker

                        if [ ! -z \${DOCKER_RC_TAG+x} ]; then
                            docker tag  \\${DOCKER_CLIENT_TAG} perconalab/pmm-client:\${DOCKER_RC_TAG}
                            docker push perconalab/pmm-client:\${DOCKER_RC_TAG}
                            docker rmi perconalab/pmm-client:\${DOCKER_RC_TAG}
                        fi
                        docker tag  \\${DOCKER_CLIENT_TAG} perconalab/pmm-client:\${DOCKER_LATEST_TAG}
                        docker push \\${DOCKER_CLIENT_TAG}
                        docker push perconalab/pmm-client:\${DOCKER_LATEST_TAG}
                        docker rmi  \\${DOCKER_CLIENT_TAG}
                        docker rmi  perconalab/pmm-client:\${DOCKER_LATEST_TAG}
                    "
                '''
                stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                archiveArtifacts 'results/docker/CLIENT_TAG'
            }
        }
        stage('Build client source rpm') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-srpm centos:7"'
                stash includes: 'results/srpm/pmm*-client-*.src.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build client binary rpm') {
            steps {
                sh '''
                    sg docker -c "
                        env
                        ./build/bin/build-client-rpm centos:7
                        ./build/bin/build-client-rpm centos:8
                    "
                '''
                stash includes: 'results/rpm/pmm*-client-*.rpm', name: 'rpms'
                uploadRPM()
            }
        }

        stage('Build client source deb') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-sdeb ubuntu:xenial"'
                stash includes: 'results/source_deb/*', name: 'debs'
                uploadDEB()
            }
        }
        stage('Build client binary debs') {
            steps {
                sh 'sg docker -c "./build/bin/build-client-deb debian:buster"'
                sh 'sg docker -c "./build/bin/build-client-deb debian:stretch"'
                sh 'sg docker -c "./build/bin/build-client-deb debian:bullseye"'
                sh 'sg docker -c "./build/bin/build-client-deb ubuntu:bionic"'
                sh 'sg docker -c "./build/bin/build-client-deb ubuntu:xenial"'
                sh 'sg docker -c "./build/bin/build-client-deb ubuntu:focal"'
                stash includes: 'results/deb/*.deb', name: 'debs'
                uploadDEB()
            }
        }
        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            agent {
                label 'virtualbox'
            }
            steps {
                // sync packages
                sync2ProdPMM(DESTINATION, 'yes')

                // upload tarball
                deleteDir()
                unstash 'binary.tarball'
                sh '''
                    scp -i ~/.ssh/id_rsa_downloads -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no results/tarball/*.tar.* jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/pmm/
                '''
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo - ${BUILD_URL}"
                    slackSend botUser: true, channel: '@nailya.kutlubaeva', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo"
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
