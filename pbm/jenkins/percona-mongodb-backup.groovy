library changelog: false, identifier: 'lib@pbmjen', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/vorsel/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget https://raw.githubusercontent.com/vorsel/percona-backup-mongodb/PBM-350_pbmjen/packaging/scripts/mongodb-backup_builder.sh -O mongodb-backup_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./mongodb-backup_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./mongodb-backup_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --version=${VERSION} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}"
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-backup-mongodb.git',
            description: 'URL for percona-mongodb-backup repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-mongodb-backup repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        string(
            defaultValue: '1.3.0',
            description: 'VERSION value',
            name: 'VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PBM source tarball') {
            steps {
                cleanUpWS()
                buildStage("centos:6", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-backup-mongodb.properties | cut -d = -f 2 | sed "s:BUILDS/::;s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-backup-mongodb.properties
                   cat uploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                //stash includes: 'awsUploadPath', name: 'awsUploadPath'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                //stash includes: 'source_tarball/*.tar.*', name: 'source.tarball'
                archiveArtifacts 'source_tarball/*.tar.*,test/*.properties'
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PBM generic source packages') {
            parallel {
                stage('Build PBM generic source rpm') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/source_tarball/*.tar.*': './', 'pbm/test/*.properties': './']
                        //unstash 'awsUploadPath'
                        //path_to_stash = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:6", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        //stash includes: 'srpm/*.src.*', name: 'rpms'
                        archiveArtifacts 'srpm/*.src.*'
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PBM generic source deb') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        //unarchive mapping: ['pbm/source_tarball/*.tar.*': './', 'pbm/test/*.properties': './']
                        buildStage("debian:stretch", "--build_src_deb=1")

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        //stash includes: 'source_deb/*', name: 'debs'
                        archiveArtifacts 'source_deb/*'
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage 
        stage('Build PBM rpms') {
            parallel {
                stage('Centos 6') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/srpm/*.src.*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:6", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        //stash includes: 'pbm/rpm/*.rpm', name: 'rpms'
                        archiveArtifacts 'rpm/*.rpm'
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/srpm/*.src.*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        //stash includes: 'pbm/rpm/*.rpm', name: 'rpms'
                        archiveArtifacts 'rpm/*.rpm'
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 8') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/srpm/*.src.*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:8", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH) 
                        //stash includes: 'pbm/rpm/*.rpm', name: 'rpms'
                        archiveArtifacts 'rpm/*.rpm'
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
            }
        }

        stage('Build PBM debs') {
            parallel {
                stage('Ubuntu Xenial(16.04)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/source_deb/*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:xenial", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        //stash includes: 'pbm/deb/*.deb', name: 'debs'
                        archiveArtifacts 'deb/*.deb'
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Bionic(18.04)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/source_deb/*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        //stash includes: 'pbm/deb/*.deb', name: 'debs'
                        archiveArtifacts 'deb/*.deb'
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/source_deb/*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        //stash includes: 'pbm/deb/*.deb', name: 'debs'
                        archiveArtifacts 'deb/*.deb'
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Jessie(8)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/source_deb/*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:jessie", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        //stash includes: 'pbm/deb/*.deb', name: 'debs'
                        archiveArtifacts 'deb/*.deb'
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Stretch(9)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/source_deb/*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:stretch", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        //stash includes: 'pbm/deb/*.deb', name: 'debs'
                        archiveArtifacts 'deb/*.deb'
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Buster(10)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/source_deb/*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        //stash includes: 'pbm/deb/*.deb', name: 'debs'
                        archiveArtifacts 'deb/*.deb'
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Build PBM binaries') {
//            parallel {
//                stage('Centos 6 tarball') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        //unarchive mapping: ['pbm/source_tarball/*.tar.*': './', 'pbm/test/*.properties': './']
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:6", "--build_tarball=1")

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        //stash includes: 'pbm/tarball/*.tar.*', name: 'binary.tarball'
                        archiveArtifacts 'tarball/*.tar.*'
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
//                }
//            }
        }

        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
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
