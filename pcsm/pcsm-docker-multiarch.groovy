// PCSM Docker multi-arch + SBOM pipeline.
//
// Builds linux/amd64 and linux/arm64 PCSM container images in parallel,
// generates per-arch CycloneDX 1.6 SBOMs from the locally-built images,
// pushes per-arch tags, assembles a multi-arch manifest, then attaches the
// SBOMs as OCI 1.1 referrer artifacts (`oras attach`).

library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// ─── Helpers ─────────────────────────────────────────────────────────────────

def installSbomTools() {
    sh """
        set -e
        ORAS_VERSION=1.2.3

        # syft (auto-detects arch via Anchore installer)
        if ! command -v syft >/dev/null 2>&1; then
            for i in {1..3}; do
                curl -fsSL https://raw.githubusercontent.com/anchore/syft/main/install.sh \\
                    | sudo sh -s -- -b /usr/local/bin && break
                sleep 10
            done
            command -v syft >/dev/null \\
                || { echo "ERROR: syft install failed" >&2; exit 1; }
        fi

        # oras (manual download, pinned version)
        if ! command -v oras >/dev/null 2>&1; then
            UNAME=\$(uname -m)
            case "\$UNAME" in
                x86_64)         ORAS_ARCH=amd64 ;;
                aarch64|arm64)  ORAS_ARCH=arm64 ;;
                *) echo "ERROR: unsupported arch \$UNAME for oras" >&2; exit 1 ;;
            esac
            for i in {1..3}; do
                curl -fsSL "https://github.com/oras-project/oras/releases/download/v\${ORAS_VERSION}/oras_\${ORAS_VERSION}_linux_\${ORAS_ARCH}.tar.gz" \\
                        -o /tmp/oras.tar.gz \\
                    && sudo tar -xzf /tmp/oras.tar.gz -C /usr/local/bin oras \\
                    && rm -f /tmp/oras.tar.gz \\
                    && break
                sleep 10
            done
            command -v oras >/dev/null \\
                || { echo "ERROR: oras install failed" >&2; exit 1; }
        fi

        # jq via the host's package manager (apt/dnf/yum) when missing
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

// Returns [registry, credentialsId, tagPrefix] for the chosen TARGET_REPO.
//   - PerconaLab / DockerHub: hub.docker.com creds, no tag prefix
def registryFor(String targetRepo) {
    switch (targetRepo) {
        case 'PerconaLab':
            return [name: 'docker.io/perconalab/percona-clustersync-mongodb',
                    credId: 'hub.docker.com',
                    tagPrefix: '']
        case 'DockerHub':
            return [name: 'docker.io/percona/percona-clustersync-mongodb',
                    credId: 'hub.docker.com',
                    tagPrefix: '']
        default:
            error "Unknown TARGET_REPO: ${targetRepo}"
    }
}

// Per-arch build + SBOM + push. Runs inside an agent block selected by the
// caller (docker-x64 or docker-aarch64). Pushes a single per-arch tag named
// <registry>:<tagPrefix><MIN_VER>-<arch>.
//   ARCH       — 'amd64' or 'arm64'
//   DOCKERFILE — Dockerfile name (Dockerfile or Dockerfile.aarch64)
def buildArchAndSbom(String arch, String dockerfile) {
    def reg = registryFor(params.TARGET_REPO)
    def sbomFile = "percona-clustersync-mongodb-${params.PCSM_VERSION}-${arch}.cdx.json"

    deleteDir()

    sh """
        set -e
        git clone https://github.com/percona/percona-docker
        cd percona-docker/percona-clustersync-mongodb
        sed -E "s/ENV PCSM_VERSION (.+)/ENV PCSM_VERSION ${params.PCSM_VERSION}/" -i ${dockerfile}
        sed -E "s/ENV PCSM_REPO_CH (.+)/ENV PCSM_REPO_CH ${params.PCSM_REPO_CH}/" -i ${dockerfile}
        docker build . -f ${dockerfile} -t percona-clustersync-mongodb:local-${arch}
    """

    installTrivy(method: 'binary', junitTpl: true)
    sh """
        set -e
        curl https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/docker/trivyignore -o ".trivyignore"
        if [ "${params.PCSM_REPO_CH}" = "release" ]; then
            /usr/local/bin/trivy -q image --format template --template @junit.tpl \\
                -o trivy-high-junit-${arch}.xml \\
                --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL \\
                percona-clustersync-mongodb:local-${arch}
        else
            /usr/local/bin/trivy -q image --format template --template @junit.tpl \\
                -o trivy-high-junit-${arch}.xml \\
                --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL \\
                percona-clustersync-mongodb:local-${arch}
        fi
    """

    installSbomTools()

    // SBOM generated against the locally-built image. rpm-db
    // catalogers included for OS inventory (UBI9: rpm-db); go-module-binary
    // for the PCSM Go binary embedded by the .rpm install. File catalogers
    // disabled to keep package-level granularity.
    //
    // metadata.component is patched to a proper application/PURL identity
    // anchored to the local image digest (= registry digest post-push,
    // because image manifests are content-addressable).
    sh """
        set -e
        LOCAL_DIGEST=\$(docker inspect --format='{{.Id}}' percona-clustersync-mongodb:local-${arch})
        REGISTRY="${reg.name}"
        PURL="pkg:oci/percona-clustersync-mongodb@\${LOCAL_DIGEST}?repository_url=\${REGISTRY}"

        echo "Generating CycloneDX 1.6 SBOM for ${arch} image..."
        syft scan "docker:percona-clustersync-mongodb:local-${arch}" \\
            --override-default-catalogers go-module-binary-cataloger,dpkg-db-cataloger,rpm-db-cataloger \\
            --select-catalogers "-file" \\
            --source-name "percona-clustersync-mongodb" \\
            --source-version "${params.PCSM_VERSION}" \\
            -o "cyclonedx-json@1.6=${sbomFile}"

        jq --arg purl "\$PURL" --arg ver "${params.PCSM_VERSION}" '.metadata.component = {
            "bom-ref": \$purl,
            "type": "application",
            "name": "percona-clustersync-mongodb",
            "version": \$ver,
            "purl": \$purl
        }' "${sbomFile}" > "${sbomFile}.tmp" && mv "${sbomFile}.tmp" "${sbomFile}"

        COMPONENT_COUNT=\$(jq '.components | length' "${sbomFile}")
        if [ "\$COMPONENT_COUNT" -lt 10 ]; then
            echo "ERROR: ${arch} SBOM has only \$COMPONENT_COUNT components" >&2
            exit 1
        fi
        echo "${arch} SBOM: ${sbomFile} (\$COMPONENT_COUNT components)"
    """

    // Push only after SBOM is generated and validated. Both per-arch tags
    // (MIN_VER + MAJ_VER) are pushed here; multi-arch manifest assembly
    // happens in the dedicated stage.
    withCredentials([usernamePassword(credentialsId: reg.credId,
                                      passwordVariable: 'PASS',
                                      usernameVariable: 'USER')]) {
        sh 'echo "${PASS}" | docker login -u "${USER}" --password-stdin'
        sh """
            set -e
            MAJ_VER=\$(echo ${params.PCSM_VERSION} | awk -F "." '{print \$1}')
            MIN_VER=\$(echo ${params.PCSM_VERSION} | awk -F "-" '{print \$1}')
            REGISTRY="${reg.name}"
            PREFIX="${reg.tagPrefix}"

            docker tag percona-clustersync-mongodb:local-${arch} \${REGISTRY}:\${PREFIX}\${MAJ_VER}-${arch}
            docker push \${REGISTRY}:\${PREFIX}\${MAJ_VER}-${arch}

            docker tag percona-clustersync-mongodb:local-${arch} \${REGISTRY}:\${PREFIX}\${MIN_VER}-${arch}
            docker push \${REGISTRY}:\${PREFIX}\${MIN_VER}-${arch}
        """
    }

    stash includes: "${sbomFile}", name: "sbom-${arch}"
    junit testResults: "trivy-high-junit-${arch}.xml",
          keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
}

// ─── Pipeline ────────────────────────────────────────────────────────────────

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
        choice(name: 'PCSM_REPO_CH', choices: ['testing', 'release', 'experimental'], description: 'Percona-release repo')
        string(name: 'PCSM_VERSION', defaultValue: '0.8.1-1', description: 'PCSM version')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab', 'DockerHub'], description: 'Target registry for image + SBOM')
        choice(name: 'LATEST', choices: ['no', 'yes'], description: 'Also tag/push the multi-arch image as :latest')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${params.PCSM_REPO_CH}-${params.PCSM_VERSION}-multiarch"
                }
            }
        }

        stage('Build + SBOM (parallel)') {
            parallel {
                stage('amd64') {
                    agent { label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
                    steps {
                        script { buildArchAndSbom('amd64', 'Dockerfile') }
                    }
                    post {
                        always {
                            sh "sudo docker rmi -f \$(sudo docker images -q | uniq) || true"
                            deleteDir()
                        }
                    }
                }
                stage('arm64') {
                    agent { label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64' }
                    steps {
                        script { buildArchAndSbom('arm64', 'Dockerfile.aarch64') }
                    }
                    post {
                        always {
                            sh "sudo docker rmi -f \$(sudo docker images -q | uniq) || true"
                            deleteDir()
                        }
                    }
                }
            }
        }

        stage('Multi-arch manifest') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
            steps {
                script {
                    def reg = registryFor(params.TARGET_REPO)
                    withCredentials([usernamePassword(credentialsId: reg.credId,
                                                      passwordVariable: 'PASS',
                                                      usernameVariable: 'USER')]) {
                        sh 'echo "${PASS}" | docker login -u "${USER}" --password-stdin'
                        sh """
                            set -e
                            MAJ_VER=\$(echo ${params.PCSM_VERSION} | awk -F "." '{print \$1}')
                            MIN_VER=\$(echo ${params.PCSM_VERSION} | awk -F "-" '{print \$1}')
                            REGISTRY="${reg.name}"
                            PREFIX="${reg.tagPrefix}"

                            for TAG in \${PREFIX}\${MIN_VER} \${PREFIX}\${MAJ_VER}; do
                                docker buildx imagetools create -t \${REGISTRY}:\${TAG} \\
                                    \${REGISTRY}:\${TAG}-amd64 \\
                                    \${REGISTRY}:\${TAG}-arm64
                                docker buildx imagetools inspect \${REGISTRY}:\${TAG}
                            done

                            if [ "${params.LATEST}" = "yes" ]; then
                                docker buildx imagetools create -t \${REGISTRY}:\${PREFIX}latest \\
                                    \${REGISTRY}:\${PREFIX}\${MAJ_VER}-amd64 \\
                                    \${REGISTRY}:\${PREFIX}\${MAJ_VER}-arm64
                                docker buildx imagetools inspect \${REGISTRY}:\${PREFIX}latest
                            fi
                        """
                    }
                }
            }
        }

        stage('Attach SBOMs') {
            agent { label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
            steps {
                script {
                    installSbomTools()
                    def reg = registryFor(params.TARGET_REPO)
                    unstash 'sbom-amd64'
                    unstash 'sbom-arm64'

                    withCredentials([usernamePassword(credentialsId: reg.credId,
                                                      passwordVariable: 'PASS',
                                                      usernameVariable: 'USER')]) {
                        sh 'echo "${PASS}" | oras login -u "${USER}" --password-stdin docker.io'
                        sh """
                            set -e
                            MIN_VER=\$(echo ${params.PCSM_VERSION} | awk -F "-" '{print \$1}')
                            REGISTRY="${reg.name}"
                            PREFIX="${reg.tagPrefix}"
                            MANIFEST_TAG="\${REGISTRY}:\${PREFIX}\${MIN_VER}"

                            # Resolve per-arch image digests from the freshly-pushed multi-arch manifest.
                            AMD64_DIGEST=\$(docker manifest inspect \${MANIFEST_TAG} \\
                                | jq -r '.manifests[] | select(.platform.architecture=="amd64") | .digest')
                            ARM64_DIGEST=\$(docker manifest inspect \${MANIFEST_TAG} \\
                                | jq -r '.manifests[] | select(.platform.architecture=="arm64") | .digest')

                            test -n "\$AMD64_DIGEST" || { echo "ERROR: failed to resolve amd64 digest" >&2; exit 1; }
                            test -n "\$ARM64_DIGEST" || { echo "ERROR: failed to resolve arm64 digest" >&2; exit 1; }

                            SBOM_AMD64="percona-clustersync-mongodb-${params.PCSM_VERSION}-amd64.cdx.json"
                            SBOM_ARM64="percona-clustersync-mongodb-${params.PCSM_VERSION}-arm64.cdx.json"

                            oras attach --artifact-type application/vnd.cyclonedx+json \\
                                "\${REGISTRY}@\${AMD64_DIGEST}" "\${SBOM_AMD64}"
                            oras attach --artifact-type application/vnd.cyclonedx+json \\
                                "\${REGISTRY}@\${ARM64_DIGEST}" "\${SBOM_ARM64}"

                            echo "SBOMs attached:"
                            oras discover --format tree "\${REGISTRY}@\${AMD64_DIGEST}"
                            oras discover --format tree "\${REGISTRY}@\${ARM64_DIGEST}"
                        """
                    }
                    archiveArtifacts artifacts: '*.cdx.json',
                                     allowEmptyArchive: false,
                                     fingerprint: true
                }
            }
        }
    }
    post {
        always {
            sh """
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            """
            deleteDir()
        }
        success {
            slackNotify("#mongodb_autofeed", "#00FF00",
                "[${JOB_NAME}]: Multi-arch PCSM ${PCSM_VERSION} (${PCSM_REPO_CH}) → ${TARGET_REPO} with SBOMs: succeeded")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930",
                "[${JOB_NAME}]: Multi-arch PCSM ${PCSM_VERSION} (${PCSM_REPO_CH}) → ${TARGET_REPO}: unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000",
                "[${JOB_NAME}]: Multi-arch PCSM ${PCSM_VERSION} (${PCSM_REPO_CH}) → ${TARGET_REPO}: failed - [${BUILD_URL}]")
        }
    }
}
