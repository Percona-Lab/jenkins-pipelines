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
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Build PMM Client') {
            agent {
                label 'agent-arm64'
            }
            stages {
                stage('Prepare') {
                    steps {
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
                                    s3://pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-latest-arm-${BUILD_ID}.tar.gz
                            '''
                        }
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
                        sh './build/bin/build-client-rpm centos:7'
                        sh './build/bin/build-client-rpm rockylinux:8'
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
    }
}
