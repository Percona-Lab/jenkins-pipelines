library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-dbaas-cli repository',
            name: 'GIT_BRANCH')
         }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/percona-dbaas-cli.git'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                   
                    git rev-parse --short HEAD > shortCommit
                    echo "UPLOAD/experimental/${JOB_NAME}/dbaas/\$(cat VERSION | grep percona-dbaas-cli | awk '{print $2}')/${GIT_BRANCH}/\$(cat shortCommit)/${BUILD_NUMBER}" > uploadPath
                '''
                stash includes: 'uploadPath', name: 'uploadPath'
                archiveArtifacts 'shortCommit'
                archiveArtifacts 'uploadPath'
            }
        }
        stage('Build bdaas source') {
            steps {
                sh '''
                    sg docker -c "
                        build/bin/build-source
                    "
                '''
                stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                uploadTarball('source')
            }
        }
        stage('Build bdaas binary') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '2a84aea7-32a0-4598-9e8d-5153179097a9', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            build/bin/build-binary
                        "
                    '''
                }
                stash includes: 'results/tarball/*.tar.*', name: 'binary.tarball'
                uploadTarball('binary')
            }
        }
        stage('Build dbaas source rpm') {
            steps {
                sh 'sg docker -c "./build/bin/build-dbaas-srpm"'
                stash includes: 'results/srpm/*.src.*', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build dbaas rpm') {
            steps {
                sh 'sg docker -c "./build/bin/build-dbaas-rpm centos:8"'
                sh 'sg docker -c "./build/bin/build-dbaas-rpm centos:7"'
                sh 'sg docker -c "./build/bin/build-dbaas-rpm centos:6"'
                stash includes: 'results/rpm/*.rpm', name: 'rpms'
                uploadRPM()
            }
        }
        stage('Build dbaas source deb') {
            steps {
                sh 'sg docker -c "./build/bin/build-dbaas-sdeb debian:jessie"'
                stash includes: 'results/source_deb/*', name: 'debs'
                uploadDEB()
            }
        }
        stage('Build dbaas binary debs') {
            steps {
                sh 'sg docker -c "./build/bin/build-dbaas-deb debian:buster"'
                sh 'sg docker -c "./build/bin/build-dbaas-deb debian:jessie"'
                sh 'sg docker -c "./build/bin/build-dbaas-deb debian:stretch"'
                sh 'sg docker -c "./build/bin/build-dbaas-deb ubuntu:bionic"'
                sh 'sg docker -c "./build/bin/build-dbaas-deb ubuntu:xenial"'
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
            steps {
                // sync packages
                sync2ProdDBaas('experimental')
            }
        }
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
