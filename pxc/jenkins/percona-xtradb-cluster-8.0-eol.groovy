library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/adivinho/jenkins-pipelines.git'
]) _

// Debian Bullseye repositories are archived: point apt to snapshot.debian.org.
// This block is inserted into pxc_builder.sh right before the first "apt-get update".
void writeBullseyeSnapshotBlock() {
    writeFile file: 'bullseye_snapshot_block.sh', text: '''export DIST="$(. /etc/os-release && echo "$VERSION_CODENAME")"
if [ "${DIST}" == "bullseye" ]; then
            sed -i -E '/bullseye(-security|-updates)?[[:space:]]/d' /etc/apt/sources.list
cat <<'EOF' | tee -a /etc/apt/sources.list
deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/20260830T000000Z/ bullseye main
deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/20260830T000000Z/ bullseye-updates main
deb [check-valid-until=no] http://snapshot.debian.org/archive/debian-security/20260830T000000Z/ bullseye-security main
EOF
fi
'''
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    writeBullseyeSnapshotBlock()
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        sh """
            set -o xtrace
            mkdir -p test
            wget --header="Authorization: token ${TOKEN}" --header="Accept: application/vnd.github.v3.raw" -O pxc_builder.sh \$(echo ${GIT_REPO} | sed -re 's|github.com|api.github.com/repos|; s|\\.git\$||')/contents/build-ps/pxc_builder.sh?ref=${GIT_BRANCH}
            sed -i "s|git clone --depth 1 --branch \\\$BRANCH \\\"\\\$REPO\\\"|git clone --depth 1 --branch \\\$BRANCH \$(echo ${GIT_REPO}| sed -re 's|github.com|${TOKEN}@github.com|') percona-xtradb-cluster|g" pxc_builder.sh
            ls -la
            grep "git clone" pxc_builder.sh
            # Insert the Debian Bullseye snapshot block right before the first "apt-get update" / "apt-get -y update"
            awk 'FNR==NR { block = block \$0 "\\n"; next } !done && /apt-get (-y )?update/ { printf "%s", block; done = 1 } { print }' bullseye_snapshot_block.sh pxc_builder.sh > pxc_builder.sh.tmp && mv pxc_builder.sh.tmp pxc_builder.sh
            grep -n -B1 -A10 'export DIST="\$(. /etc/os-release' pxc_builder.sh
            pwd -P
            export build_dir=\$(pwd -P)
            sudo docker run --shm-size=16g --cap-add=SYS_NICE -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                set -o xtrace
                cd \${build_dir}
                bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --install_deps=1
                SBOM_PARAM=\"\"
                if [ \"${ENABLE_SBOM}\" = \"ON\" ]; then SBOM_PARAM=\"--sbom=1\"; fi
                bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} --bin_release=${BIN_RELEASE} \${SBOM_PARAM} ${STAGE_PARAM}"
        """
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-private.git',
            description: 'URL for percona-xtradb-cluster repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'release-8.0.46-39',
            description: 'Tag/Branch for percona-xtradb-cluster repository',
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
            defaultValue: '1',
            description: 'BIN release value',
            name: 'BIN_RELEASE')
        choice(
            choices: 'OFF\nON',
            description: 'Enable SBOM generation',
            name: 'ENABLE_SBOM')
        choice(
            choices: 'testing\nexperimental\nlaboratory',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases-ci\n#releases',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
        string(
            defaultValue: '',
            description: 'Comma-separated list of build stages to run (e.g. "Oracle Linux 9,Oracle Linux 9 ARM"). Leave empty to run all stages.',
            name: 'BUILD_STAGES')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PXC source tarball') {
            agent {
               label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "🚀 [${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    buildStage("centos:7", "--get_sources=1")
                }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "DEST=UPLOAD" test/pxc-80.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pxc-80.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                script {
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                    sh """
                        wget --header="Authorization: token ${TOKEN}" --header="Accept: application/vnd.github.v3.raw" -O MYSQL_VERSION \$(echo ${GIT_REPO} | sed -re 's|github.com|api.github.com/repos|; s|\\.git\$||')/contents/MYSQL_VERSION?ref=${GIT_BRANCH}
                    """
                    }
                    env.MYSQL_VERSION_MAJOR = sh(returnStdout: true, script: "grep '^MYSQL_VERSION_MAJOR=' MYSQL_VERSION | cut -d= -f2").trim()
                    env.MYSQL_VERSION_MINOR = sh(returnStdout: true, script: "grep '^MYSQL_VERSION_MINOR=' MYSQL_VERSION | cut -d= -f2").trim()
                    env.MYSQL_VERSION_PATCH = sh(returnStdout: true, script: "grep '^MYSQL_VERSION_PATCH=' MYSQL_VERSION | cut -d= -f2").trim()
                    env.MYSQL_VERSION_EXTRA = sh(returnStdout: true, script: "grep '^MYSQL_VERSION_EXTRA=' MYSQL_VERSION | cut -d= -f2").trim()
                    env.PXC_RELEASE = "${env.MYSQL_VERSION_MAJOR}.${env.MYSQL_VERSION_MINOR}.${env.MYSQL_VERSION_PATCH}${env.MYSQL_VERSION_EXTRA}"
                    echo "Detected PXC version: ${env.PXC_RELEASE}"
                }
                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PXC generic source packages') {
            parallel {
                stage('Build PXC generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("centos:8", "--build_src_rpm=1")
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PXC generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            buildStage("ubuntu:xenial", "--build_source_deb=1")
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PXC RPMs/DEBs/Binary tarballs') {
            steps {
                script {
                    pxc80EolBuildMatrix(
                        cloud:        params.CLOUD,
                        awsStashPath: AWS_STASH_PATH,
                        fipsMode:     'NO',
                        onlyStages:   params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    )
                }
            }
        }

        stage('Sign packages') {
            steps {
                script {
                    def rpmStages = [
                        'Centos 8', 'Centos 8 ARM', 'Oracle Linux 9', 'Oracle Linux 9 ARM',
                        'Oracle Linux 10', 'Oracle Linux 10 ARM', 'Amazon Linux 2023', 'Amazon Linux 2023 ARM',
                        'Centos 8 tarball', 'Oracle Linux 9 tarball', 'Debian Bullseye(11) tarball'
                    ]
                    def debStages = [
                        'Ubuntu Jammy(22.04)', 'Ubuntu Jammy(22.04) ARM',
                        'Ubuntu Noble(24.04)', 'Ubuntu Noble(24.04) ARM',
                        'Ubuntu Resolute(26.04)', 'Ubuntu Resolute(26.04) ARM',
                        'Debian Bullseye(11)', 'Debian Bullseye(11) ARM',
                        'Debian Bookworm(12)', 'Debian Bookworm(12) ARM',
                        'Debian Trixie(13)', 'Debian Trixie(13) ARM',
                        'Ubuntu Jammy(22.04) tarball', 'Debian Trixie(13) tarball'
                    ]
                    def requestedStages = params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    if (!requestedStages || requestedStages.any { rpmStages.contains(it) }) {
                        signRPM()
                    }
                    if (!requestedStages || requestedStages.any { debStages.contains(it) }) {
                        signDEB()
                    }
                }
            }
        }
        stage('Push to public repository') {
            steps {
                script {
                    sync2PrivateProdAutoBuild(params.CLOUD, "pxc-80-eol", COMPONENT)
                }
            }
        }
        stage('Build docker container') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'min-jammy-x64'
            }
            steps {
                script {
                    cleanUpWS()
                    unstash 'uploadPath'
                    def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com:${path_to_build}/binary/redhat/9/x86_64/*.rpm /tmp
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com:${path_to_build}/binary/redhat/9/aarch64/*.rpm /tmp
                            ls -la /tmp
                        """
                    }
                    sh '''
                        REPO_DOCKER="https://github.com/percona/percona-docker"
                        REPO_DOCKER_BRANCH="main"
                        PXC_MAJOR_RELEASE=$(echo ${PXC_RELEASE} | cut -d. -f1,2)
                        PXC_MAJOR_MINOR_RELEASE=$(echo ${PXC_RELEASE} | cut -d- -f1)

                        sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                        sudo apt-get -y install apparmor
                        sudo aa-status
                        sudo systemctl stop apparmor
                        sudo systemctl disable apparmor
                        DISTRO=$(. /etc/os-release && echo $ID)
                        curl -fsSL https://download.docker.com/linux/${DISTRO}/gpg | sudo gpg --batch --yes --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
                        echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/${DISTRO} $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
                        sudo apt-get update
                        sudo apt-get install -y docker-ce docker-ce-cli containerd.io
                        export DOCKER_CLI_EXPERIMENTAL=enabled
                        sudo mkdir -p /usr/libexec/docker/cli-plugins/
                        sudo curl -L https://github.com/docker/buildx/releases/download/v0.37.2/buildx-v0.37.2.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                        sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                        sudo systemctl restart docker
                        sudo apt-get install -y qemu-system binfmt-support qemu-user-static
                        sudo qemu-system-x86_64 --version
                        sudo lscpu | grep -q 'sse4_2' && grep -q 'popcnt' /proc/cpuinfo && echo "Supports x86-64-v2" || echo "Does NOT support x86-64-v2"
                        sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes

                        git clone ${REPO_DOCKER}
                        cd percona-docker/percona-xtradb-cluster-8.0
                        git checkout ${REPO_DOCKER_BRANCH}
                        mv /tmp/*.rpm .
                        sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${PXC_RELEASE}.${RPM_RELEASE}/g" Dockerfile-pro
                        sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${PXC_RELEASE}.${RPM_RELEASE}/g" Dockerfile.aarch64-pro
                        sed -i "s/ENV PXC_TELEMETRY_VERSION.*/ENV PXC_TELEMETRY_VERSION ${PXC_RELEASE}-${RPM_RELEASE}/g" Dockerfile-pro
                        sed -i "s/ENV PXC_TELEMETRY_VERSION.*/ENV PXC_TELEMETRY_VERSION ${PXC_RELEASE}-${RPM_RELEASE}/g" Dockerfile.aarch64-pro
                        sudo docker builder prune -af
                        sudo docker build --provenance=false -t percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-amd64 --progress plain --platform="linux/amd64" -f Dockerfile-pro .
                        sudo docker buildx build --provenance=false --platform linux/arm64 -t percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-arm64 --load -f Dockerfile.aarch64-pro .
                        sudo docker tag percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-amd64 percona/percona-xtradb-cluster:${PXC_RELEASE}
                        sudo docker tag percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-amd64 percona/percona-xtradb-cluster:${PXC_MAJOR_RELEASE}
                        sudo docker tag percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-amd64 percona/percona-xtradb-cluster:${PXC_MAJOR_MINOR_RELEASE}
                        sudo docker images
                        sudo docker save -o percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}-amd64.docker.tar percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-amd64 percona/percona-xtradb-cluster:${PXC_RELEASE} percona/percona-xtradb-cluster:${PXC_MAJOR_RELEASE} percona/percona-xtradb-cluster:${PXC_MAJOR_MINOR_RELEASE}

                        sudo docker tag percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-arm64 percona/percona-xtradb-cluster:${PXC_RELEASE}
                        sudo docker tag percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-arm64 percona/percona-xtradb-cluster:${PXC_MAJOR_RELEASE}
                        sudo docker tag percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-arm64 percona/percona-xtradb-cluster:${PXC_MAJOR_MINOR_RELEASE}
                        sudo docker images
                        sudo docker save -o percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}-arm64.docker.tar percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-arm64 percona/percona-xtradb-cluster:${PXC_RELEASE} percona/percona-xtradb-cluster:${PXC_MAJOR_RELEASE} percona/percona-xtradb-cluster:${PXC_MAJOR_MINOR_RELEASE}

                        sudo addgroup admin || true
                        sudo useradd -m -s /bin/bash -g admin -G admin admin || true
                        sudo chown admin:admin percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}-amd64.docker.tar
                        sudo chown admin:admin percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}-arm64.docker.tar
                        sudo chmod a+r percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}-amd64.docker.tar
                        sudo chmod a+r percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}-arm64.docker.tar
                        ls -la
                    '''
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            cd percona-docker/percona-xtradb-cluster-8.0
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com "mkdir -p ${path_to_build}/binary/tarball"
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}-amd64.docker.tar ${USER}@repo.ci.percona.com:${path_to_build}/binary/tarball/
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} percona-xtradb-cluster-${PXC_RELEASE}-${RPM_RELEASE}-arm64.docker.tar ${USER}@repo.ci.percona.com:${path_to_build}/binary/tarball/
                        """
                    }
                    // 🔹 Trivy scan
                    try {
                        installTrivy(method: 'apt')
                        def imageList = [
                            "percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-amd64",
                            "percona/percona-xtradb-cluster:${PXC_RELEASE}.${RPM_RELEASE}-arm64"
                        ]
                        imageList.each { image ->
                            echo "🔍 Scanning ${image}..."
                            def result = sh(script: """
                                sudo trivy image --quiet \
                                          --format table \
                                          --timeout 10m0s \
                                          --ignore-unfixed \
                                          --exit-code 1 \
                                          --scanners vuln \
                                          --severity HIGH,CRITICAL ${image}
                            """, returnStatus: true)
                            echo "Actual Trivy exit code: ${result}"
                            if (result != 0) {
                                sh """
                                sudo trivy image --quiet \
                                             --format table \
                                             --timeout 10m0s \
                                             --ignore-unfixed \
                                             --exit-code 0 \
                                             --scanners vuln \
                                             --severity HIGH,CRITICAL ${image} | tee -a trivy-high-junit.xml
                                """
                                unstable "⚠️ Trivy detected vulnerabilities in ${image}. See trivy-high-junit.xml for details."
                            } else {
                                echo "✅ No critical vulnerabilities found in ${image}."
                            }
                        }
                    } catch (Exception e) {
                        unstable "⚠️ Trivy scan failed: ${e.message}"
                    }
               }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            when {
                expression {
                    if (!params.BUILD_STAGES) return true
                    def stages = params.BUILD_STAGES.split(',').collect { it.trim().toLowerCase() }
                    stages.any { it.contains('tarball') || it.contains('docker') } ||
                    (stages.any { it.contains('oracle linux 9') && !it.contains('arm') } && stages.any { it.contains('oracle linux 9') && it.contains('arm') })
                }
            }
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "pxc-gated", "${GIT_BRANCH}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "✅ [${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            }
            deleteDir()
        }
        failure {
            script {
                slackNotify("${SLACKNOTIFY}", "#FF0000", "❌ [${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
            }
            deleteDir()
        }
        unstable {
            script {
                slackNotify("${SLACKNOTIFY}", "#FFFF00", "⚠️ [${JOB_NAME}]: build finished with warnings for ${GIT_BRANCH} - [${BUILD_URL}]")
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                currentBuild.description = "Built on ${GIT_BRANCH} - packages [${COMPONENT}/${AWS_STASH_PATH}]"
            }
            deleteDir()
        }
    }
}
