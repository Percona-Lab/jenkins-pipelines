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
        sudo docker run -u root -e VALKEY_REPO_NAME=${VALKEY_BLOOM_REPO} -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            # Dependency installation reaches distro mirrors and repo.percona.com;
            # retry it, and stop here if it still fails instead of failing later.
            install_deps() { bash -x ./valkey-packaging/scripts/valkey_builder.sh --builddir=\${build_dir}/test --bloom_deps; }
            # Drop cached package-manager metadata before a retry: a repo republished
            # meanwhile leaves the cached checksums stale and every retry fails the same way.
            clean_pkg_cache() { command -v dnf >/dev/null && dnf clean all; command -v yum >/dev/null && yum clean all; command -v apt-get >/dev/null && apt-get clean; true; }
            install_deps || { echo 'Dependency installation failed, retry 1/2 in 60s'; clean_pkg_cache; sleep 60; install_deps; } || \\
                { echo 'Dependency installation failed, retry 2/2 in 120s'; clean_pkg_cache; sleep 120; install_deps; } || \\
                { echo 'ERROR: dependency installation failed after 3 attempts'; exit 1; }
            bash ./valkey-packaging/scripts/valkey_builder.sh --builddir=\${build_dir}/test --bloom_repo=${GIT_REPO} --bloom_branch=${GIT_BRANCH} --bloom_version=${VALKEY_BLOOM_VERSION} --release=${VALKEY_BLOOM_RELEASE} ${STAGE_PARAM}"
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
        booleanParam(defaultValue: true, description: 'Allow publishing a version-release that is already in the repo (the default). Uncheck to stop the build when the version-release is already published.', name: 'ALLOW_REPUBLISH')
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/valkey-io/valkey-bloom.git',
            description: 'URL for valkey-bloom repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '1.0.1',
            description: 'Tag/Branch for valkey-bloom repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1.0.1',
            description: 'valkey-bloom version value',
            name: 'VALKEY_BLOOM_VERSION')
        string(
            defaultValue: '1',
            description: 'valkey-bloom release value',
            name: 'VALKEY_BLOOM_RELEASE')
        string(
            defaultValue: 'https://github.com/percona/valkey-packaging.git',
            description: 'URL for valkey-packaging repository (holds the bloom/ packaging + builder)',
            name: 'PACKAGING_REPO')
        string(
            defaultValue: '9.1.3',
            description: 'Branch/Tag for valkey-packaging repository',
            name: 'PACKAGING_BRANCH')
        string(
            defaultValue: 'valkey-bloom',
            description: 'valkey-bloom repo name (target product repo for sync)',
            name: 'VALKEY_BLOOM_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        // Save pipeline state at the end of each stage, not after every step. With
        // many parallel platform stages the per-step saves overload the controller
        // ("cannot find current thread in CpsStepContext", ClosedByInterruptException
        // in saveProgram). A build cut off by a controller restart is rerun instead.
        durabilityHint('PERFORMANCE_OPTIMIZED')
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Pre-flight: release number') {
            steps {
                valkeyCheckNotPublished(repo: params.VALKEY_BLOOM_REPO, channel: params.COMPONENT, deb: 'percona-valkey-bloom', rpm: 'percona-valkey-bloom',
                                        version: "${params.VALKEY_BLOOM_VERSION}-${params.VALKEY_BLOOM_RELEASE}", allowRepublish: params.ALLOW_REPUBLISH)
            }
        }
        stage('Create valkey-bloom source tarball') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_bloom_sources")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/valkey-bloom.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/valkey-bloom.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                valkeyUpload { uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source') }
            }
        }
        stage('Build valkey-bloom generic source packages') {
            parallel {
                stage('Build valkey-bloom generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_bloom_src_rpm")

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Build valkey-bloom generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_bloom_src_deb")
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH) }
                    }
                }
            }  //parallel
        } // stage
        stage('Build valkey-bloom RPMs/DEBs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_bloom_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Oracle Linux 8 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_bloom_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_bloom_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_bloom_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Oracle Linux 10') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_bloom_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Oracle Linux 10 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "--build_bloom_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Amazon Linux 2023') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_bloom_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Amazon Linux 2023 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "--build_bloom_rpm")

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Ubuntu Resolute(26.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:resolute-20260627", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Ubuntu Resolute(26.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("ubuntu:resolute-20260627", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Debian Trixie(13)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
                    }
                }
                stage('Debian Trixie(13) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        valkeyPopArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "--build_bloom_deb")

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        valkeyUpload { uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH) }
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
                valkeyPublish(params.CLOUD, VALKEY_BLOOM_REPO, COMPONENT, params.ALLOW_REPUBLISH ? true : false)
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
