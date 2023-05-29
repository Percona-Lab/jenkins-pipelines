library changelog: false, identifier: 'lib@PMM-el9-el7-build', retriever: modernSCM([
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
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    // triggers {
    //     upstream upstreamProjects: 'pmm2-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    // }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: true,
                    branch: GIT_BRANCH,
                    url: 'http://github.com/Percona-Lab/pmm-submodules'
                script {
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }
                sh '''
                    set -o errexit
                    git submodule update --init --jobs 10
                    git submodule status

                    git rev-parse --short HEAD > shortCommit
                    echo "UPLOAD/pmm2-components/yum/${DESTINATION}/${JOB_NAME}/pmm/${VERSION}/${GIT_BRANCH}/$(cat shortCommit)/${BUILD_NUMBER}" > uploadPath
                '''

                script {
                    if (params.DESTINATION == "testing") {
                        env.DOCKER_LATEST_TAG     = "${VERSION}-rc${BUILD_NUMBER}"
                        env.DOCKER_LATEST_TAG_EL7 = "${VERSION}-rc-el7${BUILD_NUMBER}"
                        env.DOCKER_RC_TAG         = "${VERSION}-rc"
                        env.DOCKER_RC_TAG_EL7     = "${VERSION}-rc-el7"
                    } else {
                        env.DOCKER_LATEST_TAG     = "dev-latest"
                        env.DOCKER_LATEST_TAG_EL7 = "dev-latest-el7"
                    }
                }

                archiveArtifacts 'uploadPath'
                stash includes: 'uploadPath', name: 'uploadPath'
                archiveArtifacts 'shortCommit'
                // slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
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
                sh "${PATH_TO_SCRIPTS}/build-client-binary"
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                uploadTarball('binary')
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
                        sh "${PATH_TO_SCRIPTS}/build-client-srpm public.ecr.aws/e7j3v3n0/rpmbuild:ol9"
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
                        sh """
                            set -o errexit

                            ${PATH_TO_SCRIPTS}/build-client-rpm centos:7

                            mkdir -p tmp/pmm-server/RPMS/
                            cp results/rpm/pmm*-client-*.rpm tmp/pmm-server/RPMS/
                        """
                    }
                }
                stage('Build client binary rpm EL9') {
                    steps {
                        sh """
                            set -o errexit

                            ${PATH_TO_SCRIPTS}/build-client-rpm public.ecr.aws/e7j3v3n0/rpmbuild:ol9

                            mkdir -p tmp/pmm-server/RPMS/
                            cp results/rpm/pmm*-client-*.rpm tmp/pmm-server/RPMS/
                        """
                    }
                }
            }
            post {
                success {
                    stash includes: 'tmp/pmm-server/RPMS/*.rpm', name: 'rpms'
                    uploadRPM()
                }
            }
        }
        stage('Build server packages') {
            parallel {
                stage('Build server packages EL7') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                set -o errexit

                                ${PATH_TO_SCRIPTS}/build-server-rpm-all
                            '''
                        }
                    }
                }
                stage('Build server packages EL9') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                set -o errexit

                                # These are used by `src/github.com/percona/pmm/build/scripts/vars`
                                ## export ROOT_DIR=${WORKSPACE}
                                export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                                export RPMBUILD_DIST="el9"
                                # Set this variable if we need to rebuils all rpms, for example to refresh stale assets stored in S3 build cache
                                # export FORCE_REBUILD=1

                                ${PATH_TO_SCRIPTS}/build-server-rpm-all
                            '''
                        }
                    }
                }
            }
            post {
                success {
                    stash includes: 'tmp/pmm-server/RPMS/*/*/*.rpm', name: 'rpms'
                    uploadRPM()
                }
            }
        }
        stage('Build server docker images') {
            parallel {
                stage('Build server docker EL7') {
                    steps {
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh '''
                                echo "${PASS}" | docker login -u "${USER}" --password-stdin
                            '''
                        }
                        withCredentials([aws(
                            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                            credentialsId: 'ECRRWUser',
                            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                            )]) {
                            sh '''
                                set -o errexit

                                #export PUSH_DOCKER=1
                                export DOCKER_TAG=perconalab/pmm-server:$(date -u '+%Y%m%d%H%M')

                                ${PATH_TO_SCRIPTS}/build-server-docker

                                if [ -n ${DOCKER_RC_TAG_EL7} ]; then
                                    docker tag ${DOCKER_TAG_EL7} perconalab/pmm-server:${DOCKER_RC_TAG_EL7}
                                    ## docker push perconalab/pmm-server:${DOCKER_RC_TAG_EL7}
                                fi
                                docker tag ${DOCKER_TAG_EL7} perconalab/pmm-server:${DOCKER_LATEST_TAG_EL7}
                                ## docker push ${DOCKER_TAG}
                                ## docker push perconalab/pmm-server:${DOCKER_LATEST_TAG_EL7}
                                echo "${DOCKER_LATEST_TAG_EL7}" > DOCKER_TAG_EL7
                            '''
                        }
                        script {
                            env.IMAGE_EL7 = sh(returnStdout: true, script: "cat DOCKER_TAG_EL7").trim()
                        }
                    }
                }
                stage('Build server docker EL9') {
                    steps {
                        withCredentials([
                            usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                                sh '''
                                    echo "${PASS}" | docker login -u "${USER}" --password-stdin
                                '''
                        }
                        sh '''
                            set -o errexit

                            # TODO: DOCKER_TAG for RC should be a real version, not a date
                            if [ -n ${DOCKER_RC_TAG} ]; then
                                export DOCKER_TAG=perconalab/pmm-server:${DOCKER_RC_TAG}
                            else
                                export DOCKER_TAG=perconalab/pmm-server:$(date -u '+%Y%m%d%H%M')
                            fi

                            export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                            export DOCKERFILE=Dockerfile.el9
                            # Build a docker image
                            ${PATH_TO_SCRIPTS}/build-server-docker

                            if [ -n ${DOCKER_RC_TAG} ]; then
                                docker tag ${DOCKER_TAG} perconalab/pmm-server:${DOCKER_RC_TAG}
                                ## docker push perconalab/pmm-server:${DOCKER_RC_TAG}
                            fi
                            docker tag ${DOCKER_TAG} perconalab/pmm-server:${DOCKER_LATEST_TAG}
                            ## docker push ${DOCKER_TAG}
                            ## docker push perconalab/pmm-server:${DOCKER_LATEST_TAG}
                            echo "${DOCKER_LATEST_TAG}" > DOCKER_TAG
                        '''
                        script {
                            env.IMAGE = sh(returnStdout: true, script: "cat DOCKER_TAG").trim()
                        }
                    }
                }
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
        success {
            script {
                // slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE} - ${IMAGE_EL7} - ${BUILD_URL}"
                if (params.DESTINATION == "testing") {
                    currentBuild.description = "RC Build, Image(EL9):" + env.IMAGE + ", Image(EL7):" + env.IMAGE_EL7
                    // slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: RC build finished - ${IMAGE} - ${IMAGE_EL7} - ${BUILD_URL}"
                }
            }
        }
        failure {
            script {
                echo "Pipeline failed"
                // slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                // slackSend botUser: true, channel: '#pmm-qa', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
            }
        }
    }
}
