library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent none
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH'
        )
        choice(
            choices: ['experimental', 'testing', 'laboratory'],
            description: 'Publish packages to repositories: testing for RC, experimental for 3-dev-latest, laboratory for FBs',
            name: 'DESTINATION'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
    }
    stages {
        stage('Build PMM Client') {
            agent {
                label 'agent-amd64-ol9'
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
                            echo "UPLOAD/${DESTINATION}/${JOB_NAME}/pmm/\$(cat VERSION)/${GIT_BRANCH}/\$(cat shortCommit)/${BUILD_NUMBER}" > uploadPath
                        '''
                        script {
                            def versionTag = sh(returnStdout: true, script: "cat VERSION").trim()
                            if (params.DESTINATION == "testing") {
                                env.DOCKER_RC_TAG = "${versionTag}-rc-amd64"
                            } else {
                                env.DOCKER_LATEST_TAG = "3-dev-latest-amd64"
                            }
                        }

                        archiveArtifacts 'uploadPath'
                        stash includes: 'uploadPath', name: 'uploadPath'
                        archiveArtifacts 'shortCommit'
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
                                ls -la "results/tarball" || :
                                aws s3 cp --only-show-errors --acl public-read results/tarball/pmm-client-*.tar.gz \
                                    s3://pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-latest-${BUILD_ID}.tar.gz
                                aws s3 cp --only-show-errors --acl public-read --copy-props none \
                                  s3://pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-latest-${BUILD_ID}.tar.gz \
                                  s3://pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-latest.tar.gz                                    
                            """
                        }
                        stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                        uploadTarball('binary')
                    }
                }
                stage('Build client docker') {
                    steps {
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            withEnv(['PATH_TO_SCRIPTS=' + env.PATH_TO_SCRIPTS, 'DOCKER_RC_TAG=' + env.DOCKER_RC_TAG, 'DOCKER_LATEST_TAG=' + env.DOCKER_LATEST_TAG]) {
                                sh '''
                                    echo "${PASS}" | docker login -u "${USER}" --password-stdin
                                    set -o xtrace

                                    ##export PUSH_DOCKER=1
                                    export DOCKER_CLIENT_TAG=perconalab/pmm-client:$(date -u '+%Y%m%d%H%M')-amd64

                                    ${PATH_TO_SCRIPTS}/build-client-docker

                                    if [ -n "${DOCKER_RC_TAG}" ]; then
                                        docker tag $DOCKER_CLIENT_TAG perconalab/pmm-client:${DOCKER_RC_TAG}
                                        docker push perconalab/pmm-client:${DOCKER_RC_TAG}
                                    else
                                        docker tag $DOCKER_CLIENT_TAG perconalab/pmm-client:${DOCKER_LATEST_TAG}
                                        docker push perconalab/pmm-client:${DOCKER_LATEST_TAG}
                                    fi

                                    docker push $DOCKER_CLIENT_TAG
                                '''
                            }
                        }
                    }
                }
                stage('Build client source rpm') {
                    steps {
                        sh """
                            ${PATH_TO_SCRIPTS}/build-client-srpm public.ecr.aws/e7j3v3n0/rpmbuild:3
                        """
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
                        stage('Build client binary rpm EL8') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-rpm oraclelinux:8"
                            }
                        }
                        stage('Build client binary rpm EL9') {
                            steps {
                                sh """
                                    ${PATH_TO_SCRIPTS}/build-client-rpm public.ecr.aws/e7j3v3n0/rpmbuild:3
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
                        sh "${PATH_TO_SCRIPTS}/build-client-sdeb ubuntu:focal"
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
                        stage('Build client binary deb Bookworm') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb debian:bookworm"
                            }
                        }
                        stage('Build client binary deb Jammy') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb ubuntu:jammy"
                            }
                        }
                        stage('Build client binary deb Focal') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb ubuntu:focal"
                            }
                        }
                        stage('Build client binary deb Noble') {
                            steps {
                                sh "${PATH_TO_SCRIPTS}/build-client-deb ubuntu:noble"
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
                unstash 'uploadPath'
                script {
                  env.UPLOAD_PATH = sh(returnStdout: true, script: "cat uploadPath").trim()
                }
                // Upload packages to the repo defined in `DESTINATION`
                // sync2ProdPMMClient(DESTINATION, 'yes')
                sync2ProdPMMClientRepo(DESTINATION, env.UPLOAD_PATH, 'pmm3-client')
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    script {
                        sh '''
                            ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com "
                                scp -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${UPLOAD_PATH}/binary/tarball/*.tar.gz jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/pmm/
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
                slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, pushed to ${DESTINATION} repo - ${BUILD_URL}"
                if (params.DESTINATION == "testing") {
                    env.TARBALL_URL = "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-latest-${BUILD_ID}.tar.gz"
                    currentBuild.description = "RC Build, tarball: " + env.TARBALL_URL
                    slackSend botUser: true,
                              channel: '#pmm-qa',
                              color: '#00FF00',
                              message: "[${JOB_NAME}]: ${BUILD_URL} RC Client build finished\nClient Tarball: ${env.TARBALL_URL}"
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
