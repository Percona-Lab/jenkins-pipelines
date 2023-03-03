library changelog: false, identifier: 'lib@PMM-6352-custom-build-el9', retriever: modernSCM([
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
                        env.DOCKER_LATEST_TAG_EL9 = "${versionTag}-el9-rc${BUILD_NUMBER}"
                        env.DOCKER_RC_TAG_EL9 = "${versionTag}-el9-rc"
                    } else {
                        env.DOCKER_LATEST_TAG = "dev-latest"
                        env.DOCKER_LATEST_TAG_EL9 = "el9-dev-latest"
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
                sh "${PATH_TO_SCRIPTS}/build-client-binary"
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                uploadTarball('binary')
            }
        }
        stage('Build client source rpm') {
            steps {
                sh "${PATH_TO_SCRIPTS}/build-client-srpm centos:7"
                stash includes: 'results/srpm/pmm*-client-*.src.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build client RPM') {
            parallel {
                stage('Build client binary EL7') {
                    steps {
                        sh """
                    set -o errexit

                    ${PATH_TO_SCRIPTS}/build-client-rpm centos:7

                    mkdir -p tmp/pmm-server/RPMS/
                    cp results/rpm/pmm*-client-*.rpm tmp/pmm-server/RPMS/
                """
                        stash includes: 'tmp/pmm-server/RPMS/*.rpm', name: 'rpms'
                        uploadRPM()
                    }
                }
                stage('Build client binary EL9') {
                    steps {
                        sh """
                    set -o errexit

                    ${PATH_TO_SCRIPTS}/build-client-rpm oraclelinux:9

                    mkdir -p tmp/pmm-server/RPMS/
                    cp results/rpm/pmm*-client-*.rpm tmp/pmm-server/RPMS/
                """
                        stash includes: 'tmp/pmm-server/RPMS/*.rpm', name: 'rpms_el9'
                        uploadRPM()
                    }
                }
            }
        }
                stage('Build Server EL7') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh """
                        set -o errexit

                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    """
                        }
                        stash includes: 'tmp/pmm-server/RPMS/*/*/*.rpm', name: 'rpms'
                        uploadRPM()
                    }
                }
                stage('Build Server EL9') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                        set -o errexit
                        export ROOT_DIR=${WORKSPACE}
                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                        export RPMBUILD_DIST="el9"
                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    '''
                        }
                        stash includes: 'tmp/pmm-server/RPMS/*/*/*.rpm', name: 'rpms_el9'
                        uploadRPM()
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
                                export DOCKER_TAG=perconalab/pmm-server:${VERSION}-el9
                            else
                                export DOCKER_TAG=perconalab/pmm-server:$(date -u '+%Y%m%d%H%M')-el9
                            fi

                            export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                            export DOCKERFILE=Dockerfile.el9
                            # Build a docker image
                            ${PATH_TO_SCRIPTS}/build-server-docker
                            if [ -n ${DOCKER_RC_TAG} ]; then
                                docker tag ${DOCKER_TAG} perconalab/pmm-server:${DOCKER_RC_TAG}
                                docker push perconalab/pmm-server:${DOCKER_RC_TAG}
                            fi
                            docker tag ${DOCKER_TAG} perconalab/pmm-server:${DOCKER_LATEST_TAG}
                            # docker push ${DOCKER_TAG}
                            docker push perconalab/pmm-server:${DOCKER_LATEST_TAG}
                            echo "${DOCKER_LATEST_TAG}" > DOCKER_TAG
                        '''
                        script {
                            env.IMAGE = sh(returnStdout: true, script: "cat DOCKER_TAG").trim()
                        }
                    }
                }
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
                                sh """
                                    set -o errexit

                                    export PUSH_DOCKER=1
                                    export DOCKER_TAG=perconalab/pmm-server:\$(date -u '+%Y%m%d%H%M')

                                    ${PATH_TO_SCRIPTS}/build-server-docker

                                    if [ ! -z \${DOCKER_RC_TAG+x} ]; then
                                        docker tag  \${DOCKER_TAG} perconalab/pmm-server:\${DOCKER_RC_TAG}
                                        docker push perconalab/pmm-server:\${DOCKER_RC_TAG}
                                    fi
                                    docker tag \${DOCKER_TAG} perconalab/pmm-server:\${DOCKER_LATEST_TAG}
                                    docker push \${DOCKER_TAG}
                                    docker push perconalab/pmm-server:\${DOCKER_LATEST_TAG}
                                """
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
        success {
            script {
                unstash 'IMAGE'
                def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                slackSend botUser: true, channel: '#releases-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE} - ${BUILD_URL}"
                slackSend botUser: true, channel: '@evgeniy', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE} - ${BUILD_URL}"
                if (params.DESTINATION == "testing") {
                    currentBuild.description = "RR Build, Docker Image: ${IMAGE}"
                    slackSend botUser: true, channel: '#releases-ci', color: '#00FF00', message: "[${JOB_NAME}]: RC build finished - ${IMAGE} - ${BUILD_URL}"
                }
            }
        }
        failure {
            script {
                slackSend botUser: true, channel: '#releases-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                slackSend botUser: true, channel: '#releases-qa', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
            }
        }
    }
}
