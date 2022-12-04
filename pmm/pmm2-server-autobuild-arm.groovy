library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'agent-arm64'
    }
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
                '''

                script {
                    env.DOCKER_LATEST_TAG = "dev-latest-arm"
                }

                archiveArtifacts 'uploadPath'
                stash includes: 'uploadPath', name: 'uploadPath'
                archiveArtifacts 'shortCommit'
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
                sh "./build/bin/build-client-binary"
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
            }
        }
        stage('Build client source rpm') {
            steps {
                sh "./build/bin/build-client-srpm centos:7"
                stash includes: 'results/srpm/pmm*-client-*.src.rpm', name: 'rpms'
            }
        }
        stage('Build client binary rpm') {
            steps {
                sh """
                    set -o errexit

                    ./build/bin/build-client-rpm centos:7

                    mkdir -p tmp/pmm-server/RPMS/
                    cp results/rpm/pmm*-client-*.rpm tmp/pmm-server/RPMS/
                """
                stash includes: 'tmp/pmm-server/RPMS/*.rpm', name: 'rpms'
            }
        }
        stage('Build server packages') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        export PATH=\$PATH:\$(pwd -P)/build/bin

                        # 1st-party
                        build-server-rpm percona-dashboards grafana-dashboards
                        build-server-rpm pmm-managed pmm
                        build-server-rpm percona-qan-api2 pmm
                        build-server-rpm pmm-update
                        build-server-rpm dbaas-controller
                        build-server-rpm dbaas-tools
                        build-server-rpm pmm-dump

                        # 3rd-party
                        build-server-rpm victoriametrics
                        build-server-rpm alertmanager
                        build-server-rpm grafana
                    """
                }
                stash includes: 'tmp/pmm-server/RPMS/*/*/*.rpm', name: 'rpms'
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
                    sh """
                        set -o errexit

                        export PUSH_DOCKER=1
                        export DOCKER_TAG=perconalab/pmm-server:\$(date -u '+%Y%m%d%H%M')-arm

                        ./build/bin/build-server-docker

                        docker tag \${DOCKER_TAG} perconalab/pmm-server:\${DOCKER_LATEST_TAG}
                        docker push \${DOCKER_TAG}
                        docker push perconalab/pmm-server:\${DOCKER_LATEST_TAG}
                        docker rmi  \${DOCKER_TAG}
                        docker rmi perconalab/pmm-server:\${DOCKER_LATEST_TAG}
                    """
                }
                stash includes: 'results/docker/TAG', name: 'IMAGE'
                archiveArtifacts 'results/docker/TAG'
            }
        }
    }
}
