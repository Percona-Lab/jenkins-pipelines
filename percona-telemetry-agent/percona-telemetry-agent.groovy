library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/packaging/scripts/telemetry-agent_builder.sh -O telemetry-agent_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./telemetry-agent_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./telemetry-agent_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --version=${VERSION} --branch=${GIT_BRANCH} --release=${RELEASE} ${STAGE_PARAM}"
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
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/telemetry-agent.git',
            description: 'URL for telemetry-agent repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for telemetry-agent repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM/DEB release value',
            name: 'RELEASE')
        string(
            defaultValue: '1.0.1',
            description: 'VERSION value',
            name: 'VERSION')
        string(
            defaultValue: 'telemetry',
            description: 'PTA repo name',
            name: 'PTA_REPO')
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
        stage('Create telemetry agent source tarball') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
            }
            steps {
                //slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-telemetry-agent.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-telemetry-agent.properties
                   cat uploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build telemetry agent generic source packages') {
            parallel {
                stage('Build telemetry agent generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_src_rpm=1")

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build telemetry agent generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_src_deb=1")

                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build telemetry agent RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

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
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")

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
                        buildStage("oraclelinux:9", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_rpm=1")

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
                        buildStage("oraclelinux:10", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_rpm=1")

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
                        buildStage("amazonlinux:2023", "--build_rpm=1")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_deb=1")

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
                        buildStage("ubuntu:jammy", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
		stage('Ubuntu Noble(24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_deb=1")

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
                        buildStage("ubuntu:noble", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "--build_deb=1")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_deb=1")

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
                        buildStage("debian:bookworm", "--build_deb=1")

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
                sync2ProdAutoBuild(params.CLOUD, PTA_REPO, COMPONENT)
            }
        }

    }
    post {
        success {
            //slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            //slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
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
