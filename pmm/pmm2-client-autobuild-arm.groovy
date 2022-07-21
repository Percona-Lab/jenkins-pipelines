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
    environment {
        GOARCH = "arm64"
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
                        '''

                        archiveArtifacts 'shortCommit'
                        slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                    }
                }
                stage('Build client source') {
                    steps {
                        sh './build/bin/build-client-source'
                        stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                    }
                }
                stage('Build client binary') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                ./build/bin/build-client-binary
                                aws s3 cp --acl public-read results/tarball/pmm2-client-*.tar.gz \
                                    s3://pmm-build-cache/pmm2-client/ARM/pmm2-client-latest-arm-${BUILD_ID}.tar.gz
                            '''
                        }
                        stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                    }
                }
                stage('Build client source rpm') {
                    steps {
                        sh '''
                            ./build/bin/build-client-srpm centos:7
                        '''
                    }
                }
                stage('Build client binary rpm') {
                    steps {
                        sh './build/bin/build-client-rpm centos:7'
                        sh './build/bin/build-client-rpm rockylinux:8'
                        sh './build/bin/build-client-rpm almalinux:9.0'
                        sh 'aws s3 cp --recursive --acl public-read --include "pmm*-client-*.rpm" results/rpm/ \
                                s3://pmm-build-cache/pmm2-client/ARM/'
                        stash includes: 'results/rpm/pmm*-client-*.rpm', name: 'rpms'
                    }
                }

                stage('Build client source deb') {
                    steps {
                        sh './build/bin/build-client-sdeb ubuntu:bionic'
                        stash includes: 'results/source_deb/*', name: 'debs'
                    }
                }
                stage('Build client binary debs') {
                    steps {
                        sh './build/bin/build-client-deb debian:buster'
                        sh './build/bin/build-client-deb debian:stretch'
                        sh './build/bin/build-client-deb debian:bullseye'
                        sh './build/bin/build-client-deb ubuntu:bionic'
                        sh './build/bin/build-client-deb ubuntu:focal'
                        sh 'aws s3 cp --recursive --acl public-read --include "*.deb" results/deb/ \
                                s3://pmm-build-cache/pmm2-client/ARM/'
                        stash includes: 'results/deb/*.deb', name: 'debs'
                    }
                }
            }
        }
    }

}
