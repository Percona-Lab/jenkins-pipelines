library changelog: false, identifier: 'lib@ENG-1241_build_pbm_in_kubernetes', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/vorsel/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        uname -a || true
        cat /etc/os-release || true
        mkdir test
        curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/packaging/scripts/mongodb-backup_builder.sh -o mongodb-backup_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
            set -o xtrace
            cd \${build_dir}
            bash -x ./mongodb-backup_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./mongodb-backup_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --version=${VERSION} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
    """
}

void cleanUpWS() {
    sh """
        rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        kubernetes {
            inheritFrom 'runner'
        }
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-backup-mongodb.git',
            description: 'URL for percona-mongodb-backup repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'dev',
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
            defaultValue: '2.5.0',
            description: 'VERSION value',
            name: 'VERSION')
        string(
            defaultValue: 'pbm',
            description: 'PBM repo name',
            name: 'PBM_REPO')
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
        stage('Create PBM source tarball') {
            agent {
                kubernetes {
                    inheritFrom 'centos-7-x64'
                }
            }
            steps {
                container('centos-7-x64') {
                    installAWScliv2()
                    slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                    cleanUpWS()
                        buildStage("centos:7", "--get_sources=1")
                        sh '''
                        REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-backup-mongodb.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
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
		    //stash includes: 'source_tarball/*.tar.*', name: 'source.tarball'
		    //uploadTarball('source')
                    pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                    uploadTarballfromAWSrke("source_tarball/", AWS_STASH_PATH, 'source')
                }
            }
        }
        stage('Build PBM generic source packages') {
            parallel {
                stage('Build PBM generic source rpm') {
                    agent {
                        kubernetes {
                            inheritFrom 'centos-7-x64'
                        }
                    }
                    steps {
                        container('centos-7-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                            buildStage("centos:7", "--build_src_rpm=1")

                            pushArtifactFolder("srpm/", AWS_STASH_PATH)
                            uploadRPMfromAWSrke("srpm/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Build PBM generic source deb') {
                    agent {
                        kubernetes {
                            inheritFrom 'buster-x64'
                        }
                    }
                    steps {
                        container('buster-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                            buildStage("debian:buster", "--build_src_deb=1")

                            pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                            uploadDEBfromAWSrke("source_deb/", AWS_STASH_PATH)
                        }
                    }
                }
            }  //parallel
        } // stage
        stage('Build PBM RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Centos 7') {
                    agent {
                        kubernetes {
                            inheritFrom 'centos-7-x64'
                        }
                    }
                    steps {
                        container('centos-7-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("srpm/", AWS_STASH_PATH)
                            buildStage("centos:7", "--build_rpm=1")

                            pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            uploadRPMfromAWSrke("rpm/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Oracle Linux 8') {
                    agent {
                        kubernetes {
                            inheritFrom 'ol-8-x64'
                        }
                    }
                    steps {
                        container('ol-8-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("srpm/", AWS_STASH_PATH)
                            buildStage("oraclelinux:8", "--build_rpm=1")

                            pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            uploadRPMfromAWSrke("rpm/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        kubernetes {
                            inheritFrom 'ol-9-x64'
                        }
                    }
                    steps {
                        container('ol-9-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("srpm/", AWS_STASH_PATH)
                            buildStage("oraclelinux:9", "--build_rpm=1")

                            pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            uploadRPMfromAWSrke("rpm/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Bionic(18.04)') {
                    agent {
                        kubernetes {
                            inheritFrom 'bionic-x64'
                        }
                    }
                    steps {
                        container('bionic-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            buildStage("ubuntu:bionic", "--build_deb=1")

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWSrke("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        kubernetes {
                            inheritFrom 'focal-x64'
                        }
                    }
                    steps {
                        container('focal-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            buildStage("ubuntu:focal", "--build_deb=1")

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWSrke("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        kubernetes {
                            inheritFrom 'jammy-x64'
                        }
                    }
                    steps {
                        container('jammy-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            buildStage("ubuntu:jammy", "--build_deb=1")

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWSrke("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        kubernetes {
                            inheritFrom 'noble-x64'
                        }
                    }
                    steps {
                        container('noble-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            buildStage("ubuntu:noble", "--build_deb=1")

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWSrke("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Debian Buster(10)') {
                    agent {
                        kubernetes {
                            inheritFrom 'buster-x64'
                        }
                    }
                    steps {
                        container('buster-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            buildStage("debian:buster", "--build_deb=1")

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWSrke("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        kubernetes {
                            inheritFrom 'bullseye-x64'
                        }
                    }
                    steps {
                        container('bullseye-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            buildStage("debian:bullseye", "--build_deb=1")

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWSrke("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        kubernetes {
                            inheritFrom 'bookworm-x64'
                        }
                    }
                    steps {
                        container('bookworm-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            buildStage("debian:bookworm", "--build_deb=1")

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWSrke("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Centos 7 tarball') {
                    agent {
                        kubernetes {
                            inheritFrom 'centos-7-x64'
                        }
                    }
                    steps {
                        container('centos-7-x64') {
                            installAWScliv2()
                            cleanUpWS()
                            popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                            buildStage("centos:7", "--build_tarball=1")

                            pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            uploadTarballfromAWSrke("tarball/", AWS_STASH_PATH, 'binary')
                        }
                    }
                }
            }
        }

        stage('Sign packages') {
            steps {
                signRPMrke()
                signDEBrke()
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuildrke(PBM_REPO, COMPONENT)
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
                rm -rf ./*
            '''
            deleteDir()
        }
    }
}
