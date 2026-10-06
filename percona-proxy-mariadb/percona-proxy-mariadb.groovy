library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Runs one stage of BUILD/percona/percona_proxy_builder.sh, from the branch being built, inside a
// container of the target distribution.
void buildStage(String DOCKER_OS, String STAGE_PARAM, String INSTALL_DEPS = '1') {
    sh """
        set -o xtrace
        mkdir -p test
        wget \$(echo ${params.GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${params.BRANCH}/BUILD/percona/percona_proxy_builder.sh -O percona_proxy_builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o errexit
            set -o xtrace
            cd \${build_dir}
            bash -x ./percona_proxy_builder.sh --builddir=\${build_dir}/test --repo=${params.GIT_REPO} --branch=${params.BRANCH} --install_deps=${INSTALL_DEPS}
            bash -x ./percona_proxy_builder.sh --builddir=\${build_dir}/test --repo=${params.GIT_REPO} --branch=${params.BRANCH} --version=${params.VERSION} --rpm_release=${params.RPM_RELEASE} --deb_release=${params.DEB_RELEASE} --package_name=${params.PACKAGE_NAME} --build_tests=${params.BUILD_TESTS ? 1 : 0} ${STAGE_PARAM}"
    """
}

// Builds the source packages the binary packages are then rebuilt from, so that the published
// sources reproduce the shipped packages.
void buildSourcePackages(String DOCKER_OS, String KIND, String STASH_PATH) {
    cleanUpWS()
    popArtifactFolder(params.CLOUD, "source_tarball/", STASH_PATH)
    if (KIND == 'srpm') {
        buildStage(DOCKER_OS, "--build_src_rpm=1")
        pushArtifactFolder(params.CLOUD, "srpm/", STASH_PATH)
        uploadRPMfromAWS(params.CLOUD, "srpm/", STASH_PATH)
    } else {
        buildStage(DOCKER_OS, "--build_source_deb=1")
        pushArtifactFolder(params.CLOUD, "source_deb/", STASH_PATH)
        uploadDEBfromAWS(params.CLOUD, "source_deb/", STASH_PATH)
    }
}

// Builds packages of one kind ('rpm', 'deb' or 'tarball') and uploads them to repo.ci.percona.com.
// RPMs and DEBs are rebuilt from the source packages, tarballs from the source tarball.
void buildPackages(String DOCKER_OS, String KIND, String STASH_PATH) {
    cleanUpWS()
    if (KIND == 'rpm') {
        popArtifactFolder(params.CLOUD, "srpm/", STASH_PATH)
        buildStage(DOCKER_OS, "--build_rpm=1")
        pushArtifactFolder(params.CLOUD, "rpm/", STASH_PATH)
        uploadRPMfromAWS(params.CLOUD, "rpm/", STASH_PATH)
    } else if (KIND == 'deb') {
        popArtifactFolder(params.CLOUD, "source_deb/", STASH_PATH)
        buildStage(DOCKER_OS, "--build_deb=1")
        pushArtifactFolder(params.CLOUD, "deb/", STASH_PATH)
        uploadDEBfromAWS(params.CLOUD, "deb/", STASH_PATH)
    } else {
        popArtifactFolder(params.CLOUD, "source_tarball/", STASH_PATH)
        buildStage(DOCKER_OS, "--build_tarball=1")
        pushArtifactFolder(params.CLOUD, "tarball/", STASH_PATH)
        uploadTarballfromAWS(params.CLOUD, "tarball/", STASH_PATH, 'binary')
    }
}

// Builds one image variant for amd64 and scans it with Trivy before anything is published.
// SUFFIX is empty for the UBI9 image and "-debian" for the hardened Debian image.
void buildAndScanImage(String DOCKERFILE, String SUFFIX) {
    String localTag = "percona-proxy-mariadb:scan${SUFFIX}"
    sh """
        set -o xtrace
        sudo docker buildx build --provenance=false --no-cache --pull --load \
            --platform linux/amd64 -f percona-proxy-src/BUILD/percona/docker/${DOCKERFILE} \
            --build-arg PERCONA_PROXY_VERSION=${params.VERSION} \
            --build-arg PERCONA_PROXY_RELEASE=${params.RPM_RELEASE} \
            --build-arg REPO_COMPONENT=${params.COMPONENT} \
            -t ${localTag} percona-proxy-src/BUILD/percona/docker
    """

    installTrivy(method: 'binary', junitTpl: true)
    String report = "trivy-high-junit${SUFFIX}.xml"
    sh """
        set -o xtrace
        sudo /usr/local/bin/trivy -q image --format template --template @junit.tpl \
            -o ${report} --timeout 10m0s --ignore-unfixed --scanners vuln \
            --exit-code ${params.FAIL_ON_CVE ? 1 : 0} --severity HIGH,CRITICAL ${localTag}
        sudo chown \$(id -u):\$(id -g) ${report}
    """
    junit testResults: report, keepLongStdio: true, allowEmptyResults: true,
        skipPublishingChecks: true
}

// Builds the same variant for both architectures and pushes it as one manifest.
void pushImage(String DOCKERFILE, String SUFFIX) {
    sh """
        set -o xtrace
        MAJOR_MINOR=\$(echo ${params.VERSION} | cut -d. -f1,2)

        sudo docker buildx build --provenance=false --pull \
            --platform linux/amd64,linux/arm64 \
            -f percona-proxy-src/BUILD/percona/docker/${DOCKERFILE} \
            --build-arg PERCONA_PROXY_VERSION=${params.VERSION} \
            --build-arg PERCONA_PROXY_RELEASE=${params.RPM_RELEASE} \
            --build-arg REPO_COMPONENT=${params.COMPONENT} \
            -t ${params.DOCKER_REPOSITORY}:${params.VERSION}-${params.RPM_RELEASE}${SUFFIX} \
            -t ${params.DOCKER_REPOSITORY}:${params.VERSION}${SUFFIX} \
            -t ${params.DOCKER_REPOSITORY}:\${MAJOR_MINOR}${SUFFIX} \
            --push percona-proxy-src/BUILD/percona/docker

        sudo docker buildx imagetools inspect ${params.DOCKER_REPOSITORY}:${params.VERSION}-${params.RPM_RELEASE}${SUFFIX}
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
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    parameters {
        choice(
            choices: [ 'Hetzner','AWS' ],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/percona-proxy-mariadb.git',
            description: 'URL for the Percona Proxy for MariaDB repository (must contain BUILD/percona/percona_proxy_builder.sh)',
            name: 'GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch to build',
            name: 'BRANCH')
        string(
            defaultValue: '1.0.0',
            description: 'Percona Proxy for MariaDB version, must match the version in the sources',
            name: 'VERSION')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        string(
            defaultValue: 'percona-proxy-mariadb',
            description: 'Package name',
            name: 'PACKAGE_NAME')
        booleanParam(
            defaultValue: false,
            description: 'Also build and run the unit tests in every package build',
            name: 'BUILD_TESTS')
        booleanParam(
            defaultValue: true,
            description: 'Install the packages that were just built and check that Percona Proxy routes queries, before anything is signed or pushed',
            name: 'VERIFY_PACKAGES')
        booleanParam(
            defaultValue: false,
            description: 'Push the signed packages to PERCONA_PROXY_DEST_REPO on repo.percona.com',
            name: 'PUSH_TO_REPO')
        string(
            defaultValue: '',
            description: 'Repository name on repo.percona.com, required when PUSH_TO_REPO is set',
            name: 'PERCONA_PROXY_DEST_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        booleanParam(
            defaultValue: false,
            description: 'Build the amd64 and arm64 container images and push them to DOCKER_REPOSITORY. Needs PUSH_TO_REPO, because the image installs the packages from the repository',
            name: 'BUILD_DOCKER')
        string(
            defaultValue: 'perconalab/percona-proxy-mariadb',
            description: 'Where to push the container images. The hardened Debian variant gets the same tags with a -debian suffix',
            name: 'DOCKER_REPOSITORY')
        booleanParam(
            defaultValue: true,
            description: 'Fail the build when Trivy reports a fixable HIGH or CRITICAL vulnerability in an image',
            name: 'FAIL_ON_CVE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Create source tarball') {
            steps {
                script {
                    // The string parameters are pasted into nested shell commands that run as root.
                    ['GIT_REPO', 'BRANCH', 'VERSION', 'RPM_RELEASE', 'DEB_RELEASE',
                     'PACKAGE_NAME', 'PERCONA_PROXY_DEST_REPO'].each { name ->
                        if (!(params[name] ==~ /[A-Za-z0-9._\/:@+-]*/)) {
                            error("Parameter ${name} contains characters that are not allowed")
                        }
                    }
                    if (params.PUSH_TO_REPO && !params.PERCONA_PROXY_DEST_REPO?.trim()) {
                        error('PUSH_TO_REPO is set but PERCONA_PROXY_DEST_REPO is empty')
                    }
                    if (params.BUILD_DOCKER) {
                        if (!(params.DOCKER_REPOSITORY ==~ /[A-Za-z0-9._\/:-]+/)) {
                            error('Parameter DOCKER_REPOSITORY contains characters that are not allowed')
                        }
                        if (!params.PUSH_TO_REPO) {
                            error('BUILD_DOCKER needs PUSH_TO_REPO: the image installs the packages from the repository')
                        }
                    }
                }
                cleanUpWS()
                buildStage("oraclelinux:9", "--get_sources=1", "git")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-proxy.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-proxy.properties
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
        stage('Build generic source packages') {
            parallel {
                stage('Build generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        buildSourcePackages("oraclelinux:9", "srpm", AWS_STASH_PATH)
                    }
                }
                stage('Build generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        buildSourcePackages("ubuntu:noble", "sdeb", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("oraclelinux:8", "rpm", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("oraclelinux:8", "rpm", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("oraclelinux:9", "rpm", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("oraclelinux:9", "rpm", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("oraclelinux:10", "rpm", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("oraclelinux:10", "rpm", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("amazonlinux:2023", "rpm", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("amazonlinux:2023", "rpm", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("ubuntu:jammy", "deb", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("ubuntu:jammy", "deb", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("ubuntu:noble", "deb", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("ubuntu:noble", "deb", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("debian:bookworm", "deb", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("debian:bookworm", "deb", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("debian:trixie", "deb", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie(13) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("debian:trixie", "deb", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        buildPackages("oraclelinux:8", "tarball", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 ARM tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        buildPackages("oraclelinux:8", "tarball", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Verify the packages that were built') {
            when {
                expression { params.VERIFY_PACKAGES }
            }
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                cleanUpWS()
                // The packages are installed and exercised exactly as they came out of the
                // build, before they are signed or pushed, so that a broken package cannot
                // reach a repository. Nothing is downloaded from repo.percona.com here, which
                // is also why this works before the repository for this product exists.
                popArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                popArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                sh """
                    set -o xtrace
                    wget \$(echo ${params.GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${params.BRANCH}/BUILD/percona/verify_packages.sh -O verify_packages.sh
                    chmod +x verify_packages.sh
                    # The agent is x86_64 and the folders hold both architectures; the script
                    # picks the ones this machine can run.
                    ./verify_packages.sh --packages=\$(pwd -P) --version=${params.VERSION}
                """
            }
        }
        stage('Sign packages') {
            steps {
                signRPM(params.CLOUD)
                signDEB(params.CLOUD)
            }
        }
        stage('Push to public repository') {
            when {
                expression { params.PUSH_TO_REPO }
            }
            steps {
                sync2ProdAutoBuild(params.CLOUD, params.PERCONA_PROXY_DEST_REPO, params.COMPONENT)
            }
        }
        stage('Build docker images') {
            when {
                expression { params.BUILD_DOCKER }
            }
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                cleanUpWS()
                // The image installs the packages from the repository, which the previous stage
                // only started syncing.
                echo "Waiting 10 minutes for the packages to appear in the ${params.COMPONENT} repository"
                sh 'sleep 600'
                sh """
                    set -o xtrace
                    git clone --depth 1 --branch ${params.BRANCH} ${params.GIT_REPO} percona-proxy-src

                    # buildx and qemu are what make the arm64 image buildable here.
                    sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                    sudo docker buildx rm percona-proxy-builder || true
                    sudo docker buildx create --name percona-proxy-builder --use --bootstrap
                """
                // Both variants are scanned as amd64 images before anything is pushed.
                buildAndScanImage('Dockerfile', '')
                buildAndScanImage('Dockerfile-debian', '-debian')

                withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                                                  passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        set -o xtrace
                        echo "\${PASS}" | sudo docker login -u "\${USER}" --password-stdin
                    """
                    pushImage('Dockerfile', '')
                    pushImage('Dockerfile-debian', '-debian')
                }
            }
            post {
                always {
                    sh 'sudo docker buildx rm percona-proxy-builder || true'
                }
            }
        }
    }
    post {
        success {
            script {
                currentBuild.description = "Built ${params.PACKAGE_NAME} ${params.VERSION} from ${params.BRANCH} - [${BUILD_URL}]"
            }
            deleteDir()
        }
        failure {
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
