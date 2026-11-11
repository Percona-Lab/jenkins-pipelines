library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

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

def minitestNodes = [  "min-bullseye-x64",
                       "min-bookworm-x64",
                       "min-centos-7-x64",
                       "min-ol-8-x64",
                       "min-focal-x64",
                       "min-jammy-x64",
                       "min-ol-9-x64"     ]

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
            defaultValue: 'https://github.com/percona/percona-xtrabackup.git',
            description: 'URL for PXB git repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'release-8.4.0-6',
            description: 'release Tag/Branch for PXB repository or PXB version in the format 8.4.0-6',
            name: 'BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        choice(
            choices: 'testing\nexperimental\nrelease',
            description: 'Repository component used to retrieve packages',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Build docker containers') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                script {
                        echo "====> Build docker containers"
                        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                        sh '''
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
                            curl -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                            . ./XB_VERSION
                            if [ \${XB_VERSION_MAJOR} = "26" ]; then
                                curl -O https://raw.githubusercontent.com/percona/percona-server/refs/heads/trunk/MYSQL_VERSION
                            else
                                curl -O https://raw.githubusercontent.com/percona/percona-server/refs/heads/${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}/MYSQL_VERSION
                            fi
                            . ./MYSQL_VERSION
                            rm -rf percona-docker
                            git clone $(echo ${REPO_DOCKER} | sed -e "s|https://github.com/|https://x-access-token:${TOKEN}@github.com/|")
                            cd percona-docker
                            git checkout ${REPO_DOCKER_BRANCH}
                            if [ \${MYSQL_VERSION_MINOR} = "0" ]; then
                                cd percona-xtrabackup-\${XB_VERSION_MAJOR}.0
                            else
                                cd percona-xtrabackup-\${XB_VERSION_MAJOR}.x
                            fi
                            XB_EXTRA_CLEAN="${XB_VERSION_EXTRA#-}"
                            if echo "${XB_EXTRA_CLEAN}" | grep -qE '^[0-9]+$'; then
                                XB_DOCKER_VERSION="${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}-${XB_EXTRA_CLEAN}.${RPM_RELEASE}"
                            else
                                XB_DOCKER_VERSION="${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}-${RPM_RELEASE}.${XB_EXTRA_CLEAN}"
                            fi
                            sed -i "s/ENV XTRABACKUP_VERSION.*/ENV XTRABACKUP_VERSION ${XB_DOCKER_VERSION}/g" Dockerfile
                            sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.1/g" Dockerfile
                            sed -i "s/ARG PXB_REPO=release/ARG PXB_REPO=${COMPONENT}/g" Dockerfile
                            sed -i "s/ARG PS_REPO=release/ARG PS_REPO=${COMPONENT}/g" Dockerfile
                            sed -i "s/ARG TOOLS_REPO=release/ARG TOOLS_REPO=${COMPONENT}/g" Dockerfile
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker build --provenance=false --no-cache -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --platform "linux/amd64" .
                                sudo docker build --provenance=false --no-cache -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --platform="linux/arm64" .
                            else
                                sudo docker pull perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 
                                sudo docker tag perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker pull perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 
                                sudo docker tag perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64
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
                                curl -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                                . ./XB_VERSION
                                if [ ${ORGANIZATION} != "percona" ]; then
                                    sudo docker push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                    sudo docker push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64

                                    sudo docker manifest create perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} \
                                        perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 \
                                        perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                    sudo docker manifest annotate perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                                    sudo docker manifest annotate perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --os linux --arch amd64
                                    sudo docker manifest inspect perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker manifest push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}

                                    sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:latest perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                               else
                                    sudo docker push percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                    sudo docker push percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64

                                    sudo docker manifest create percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} \
                                        percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 \
                                        percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                    sudo docker manifest annotate percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                                    sudo docker manifest annotate percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --os linux --arch amd64
                                    sudo docker manifest inspect percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker manifest push percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}

                                    sudo docker buildx imagetools create -t percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    if [ ${XB_VERSION_MINOR} = "0" ]; then
                                        sudo docker buildx imagetools create -t percona/percona-xtrabackup:latest percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    fi
                               fi
                            '''
                           }
                } // scripts
            } // steps
        } // stage
        stage('Attach SBOMs') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            when {
                expression {
                    // Container-image SBOM generation/attach only applies to
                    // PXB releases newer than 9.7
                    def rel = sh(returnStdout: true, script: '''
                        curl -fsSL -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                        . ./XB_VERSION
                        echo "${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}"
                    ''').trim()

                    env.PXB_RELEASE = rel
                    def (majorStr, minorStr) = rel.tokenize('.')[0..1]
                    int major = majorStr as int
                    int minor = minorStr as int

                    return (major > 9) || (major == 9 && minor >= 7)
                }
            }
            steps {
                script {
                    installSbomTools()

                    def PXB_RELEASE = env.PXB_RELEASE
                    def RPM_RELEASE = params.RPM_RELEASE
                    def ORGANIZATION = params.ORGANIZATION

                    def images = [
                        'percona-xtrabackup': "${ORGANIZATION}/percona-xtrabackup"
                    ]

                    withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                                                      passwordVariable: 'PASS',
                                                      usernameVariable: 'USER')]) {
                        sh 'echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin'
                        sh 'echo "${PASS}" | oras login -u "${USER}" --password-stdin docker.io'

                        images.each { name, repo ->
                            sh """
                                set -e
                                MANIFEST_TAG="${repo}:${PXB_RELEASE}.${RPM_RELEASE}"
                                ORAS_REF="docker.io/${repo}"
                                INSPECT=\$(oras manifest fetch "\${ORAS_REF}:${PXB_RELEASE}.${RPM_RELEASE}")

                                for ARCH in amd64 arm64; do
                                    DIGEST=\$(echo "\${INSPECT}" \
                                        | jq -r --arg a "\${ARCH}" '.manifests[] | select(.platform.architecture==\$a) | .digest')
                                    [ -n "\${DIGEST}" ] || { echo "ERROR: failed to resolve \${ARCH} digest for ${name}" >&2; exit 1; }

                                    SBOM_FILE="${name}-${PXB_RELEASE}.${RPM_RELEASE}-\${ARCH}.cdx.json"
                                    PURL="pkg:oci/${name}@\${DIGEST}?repository_url=${repo}"

                                    echo "Generating CycloneDX 1.6 SBOM for ${name} (\${ARCH})..."
                                    syft scan "registry:\${MANIFEST_TAG}-\${ARCH}" \
                                        --source-name "${name}" \
                                        --source-version "${PXB_RELEASE}" \
                                        -o "cyclonedx-json@1.6=\${SBOM_FILE}"

                                    jq --arg purl "\${PURL}" --arg ver "${PXB_RELEASE}" '.metadata.component = {
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
stage('Check by Trivy') {
    agent {
       label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
    }
    environment {
        TRIVY_LOG = "trivy-high-junit.xml"
    }
    steps {
        script {
            try {
                // 🔹 Fetch XtraBackup Version
                echo "🔄 Fetching XtraBackup version..."
                sh '''
                    curl -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                    cat XB_VERSION
                '''

                // ✅ Read the version file and parse it manually (CPS-compatible way)
                def versionEnv = [:]
                def versionContent = readFile('XB_VERSION')
                
                // 🔹 Manually split lines and process them
                versionContent.split("\n").each { line ->
                    def parts = line.split('=')
                    if (parts.length == 2) {
                        versionEnv[parts[0].trim()] = parts[1].trim()
                    }
                }

                // 🔹 Extract version components
                def XB_VERSION_MAJOR = versionEnv['XB_VERSION_MAJOR']
                def XB_VERSION_MINOR = versionEnv['XB_VERSION_MINOR']
                def XB_VERSION_PATCH = versionEnv['XB_VERSION_PATCH']
                def XB_VERSION_EXTRA = versionEnv['XB_VERSION_EXTRA']
                
                // 🔹 Install Trivy if not already installed
                installTrivy(method: 'apt')

                // 🔹 Define the image tags
                def imageList = [
                    "perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64",
                    "perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64"
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
            }
        }
    }
    post {
        always {
            // 🔹 Archive the report
            archiveArtifacts artifacts: "${TRIVY_LOG}", allowEmptyArchive: true
        }
    }
}
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                currentBuild.description = "Built on ${BRANCH} for ${ORGANIZATION} organization"
            }
            deleteDir()
        }
    }
}
