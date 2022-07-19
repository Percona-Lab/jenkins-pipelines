library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent none
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
        stage('Build PMM Client') {
            agent {
                label 'agent-amd64'
            }
            stages {
                stage('Prepare') {
                    steps {
                        git poll: true, branch: GIT_BRANCH, url: 'http://github.com/Percona-Lab/pmm-submodules'
                        sh '''
                            git reset --hard
                            git clean -xdf
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
                        sh './build/bin/build-client-source'
                        stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                        uploadTarball('source')
                    }
                }
                stage('Build client binary') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                ./build/bin/build-client-binary
                                aws s3 cp --acl public-read results/tarball/pmm2-client-*.tar.gz \
                                    s3://pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-latest-${BUILD_ID}.tar.gz
                            '''
                        }
                        stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                        uploadTarball('binary')
                    }
                }
                stage('Build client docker') {
                    steps {
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh """
                                echo "${PASS}" | docker login -u "${USER}" --password-stdin
                            """
                        }
                        sh '''
                            set -o xtrace

                            export PUSH_DOCKER=1
                            export DOCKER_CLIENT_TAG=perconalab/pmm-client:$(date -u '+%Y%m%d%H%M')

                            ./build/bin/build-client-docker

                            if [ ! -z \${DOCKER_RC_TAG+x} ]; then
                                docker tag  \${DOCKER_CLIENT_TAG} perconalab/pmm-client:\${DOCKER_RC_TAG}
                                docker push perconalab/pmm-client:\${DOCKER_RC_TAG}
                                docker rmi perconalab/pmm-client:\${DOCKER_RC_TAG}
                            fi
                            docker tag  \${DOCKER_CLIENT_TAG} perconalab/pmm-client:\${DOCKER_LATEST_TAG}
                            docker push \${DOCKER_CLIENT_TAG}
                            docker push perconalab/pmm-client:\${DOCKER_LATEST_TAG}
                            docker rmi  \${DOCKER_CLIENT_TAG}
                            docker rmi  perconalab/pmm-client:\${DOCKER_LATEST_TAG}
                        '''
                        stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                        archiveArtifacts 'results/docker/CLIENT_TAG'
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
                        sh './build/bin/build-client-rpm centos:7'
                        sh './build/bin/build-client-rpm rockylinux:8'
                        sh './build/bin/build-client-rpm almalinux:9.0'
                        stash includes: 'results/rpm/pmm*-client-*.rpm', name: 'rpms'
                        uploadRPM()
                    }
                }

                stage('Build client source deb') {
                    steps {
                        sh './build/bin/build-client-sdeb ubuntu:bionic'
                        stash includes: 'results/source_deb/*', name: 'debs'
                        uploadDEB()
                    }
                }
                stage('Build client binary debs') {
                    steps {
                        sh './build/bin/build-client-deb debian:buster'
                        sh './build/bin/build-client-deb debian:stretch'
                        sh './build/bin/build-client-deb debian:bullseye'
                        sh './build/bin/build-client-deb ubuntu:jammy'
                        sh './build/bin/build-client-deb ubuntu:bionic'
                        sh './build/bin/build-client-deb ubuntu:focal'
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
                unstash 'binary.tarball'
                sh 'scp -i ~/.ssh/id_rsa_downloads -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no results/tarball/*.tar.* jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/pmm/'
            }
        }
    }
    post {
        always {
            script {
                env.TARBALL_URL = "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-latest-${BUILD_ID}.tar.gz"
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo - ${BUILD_URL}"
                    slackSend botUser: true, channel: '@nailya.kutlubaeva', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo"
                    if ("${DESTINATION}" == "testing")
                    {
                      currentBuild.description = "Release Candidate Build: "
                      slackSend botUser: true,
                                channel: '#pmm-qa',
                                color: '#00FF00',
                                message: "[${JOB_NAME}]: ${BUILD_URL} Release Candidate build finished\nClient Tarball: ${env.TARBALL_URL}"
                    }
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    slackSend botUser: true, channel: '#pmm-qa', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                }
            }
        }
    }
}
