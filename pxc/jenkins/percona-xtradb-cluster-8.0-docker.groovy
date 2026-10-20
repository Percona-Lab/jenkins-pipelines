library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

void installSbomTools() {
    sh """
        set -e
        ORAS_VERSION=1.2.3

        if ! command -v syft >/dev/null 2>&1; then
            for i in 1 2 3; do
                curl -fsSL https://raw.githubusercontent.com/anchore/syft/main/install.sh \
                    | sudo sh -s -- -b /usr/local/bin && break
                sleep 10
            done
            command -v syft >/dev/null || { echo "ERROR: syft install failed" >&2; exit 1; }
        fi

        if ! command -v oras >/dev/null 2>&1; then
            UNAME=\$(uname -m)
            case "\$UNAME" in
                x86_64)         ORAS_ARCH=amd64 ;;
                aarch64|arm64)  ORAS_ARCH=arm64 ;;
                *) echo "ERROR: unsupported arch \$UNAME for oras" >&2; exit 1 ;;
            esac
            for i in 1 2 3; do
                curl -fsSL "https://github.com/oras-project/oras/releases/download/v\${ORAS_VERSION}/oras_\${ORAS_VERSION}_linux_\${ORAS_ARCH}.tar.gz" \
                        -o /tmp/oras.tar.gz \
                    && sudo tar -xzf /tmp/oras.tar.gz -C /usr/local/bin oras \
                    && rm -f /tmp/oras.tar.gz && break
                sleep 10
            done
            command -v oras >/dev/null || { echo "ERROR: oras install failed" >&2; exit 1; }
        fi

        if ! command -v jq >/dev/null 2>&1; then
            if command -v apt-get >/dev/null 2>&1; then
                sudo apt-get update && sudo apt-get install -y jq
            elif command -v dnf >/dev/null 2>&1; then
                sudo dnf install -y jq
            elif command -v yum >/dev/null 2>&1; then
                sudo yum install -y jq
            else
                echo "ERROR: no supported package manager for jq" >&2; exit 1
            fi
        fi

        syft version | head -1
        oras version | head -1
        jq --version
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
        choice(
            choices: 'perconalab\npercona',
            description: 'Organization on hub.docker.com',
            name: 'ORGANIZATION')
        string(defaultValue: 'https://github.com/percona/percona-docker', description: 'Dockerfiles source', name: 'REPO_DOCKER')
        string(defaultValue: 'main', description: 'Tag/Branch for percona-docker repository', name: 'REPO_DOCKER_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster.git',
            description: 'URL for percona-xtradb-cluster repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'release-8.4.10',
            description: 'release Tag/Branch for percona-xtradb-cluster repository or pxc version in the format 8.4.8',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        choice(
            choices: 'testing\nexperimental\nrelease',
            description: 'Repository component used to retrieve packages',
            name: 'COMPONENT')
        choice(
            choices: '#releases-ci\n#releases',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Build docker containers') {
            agent {
               label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
            }
            steps {
                script {
                        echo "====> Build docker containers"
                        cleanUpWS()
                        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                        sh '''
                            PXC_RELEASE=$(echo ${GIT_BRANCH} | sed 's/release-//g')
                            PXC_MAJOR_RELEASE=$(echo ${GIT_BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}')
                            sudo apt-get -y install apparmor
                            sudo aa-status
                            sudo systemctl stop apparmor
                            sudo systemctl disable apparmor
                            sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                            sudo apt-get -y install apparmor
                            sudo aa-status
                            sudo systemctl stop apparmor
                            sudo systemctl disable apparmor
                            sudo apt-get install -y docker-ce docker-ce-cli containerd.io
                            export DOCKER_CLI_EXPERIMENTAL=enabled
                            sudo mkdir -p /usr/libexec/docker/cli-plugins/
                            sudo curl -L https://github.com/docker/buildx/releases/download/v0.35.0/buildx-v0.35.0.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            sudo apt-get install -y qemu-system binfmt-support qemu-user-static
                            sudo qemu-system-x86_64 --version
                            sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                            curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                            . ./MYSQL_VERSION
                            git clone $(echo ${REPO_DOCKER} | sed -e "s|https://github.com/|https://x-access-token:${TOKEN}@github.com/|")
                            cd percona-docker
                            git checkout ${REPO_DOCKER_BRANCH}
                            case ${PXC_MAJOR_RELEASE} in
                                80) cd percona-xtradb-cluster-8.0 ;;
                                84) cd percona-xtradb-cluster-8.4 ;;
                                *) cd percona-xtradb-cluster-9.x ;;
                            esac
                            sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PXC_TELEMETRY_VERSION.*/ENV PXC_TELEMETRY_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}-${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PXC_REPO .*/ENV PXC_REPO ${COMPONENT}/g" Dockerfile
                            if [ ${PXC_MAJOR_RELEASE} = "97" ]; then
                                sed -i "s/wsrep_slave_threads/wsrep_applier_threads/g" dockerdir/etc/mysql/node.cnf
                            fi
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker build --provenance=false --no-cache --platform "linux/amd64" -t ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64 .
                                sudo docker build --provenance=false --no-cache --platform "linux/amd64" --build-arg DEBUG=1 -t perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64 .
                            else
                                sudo docker pull perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker tag perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64 percona/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker pull perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64
                                sudo docker tag perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64 percona/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64
                            fi
                            sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PXC_TELEMETRY_VERSION.*/ENV PXC_TELEMETRY_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}-${RPM_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PXC_REPO .*/ENV PXC_REPO ${COMPONENT}/g" Dockerfile.aarch64
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker build --provenance=false --no-cache -t perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --platform="linux/arm64" -f Dockerfile.aarch64 .
                                sudo docker build --provenance=false --no-cache --build-arg DEBUG=1 -t perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64 --platform="linux/arm64" -f Dockerfile.aarch64 .
                            else
                                sudo docker pull perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                sudo docker tag perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64 percona/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                sudo docker pull perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64
                                sudo docker tag perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64 percona/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64
                            fi
                            case ${PXC_MAJOR_RELEASE} in
                                80) cd ../percona-xtradb-cluster-8.0-backup ;;
                                84) cd ../percona-xtradb-cluster-8.4-backup ;;
                                9*) cd ../percona-xtradb-cluster-9.x-backup ;;
                            esac
                            sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION=${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PXC_REPO.*/ENV PXC_REPO=${COMPONENT}/g" Dockerfile
                            sed -i "s:yum/release:yum/${COMPONENT}:g" Dockerfile
                            if [ ${PXC_MAJOR_RELEASE} != "80" ]; then
                                sed -i "s/ENV PXB_VERSION.*/ENV PXB_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                                sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                                if [ ${PXC_MAJOR_RELEASE} != "84" ]; then
                                    sed -i "s/ENV PXB_VERSION.*/ENV PXB_VERSION 9.7.1-1.rc1/g" Dockerfile
                                    sed -i "s/tools/pxb-97-lts/g" Dockerfile
                                    sed -i "s/ps-80/ps-97-lts/g" Dockerfile
                                    sed -i "s/pxc-80/pxc-97-lts/g" Dockerfile
                                else
                                    sed -i "s/ENV PXB_VERSION.*/ENV PXB_VERSION 8.4.0-6.1/g" Dockerfile
                                    sed -i "s/tools/pxb-84-lts/g" Dockerfile
                                    sed -i "s/ps-80/ps-84-lts/g" Dockerfile
                                    sed -i "s/pxc-80/pxc-84-lts/g" Dockerfile
                                fi
                                sed -i "s/percona-xtrabackup-80/percona-xtrabackup-${PXC_MAJOR_RELEASE}/g" Dockerfile
                            fi
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker build --provenance=false --no-cache --platform "linux/amd64" -t perconalab/percona-xtradb-cluster-operator:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}-pxc8.${MYSQL_VERSION_MINOR}-backup .
                            fi

                            sudo docker images
                        '''
                        }
                            withCredentials([
                            usernamePassword(credentialsId: 'hub.docker.com',
                            passwordVariable: 'PASS',
                            usernameVariable: 'USER'
                            )]) {
                            sh '''
                                echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                                curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                                . ./MYSQL_VERSION
                                sudo docker push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64
                                sudo docker push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                sudo docker push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64
                                if [ ${ORGANIZATION} != "percona" ]; then
                                    sudo docker push perconalab/percona-xtradb-cluster-operator:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}-pxc8.${MYSQL_VERSION_MINOR}-backup
                                fi
                            '''
                        }
                        sh '''
                           curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                           . ./MYSQL_VERSION
                           sudo docker manifest create --amend ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE} \
                               ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64 \
                               ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                           sudo docker manifest annotate ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE} ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                           sudo docker manifest annotate ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE} ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --os linux --arch amd64
                           sudo docker manifest inspect ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                       '''
                       withCredentials([
                       usernamePassword(credentialsId: 'hub.docker.com',
                       passwordVariable: 'PASS',
                       usernameVariable: 'USER'
                       )]) {
                       sh '''
                           echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                           curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                           . ./MYSQL_VERSION
                           sudo docker manifest push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH} ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR} ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-xtradb-cluster:latest ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                       '''
                       }
                 }
            }
        }
        stage('Attach SBOMs') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            when {
                expression {
                    // Container-image SBOM generation/attach only applies to
                    // PXC releases 9.7 and above
                    def ver = sh(returnStdout: true, script: """
                        curl -fsSL "https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION" \
                            | awk -F= '/^MYSQL_VERSION_(MAJOR|MINOR)/{gsub(/[ \\r\\t"]/,"",\$2); printf "%s ", \$2}'
                    """).trim()

                    def parts = ver.tokenize(' ')
                    if (parts.size() < 2) {
                        error "Unable to determine PXC major/minor version from MYSQL_VERSION for ${GIT_BRANCH}"
                    }
                    int major = parts[0] as int
                    int minor = parts[1] as int

                    return (major > 9) || (major == 9 && minor >= 7)
                }
            }
            steps {
                script {
                    cleanUpWS()
                    installSbomTools()

                    def RPM_RELEASE = params.RPM_RELEASE
                    def ORGANIZATION = params.ORGANIZATION

                    def images = [
                        'percona-xtradb-cluster': "${ORGANIZATION}/percona-xtradb-cluster"
                    ]

                    withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                                                      passwordVariable: 'PASS',
                                                      usernameVariable: 'USER')]) {
                        sh 'echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin'
                        sh 'echo "${PASS}" | oras login -u "${USER}" --password-stdin docker.io'

                        images.each { name, repo ->
                            sh """
                                set -e
                                curl -fsSL "https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION" -o MYSQL_VERSION
                                . ./MYSQL_VERSION
                                PXC_RELEASE="\${MYSQL_VERSION_MAJOR}.\${MYSQL_VERSION_MINOR}.\${MYSQL_VERSION_PATCH}\${MYSQL_VERSION_EXTRA}"

                                MANIFEST_TAG="${repo}:\${PXC_RELEASE}.${RPM_RELEASE}"
                                ORAS_REF="docker.io/${repo}"
                                INSPECT=\$(oras manifest fetch "\${ORAS_REF}:\${PXC_RELEASE}.${RPM_RELEASE}")

                                for ARCH in amd64 arm64; do
                                    DIGEST=\$(echo "\${INSPECT}" \
                                        | jq -r --arg a "\${ARCH}" '.manifests[] | select(.platform.architecture==\$a) | .digest')
                                    [ -n "\${DIGEST}" ] || { echo "ERROR: failed to resolve \${ARCH} digest for ${name}" >&2; exit 1; }

                                    SBOM_FILE="${name}-\${PXC_RELEASE}.${RPM_RELEASE}-\${ARCH}.cdx.json"
                                    PURL="pkg:oci/${name}@\${DIGEST}?repository_url=${repo}"

                                    echo "Generating CycloneDX 1.6 SBOM for ${name} (\${ARCH})..."
                                    syft scan "registry:\${MANIFEST_TAG}-\${ARCH}" \
                                        --source-name "${name}" \
                                        --source-version "\${PXC_RELEASE}" \
                                        -o "cyclonedx-json@1.6=\${SBOM_FILE}"

                                    jq --arg purl "\${PURL}" --arg ver "\${PXC_RELEASE}" '.metadata.component = {
                                        "bom-ref": \$purl,
                                        "type": "application",
                                        "name": "${name}",
                                        "version": \$ver,
                                        "purl": \$purl
                                    }' "\${SBOM_FILE}" > "\${SBOM_FILE}.tmp" && mv "\${SBOM_FILE}.tmp" "\${SBOM_FILE}"

                                    COMPONENT_COUNT=\$(jq '.components | length' "\${SBOM_FILE}")
                                    [ "\${COMPONENT_COUNT}" -ge 10 ] || { echo "ERROR: ${name}/\${ARCH} SBOM has only \${COMPONENT_COUNT} components" >&2; exit 1; }

                                    oras attach --artifact-type application/vnd.cyclonedx+json \
                                        "\${ORAS_REF}@\${DIGEST}" "\${SBOM_FILE}"

                                    echo "SBOM attached for ${name} (\${ARCH}):"
                                    oras discover --format tree "\${ORAS_REF}@\${DIGEST}"
                                done
                            """
                        }
                    }

                    archiveArtifacts artifacts: '*.cdx.json', allowEmptyArchive: false, fingerprint: true
                }
            }
        }
stage('Check by trivy') {
    agent {
       label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
    }
    environment {
        TRIVY_LOG = "trivy-high-junit.xml"
    }
    steps {
        script {
            try {
                echo "🔄 Fetching MySQL version..."

                // 🔹 Capture the file content directly from curl
                def mysqlVersion = sh(
                    script: "curl -s https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION",
                    returnStdout: true
                ).trim()

                echo "🔎 Raw MYSQL_VERSION: '${mysqlVersion}'"

                if (!mysqlVersion) {
                    error "❌ MYSQL_VERSION file is empty or not found!"
                }

                def versionMap = [:]
                mysqlVersion.split('\n').each { line ->
                    def (key, value) = line.tokenize('=')
                    versionMap[key.trim()] = value.trim().replaceAll('"', '')
                }

                def MYSQL_VERSION_MAJOR = versionMap['MYSQL_VERSION_MAJOR']
                def MYSQL_VERSION_MINOR = versionMap['MYSQL_VERSION_MINOR']
                def MYSQL_VERSION_PATCH = versionMap['MYSQL_VERSION_PATCH']
                def MYSQL_VERSION_EXTRA = versionMap['MYSQL_VERSION_EXTRA']
                def fullVersion = "${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}"

                echo "✅ Parsed MySQL version: ${fullVersion}"
                
                // 🔹 Install Trivy if not already installed
                installTrivy(method: 'apt')

                // 🔹 Define the image tags
                def imageList = [
                    "${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64",
                    "${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64"
                ]

                // 🔹 Scan images and store logs
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

                    // 🟡 Mark build as unstable if vulnerabilities are found
                        if (result != 0) {
                            sh """
                            sudo trivy image --quiet \
                                         --format table \
                                         --timeout 10m0s \
                                         --ignore-unfixed \
                                         --exit-code 0 \
                                         --scanners vuln \
                                         --severity HIGH,CRITICAL ${image} | tee -a ${TRIVY_LOG}
                            """
                            unstable "⚠️ Trivy detected vulnerabilities in ${image}. See ${TRIVY_LOG} for details."
                        } else {
                            echo "✅ No critical vulnerabilities found in ${image}."
                        }
                    }
            } catch (Exception e) {
                unstable "⚠️ Trivy scan failed: ${e.message}"
            } // try
        } // script
    } // steps
 } // stage
    }
    post {
        success {
            script {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "✅ ${ORGANIZATION == 'perconalab' ? '🧪 ' : '🦾 '}[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} pushed to ${ORGANIZATION}")
            }
            deleteDir()
        }
        unstable {
            script {
                slackNotify("${SLACKNOTIFY}", "#FFFF00", "⚠️ ${ORGANIZATION == 'perconalab' ? '🧪 ' : '🦾 '}[${JOB_NAME}]: build finished with warnings (Trivy) for ${GIT_BRANCH} pushed to ${ORGANIZATION}")
            }
            deleteDir()
        }
        failure {
            script {
                slackNotify("${SLACKNOTIFY}", "#FF0000", "❌ ${ORGANIZATION == 'perconalab' ? '🧪 ' : '🦾 '}[${JOB_NAME}]: build failed for ${GIT_BRANCH}")
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                currentBuild.description = "Built on ${GIT_BRANCH} pushed to ${ORGANIZATION}"
            }
            deleteDir()
        }
    }
}
