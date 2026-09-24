library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        git clone ${PACKAGING_REPO} valkey-packaging
        cd valkey-packaging
        git checkout ${PACKAGING_BRANCH}
        cd ..
        pwd -P
        export build_dir=\$(pwd -P)
        sudo docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./valkey-packaging/scripts/valkey_builder.sh --builddir=\${build_dir}/test --admin_deps
            bash -x ./valkey-packaging/scripts/valkey_builder.sh --builddir=\${build_dir}/test --admin_repo=${GIT_REPO} --admin_branch=${GIT_BRANCH} --admin_version=${VALKEY_ADMIN_VERSION} --release=${VALKEY_ADMIN_RELEASE} ${STAGE_PARAM}"
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
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/valkey-io/valkey-admin.git',
            description: 'URL for valkey-admin repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'v1.1.1',
            description: 'Tag/Branch for valkey-admin repository (upstream tags carry a leading "v")',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1.1.1',
            description: 'valkey-admin version value',
            name: 'VALKEY_ADMIN_VERSION')
        string(
            defaultValue: '1',
            description: 'valkey-admin release value',
            name: 'VALKEY_ADMIN_RELEASE')
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/valkey-packaging.git',
            description: 'URL for valkey-packaging repository (holds the valkey-admin/ packaging + builder)',
            name: 'PACKAGING_REPO')
        string(
            defaultValue: 'valkey-admin-packaging',
            description: 'Branch/Tag for valkey-packaging repository',
            name: 'PACKAGING_BRANCH')
        string(
            defaultValue: 'valkey-admin',
            description: 'valkey-admin repo name (target product repo for sync)',
            name: 'VALKEY_ADMIN_REPO')
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
        stage('Create valkey-admin source tarball') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_admin_sources")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/valkey-admin.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/valkey-admin.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build valkey-admin generic source packages') {
            parallel {
                stage('Build valkey-admin generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_admin_src_rpm")

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build valkey-admin generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_admin_src_deb")
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build valkey-admin RPMs/DEBs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_admin_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_admin_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_admin_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_admin_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_admin_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_admin_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_admin_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_admin_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Resolute(26.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:resolute-20260627", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Resolute(26.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:resolute-20260627", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_admin_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
            }
        }

        stage('Sign packages') {
            steps {
                signRPM(params.CLOUD)
                signDEB(params.CLOUD)
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild(params.CLOUD, VALKEY_ADMIN_REPO, COMPONENT)
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
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
