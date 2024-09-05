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
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} --mongo_tools_tag=${MONGO_TOOLS_TAG} ${STAGE_PARAM}"
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
        label 'docker-64gb'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for  percona-server-mongodb repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'v6.0',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '6.0.0',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.5.4',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-60',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'no\nyes',
            description: 'Enable all pro features',
            name: 'FULL_FEATURED')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
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
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("centos:7", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-60.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-60.properties
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
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("centos:7", "--build_src_rpm=1 --full_featured=1")
                            } else {
                                buildStage("centos:7", "--build_src_rpm=1")
                            }
                        }

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PSMDB generic source deb') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("debian:buster", "--build_src_deb=1 --full_featured=1")
                            } else {
                                buildStage("debian:buster", "--build_src_deb=1")
                            }
                        }

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
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("centos:7", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("centos:7", "--build_rpm=1")
                            }
                        }

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:8", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:8", "--build_rpm=1")
                            }
                        }

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }
                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:focal", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:focal", "--build_deb=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:noble", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Buster(10)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("debian:buster", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("debian:buster", "--build_deb=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("debian:bullseye", "--build_deb=1 --full_featured=1")
                            } else {
                                buildStage("debian:bullseye", "--build_deb=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 binary tarball(glibc2.17)') {
                    when {
                        expression { env.FULL_FEATURED != 'yes' }
                    }
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("centos:7", "--build_tarball=1")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
/*
                stage('Centos 7 debug binary tarball(glibc2.17)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                echo "The step is skipped ..."
                                buildStage("centos:7", "--debug=1 --full_featured=1")
                            } else {
                                buildStage("centos:7", "--debug=1")
                            }
                        }

                        pushArtifactFolder("debug/", AWS_STASH_PATH)
                    }
                }
*/
                stage('Centos 8 binary tarball(glibc2.28)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:8", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:8", "--build_tarball=1")
                            }
                            pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                        }
                    }
                }
                stage('Oracle Linux 9 binary tarball(glibc2.34)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("oraclelinux:9", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1")
                            }
                            pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                        }
                    }
                }
                stage('Ubuntu Focal(20.04) binary tarball(glibc2.31)') {
                    when {
                        expression { env.FULL_FEATURED != 'yes' }
                    }
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "--build_tarball=1")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Ubuntu Jammy(22.04) binary tarball(glibc2.35)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1")
                            }
                            pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                        }
                    }
                }
                stage('Ubuntu Noble(24.04) binary tarball(glibc2.39)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                buildStage("ubuntu:noble", "--build_tarball=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_tarball=1")
                            }
                            pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                        }
                    }
                }
/*
                stage('Ubuntu Jammy(22.04) debug binary tarball(glibc2.35)') {
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FULL_FEATURED == 'yes') {
                                echo "The step is skipped ..."
                                buildStage("ubuntu:jammy", "--debug=1 --full_featured=1")
                            } else {
                                buildStage("ubuntu:jammy", "--debug=1")
                            }
                        }

                        pushArtifactFolder("debug/", AWS_STASH_PATH)
                    }
                }
*/
                stage('Debian Buster(10) binary tarball(glibc2.28)') {
                    when {
                        expression { env.FULL_FEATURED != 'yes' }
                    }
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:buster", "--build_tarball=1")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
                stage('Debian Bullseye(11) binary tarball(glibc2.31)') {
                    when {
                        expression { env.FULL_FEATURED != 'yes' }
                    }
                    agent {
                        label 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_tarball=1")
                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                        uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
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
                script {
                    if (env.FULL_FEATURED == 'yes') {
                        // Replace by a new procedure when it's ready
                        sync2PrivateProdAutoBuild(PSMDB_REPO+"-pro", COMPONENT)
                    } else {
                        sync2ProdAutoBuild(PSMDB_REPO, COMPONENT)
                    }
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    if (env.FULL_FEATURED == 'yes') {
                        try {
                            uploadTarballToDownloadsTesting("psmdb-gated", "${PSMDB_VERSION}")
                        }
                        catch (err) {
                            echo "Caught: ${err}"
                            currentBuild.result = 'UNSTABLE'
                        }
                    } else {
                        try {
                            uploadTarballToDownloadsTesting("psmdb", "${PSMDB_VERSION}")
                        }
                        catch (err) {
                            echo "Caught: ${err}"
                            currentBuild.result = 'UNSTABLE'
                        }
                    }
                }
            }
        }

    }
    post {
        success {
            slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
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
