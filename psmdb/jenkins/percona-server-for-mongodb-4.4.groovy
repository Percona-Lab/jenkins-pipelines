library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} --mongo_tools_tag=${MONGO_TOOLS_TAG} --jemalloc_tag=${JEMALLOC_TAG} ${STAGE_PARAM}"
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
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for  percona-server-mongodb repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'v4.4',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '4.4.2',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '4',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.2.1',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-3.2.11-3.1',
            description: 'JEMALLOC_TAG value',
            name: 'JEMALLOC_TAG')
        string(
            defaultValue: 'psmdb-44',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental\nrelease',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PSMDB source tarball') {
            steps {
                slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH}")
                cleanUpWS()
                buildStage("centos:7", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-44.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-44.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PSMDB generic source packages') {
            parallel {
                stage('Build PSMDB generic source rpm') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PSMDB generic source deb') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:xenial", "--build_src_deb=1")

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PSMDB RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Centos 7') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 8') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("centos:8", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Bionic(18.04)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Stretch(9)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:stretch", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Buster(10)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 binary tarball(glibc2.17)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_tarball=1")

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Centos 7 debug binary tarball(glibc2.17)') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--debug=1")

                        pushArtifactFolder("debug/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) binary tarball(glibc2.35)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_tarball=1")

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Ubuntu Jammy(22.04) debug binary tarball(glibc2.35)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--debug=1")

                        pushArtifactFolder("debug/", AWS_STASH_PATH)
                    }
                }
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
                sync2ProdAutoBuild(PSMDB_REPO, COMPONENT)
            }
        }

    }
    post {
        success {
            slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH}")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH}")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
