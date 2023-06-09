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
            description: 'Publish packages to repositories: testing (for RC), experimental: (for dev-latest), laboratory: (for FBs)',
            name: 'DESTINATION')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
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
                            if (params.DESTINATION == "testing") {
                                env.DOCKER_LATEST_TAG = "${versionTag}-rc${BUILD_NUMBER}"
                                env.DOCKER_RC_TAG = "${versionTag}-rc"
                            } else {
                                env.DOCKER_LATEST_TAG = "dev-latest"
                            }
                        }

                        archiveArtifacts 'uploadPath'
                        stash includes: 'uploadPath', name: 'uploadPath'
                        archiveArtifacts 'shortCommit'
                        slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
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
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh """
                                ${PATH_TO_SCRIPTS}/build-client-binary
                                aws s3 cp --acl public-read results/tarball/pmm2-client-*.tar.gz \
                                    s3://pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-latest-${BUILD_ID}.tar.gz
                            """
                        }
                        stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                        uploadTarball('binary')
                    }
                }
                stage('Build client docker') {
                    steps {
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            withEnv(['PATH_TO_SCRIPTS=' + env.PATH_TO_SCRIPTS]) {
                                sh '''
                                    echo "${PASS}" | docker login -u "${USER}" --password-stdin
                                    set -o xtrace

                                    export PUSH_DOCKER=1
                                    export DOCKER_CLIENT_TAG=perconalab/pmm-client:$(date -u '+%Y%m%d%H%M')

                                    ${PATH_TO_SCRIPTS}/build-client-docker

                                    if [ -n "${DOCKER_RC_TAG}" ]; then
                                        docker tag $DOCKER_CLIENT_TAG perconalab/pmm-client:${DOCKER_RC_TAG}
                                        docker push perconalab/pmm-client:${DOCKER_RC_TAG}
                                    fi
                                    docker tag $DOCKER_CLIENT_TAG perconalab/pmm-client:${DOCKER_LATEST_TAG}
                                    docker push $DOCKER_CLIENT_TAG
                                    docker push perconalab/pmm-client:${DOCKER_LATEST_TAG}
                                '''
                            }
                        }
                        stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                        archiveArtifacts 'results/docker/CLIENT_TAG'
                    }
                }
                stage('Build client source rpm') {
                    parallel {
                        stage('Build client source rpm EL7') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-srpm centos:7"
                            }
                        }
                        stage('Build client source rpm EL9') {
                            steps {
                                sh """
                                    ${PATH_TO_SCRIPTS}/build-client-srpm public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                                """
                            }
                        }
                    }
                    post {
                        success {
                            stash includes: 'results/srpm/pmm*-client-*.src.rpm', name: 'rpms'
                            uploadRPM()
                        }
                    }
                }
                stage('Build client binary rpms') {
                    parallel {
                        stage('Build client binary rpm EL7') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-rpm centos:7"
                                // sh "${PATH_TO_SCRIPTS}/build-client-rpm oraclelinux:8"
                                // sh "${PATH_TO_SCRIPTS}/build-client-rpm almalinux:9.0"
                            }
                        }
                        stage('Build client binary rpm EL8') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-rpm oraclelinux:8"
                            }
                        }
                        stage('Build client binary rpm EL9') {
                            steps {
                                sh """
                                    ${PATH_TO_SCRIPTS}/build-client-rpm public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                                """
                            }
                        }
                    }
                    post {
                        success {
                            stash includes: 'results/rpm/pmm*-client-*.rpm', name: 'rpms'
                            uploadRPM()
                        }
                    }
                }
                stage('Build client source deb') {
                    steps {
                        sh "${PATH_TO_SCRIPTS}/build-client-sdeb ubuntu:bionic"
                        stash includes: 'results/source_deb/*', name: 'debs'
                        uploadDEB()
                    }
                }
                stage('Build client binary debs') {
                    parallel {
                        stage('Build client binary deb Buster') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb debian:buster"
                            }
                        }
                        stage('Build client binary deb Bullseye') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb debian:bullseye"
                            }
                        }
                        stage('Build client binary deb Jammy') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb ubuntu:jammy"
                            }
                        }
                        stage('Build client binary deb Bionic') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb ubuntu:bionic"
                            }
                        }
                        stage('Build client binary deb Focal') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb ubuntu:focal"
                            }
                        }
                    }
                    post {
                        success {
                            stash includes: 'results/deb/*.deb', name: 'debs'
                            uploadDEB()
                        }
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
                label 'master'
            }
            steps {
                // sync packages
                sync2ProdPMMClient(DESTINATION, 'yes')
                sync2ProdPMMClientRepo(DESTINATION, 'yes')
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    script {
                        unstash 'uploadPath'
                        sh '''
                            PATH_TO_BUILD=$(cat uploadPath)
                            ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com "
                                scp -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${PATH_TO_BUILD}/binary/tarball/*.tar.gz jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/pmm/
                            "
                        '''
                    }  
                }
            }
        }
    }
    post {
        success {
            script {
                env.TARBALL_URL = "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/el9/pmm2-client/pmm2-client-latest-${BUILD_ID}.tar.gz"
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo - ${BUILD_URL}"
                    slackSend botUser: true, channel: '@nailya.kutlubaeva', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo"
                    if (params.DESTINATION == "testing") {
                      currentBuild.description = "RC Build, tarball: " + env.TARBALL_URL
                      slackSend botUser: true,
                                channel: '#pmm-qa',
                                color: '#00FF00',
                                message: "[${JOB_NAME}]: ${BUILD_URL} Release Candidate build finished\nClient Tarball: ${env.TARBALL_URL}"
                    }
            }
        }
        failure {
            script {
                echo "Pipeline failed"
                slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                slackSend botUser: true, channel: '#pmm-qa', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
            }
        }
    }
}
