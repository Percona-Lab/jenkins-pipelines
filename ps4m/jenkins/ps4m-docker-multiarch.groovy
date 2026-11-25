library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Build the Percona Search for MongoDB (mongot) container image.
//
// Two per-arch images are produced in parallel (amd64 + arm64), each tagged
// `<version>-<arch>` and pushed to the chosen registry. The next stage
// stitches them together into multi-arch manifest tags (no -arch suffix)
// using `docker buildx imagetools`; the last one attaches each per-arch
// image's CycloneDX SBOM to it as an OCI referrer (`oras attach`).

void buildPerArch(String dockerfile, String archTag) {
    sh """
        set -ex
        rm -rf percona-docker
        git clone --depth 1 --branch ${params.DOCKER_BRANCH} ${params.DOCKER_REPO} percona-docker
        cd percona-docker/percona-search-mongodb

        # Stamp version + repo channel into the Dockerfile so the image
        # pulls the exact RPM the user requested. Dockerfile uses the modern
        # `ENV KEY=VALUE` form, so sed matches the `=` not a space.
        sed -E "s|^ENV PS4M_VERSION=.*|ENV PS4M_VERSION=${params.PS4M_VERSION}|"   -i ${dockerfile}
        sed -E "s|^ENV PS4M_REPO_CH=.*|ENV PS4M_REPO_CH=${params.PS4M_REPO_CH}|"   -i ${dockerfile}
        sed -E "s|^ENV PS4M_REPO=.*|ENV PS4M_REPO=${params.PS4M_REPO}|"               -i ${dockerfile}

        # `buildx build --load` puts the built image into the local docker
        # image store so the subsequent `docker tag` / `docker push` find it.
        # `--provenance=false --sbom=false` disables BuildKit attestations,
        # which would otherwise wrap this single-arch image in an OCI manifest
        # list (index) and complicate per-arch tagging and manifest assembly.
        docker buildx build --load --provenance=false --sbom=false \\
            -f ${dockerfile} -t percona-search-mongodb:${archTag} .
    """
}

void scanPerArch(String archTag) {
    installTrivy(method: 'binary', junitTpl: true)
    sh """
        set -e
        curl https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/docker/trivyignore -o ".trivyignore"
        if [ "${params.PS4M_REPO_CH}" = "release" ]; then
            /usr/local/bin/trivy -q image --format template --template @junit.tpl \\
                -o trivy-high-junit-${archTag}.xml \\
                --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL \\
                percona-search-mongodb:${archTag}
        else
            /usr/local/bin/trivy -q image --format template --template @junit.tpl \\
                -o trivy-high-junit-${archTag}.xml \\
                --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL \\
                percona-search-mongodb:${archTag}
        fi
    """
}

// Same pins as percona-mongot's percona-packaging/sbom.
void installSbomTools() {
    sh '''
        set -e
        SYFT_VERSION=v1.54.1
        ORAS_VERSION=1.2.3
        CYCLONEDX_CLI_VERSION=v0.33.1
        case "$(uname -m)" in
            x86_64)
                ARCH=amd64
                ORAS_SHA256=b4efc97a91f471f323f193ea4b4d63d8ff443ca3aab514151a30751330852827
                CDX_ASSET=cyclonedx-linux-x64
                CDX_SHA256=bfc8b2538da86fe239bc53658bbb63c1c8c510a293c1e6891aa5bea5d3c58746 ;;
            aarch64)
                ARCH=arm64
                ORAS_SHA256=90e24e234dc6dffe73365533db66fd14449d2c9ae77381081596bf92f40f6b82
                CDX_ASSET=cyclonedx-linux-arm64
                CDX_SHA256=b2e9fdf9665ef49868a2ec012171c6e785dcd69745bc5869e53e4f4bfb096a5f ;;
            *) echo "ERROR: unsupported arch $(uname -m)" >&2; exit 1 ;;
        esac
        export PATH=/usr/local/bin:$PATH

        if [ "$(syft version 2>/dev/null | awk '/^Version:/ {print "v"$2}')" != "$SYFT_VERSION" ]; then
            # the tag's installer verifies the release checksums itself
            curl -fsSL --retry 3 "https://raw.githubusercontent.com/anchore/syft/${SYFT_VERSION}/install.sh" \\
                | sudo sh -s -- -b /usr/local/bin "$SYFT_VERSION"
        fi
        if ! oras version 2>/dev/null | grep -q "^Version: *${ORAS_VERSION}$"; then
            curl -fsSL --retry 3 -o /tmp/oras.tar.gz \\
                "https://github.com/oras-project/oras/releases/download/v${ORAS_VERSION}/oras_${ORAS_VERSION}_linux_${ARCH}.tar.gz"
            echo "${ORAS_SHA256}  /tmp/oras.tar.gz" | sha256sum -c --quiet
            sudo tar -xzf /tmp/oras.tar.gz -C /usr/local/bin oras
            rm -f /tmp/oras.tar.gz
        fi
        if ! echo "${CDX_SHA256}  /usr/local/bin/cyclonedx" | sha256sum -c --status 2>/dev/null; then
            curl -fsSL --retry 3 -o /tmp/cyclonedx \\
                "https://github.com/CycloneDX/cyclonedx-cli/releases/download/${CYCLONEDX_CLI_VERSION}/${CDX_ASSET}"
            echo "${CDX_SHA256}  /tmp/cyclonedx" | sha256sum -c --quiet
            sudo install -m 0755 /tmp/cyclonedx /usr/local/bin/cyclonedx
            rm -f /tmp/cyclonedx
        fi
        if ! command -v jq >/dev/null 2>&1; then
            if command -v apt-get >/dev/null 2>&1; then
                sudo apt-get update && sudo apt-get install -y jq
            else
                sudo yum install -y jq
            fi
        fi
        syft version | grep '^Version'
        oras version | grep '^Version'
        jq --version
    '''
}

// Merges syft's OS inventory of the image (--slurpfile os) with the SBOM the
// rpm ships in /usr/share/doc (--slurpfile pkg). syft's own entry for the
// percona-search-mongodb rpm is replaced by the package SBOM's root, which
// then depends on both its OS requirements and the bundle components. Fails
// rather than emit duplicate purls.
String imageSbomMergeJq() {
    return '''
def ren($a; $b): if . == $a then $b else . end;

$os[0] as $o
| $pkg[0] as $p
| $p.metadata.component as $prod
| [$o.components[] | select(.name == $prod.name and ((.purl // "") | startswith("pkg:rpm/")))] as $hits
| if ($hits | length) != 1
  then error("expected one rpm entry for " + $prod.name + " in the image, got " + ($hits | length | tostring))
  else . end
| $hits[0]["bom-ref"] as $old
| $prod["bom-ref"] as $new
| (($o.components | map(select(.["bom-ref"] != $old)))
    + [$prod + {properties: ($hits[0].properties // [])}]
    + $p.components) as $components
| ((($o.dependencies // []) | map(.ref |= ren($old; $new) | .dependsOn |= ((. // []) | map(ren($old; $new)))))
    + ($p.dependencies // [])
  | group_by(.ref)
  | map({ref: .[0].ref, dependsOn: ([.[].dependsOn // [] | .[]] | unique)})) as $deps
| ([$deps[].dependsOn[]] | unique) as $referenced
| (($components | map(.["bom-ref"])) - $referenced) as $top
| {
    "$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json",
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    serialNumber: $serial,
    version: 1,
    metadata: {
      timestamp: $timestamp,
      lifecycles: [{phase: "post-build"}],
      tools: {components: ((($o.metadata.tools.components // []) + ($p.metadata.tools.components // [])) | unique_by(.name))},
      component: {type: "container", "bom-ref": $root, supplier: $p.metadata.supplier, name: $prod.name, version: $version},
      supplier: $p.metadata.supplier,
      properties: ($o.metadata.properties // [])
    },
    components: $components,
    dependencies: ([{ref: $root, dependsOn: $top}] + $deps)
  }
| ([.components[].purl // empty] | group_by(.) | map(select(length > 1) | .[0])) as $dups
| if ($dups | length) > 0
  then error("duplicate purls in the image SBOM: " + ($dups | join(", ")))
  else . end
'''
}

// CycloneDX SBOM of the local per-arch image, written to
// percona-search-mongodb-<PS4M_VERSION>-<arch>.cdx.json. Its root gets the
// registry digest later, in the attach stage.
//
// Java content is not scanned here: syft only recognises a fraction of the
// jars in the mongot deploy jar, so it comes from the SBOM the rpm ships
// (built from the bazel inventory) and syft does OS packages only. Images of
// releases that predate that SBOM fall back to a plain syft scan; the build
// goes UNSTABLE because that one is incomplete.
void sbomPerArch(String archTag) {
    installSbomTools()
    writeFile file: 'image-sbom-merge.jq', text: imageSbomMergeJq()
    sh """
        set -e
        export PATH=/usr/local/bin:\$PATH
        # cyclonedx-cli is .NET; JSON validation needs no libicu
        export DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=true
        IMG=percona-search-mongodb:${archTag}
        OUT=percona-search-mongodb-${params.PS4M_VERSION}-${archTag}.cdx.json
        ROOT=percona-search-mongodb-image-${archTag}
        rm -rf sbom-work && mkdir -p sbom-work/doc

        CID=\$(docker create \$IMG)
        docker cp \$CID:/usr/share/doc/percona-search-mongodb/. sbom-work/doc/ || true
        docker rm \$CID >/dev/null
        PKG_SBOM=\$(ls sbom-work/doc/*.cdx.json 2>/dev/null | head -n1)

        if [ -n "\$PKG_SBOM" ]; then
            syft scan "docker:\$IMG" -q \\
                --override-default-catalogers rpm-db-cataloger \\
                --select-catalogers "-file" \\
                -o "cyclonedx-json@1.6=sbom-work/os.cdx.json"
            jq -n --slurpfile os sbom-work/os.cdx.json --slurpfile pkg "\$PKG_SBOM" \\
                --arg serial "urn:uuid:\$(cat /proc/sys/kernel/random/uuid)" \\
                --arg timestamp "\$(date -u +%Y-%m-%dT%H:%M:%SZ)" \\
                --arg root "\$ROOT" --arg version "${params.PS4M_VERSION}" \\
                -f image-sbom-merge.jq > "\$OUT"
        else
            echo "WARNING: no package SBOM in \$IMG, falling back to a plain syft scan" >&2
            touch sbom-work/partial
            syft scan "docker:\$IMG" -q \\
                --override-default-catalogers rpm-db-cataloger,java-archive-cataloger,java-jvm-cataloger \\
                --select-catalogers "-file" \\
                -o "cyclonedx-json@1.6=sbom-work/raw.cdx.json"
            jq --arg root "\$ROOT" --arg version "${params.PS4M_VERSION}" '.metadata.component = {
                "type": "container",
                "bom-ref": \$root,
                "supplier": {"name": "Percona LLC", "url": ["https://www.percona.com"]},
                "name": "percona-search-mongodb",
                "version": \$version
            }' sbom-work/raw.cdx.json > "\$OUT"
        fi

        cyclonedx validate --input-file "\$OUT" --input-format json --input-version v1_6 --fail-on-errors
        echo "\$OUT: \$(jq '.components | length' "\$OUT") components"
    """
    if (fileExists('sbom-work/partial')) {
        unstable("Image SBOM for ${archTag} is a plain syft scan: the rpm ships no SBOM")
    }
    stash includes: "percona-search-mongodb-${params.PS4M_VERSION}-${archTag}.cdx.json", name: "sbom-${archTag}"
}

// Gives each per-arch SBOM the digest the registry assigned to that image and
// attaches it to the image as an OCI referrer.
void attachSboms(String registry) {
    sh """
        set -e
        export PATH=/usr/local/bin:\$PATH
        # cyclonedx-cli is .NET; JSON validation needs no libicu
        export DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=true
        REPO=docker.io/${registry}/percona-search-mongodb
        INDEX=\$(docker buildx imagetools inspect --raw \$REPO:${params.PS4M_VERSION})
        for ARCH in amd64 arm64; do
            SBOM=percona-search-mongodb-${params.PS4M_VERSION}-\$ARCH.cdx.json
            DIGEST=\$(echo "\$INDEX" | jq -r --arg a \$ARCH '.manifests[] | select(.platform.architecture == \$a) | .digest')
            test -n "\$DIGEST" || { echo "ERROR: no \$ARCH image in \$REPO:${params.PS4M_VERSION}" >&2; exit 1; }

            PURL="pkg:oci/percona-search-mongodb@\$(echo \$DIGEST | sed 's/:/%3A/')?arch=\$ARCH&repository_url=\$(echo \$REPO | sed 's|/|%2F|g')"
            jq --arg ref "\$PURL" '.metadata.component["bom-ref"] as \$old
                | .metadata.component += {"bom-ref": \$ref, "purl": \$ref}
                | .dependencies |= map(if .ref == \$old then .ref = \$ref else . end)' \\
                "\$SBOM" > "\$SBOM.tmp" && mv "\$SBOM.tmp" "\$SBOM"
            cyclonedx validate --input-file "\$SBOM" --input-format json --input-version v1_6 --fail-on-errors

            oras attach --artifact-type application/vnd.cyclonedx+json "\$REPO@\$DIGEST" "\$SBOM"
            oras discover --format tree "\$REPO@\$DIGEST"
        done
    """
}

void pushPerArch(String registry, String archTag) {
    sh """
        set -ex
        MIN_VER=\$(echo ${params.PS4M_VERSION} | awk -F "-" '{print \$1}')
        MAJ_VER=\$(echo \$MIN_VER | awk -F "." '{print \$1}')

        IMG=${registry}/percona-search-mongodb

        docker tag  percona-search-mongodb:${archTag} \$IMG:${params.PS4M_VERSION}-${archTag}
        docker push \$IMG:${params.PS4M_VERSION}-${archTag}

        docker tag  percona-search-mongodb:${archTag} \$IMG:\$MIN_VER-${archTag}
        docker push \$IMG:\$MIN_VER-${archTag}

        # Major-only tag (:0-<arch>) is gated: while mongot is on the 0.x
        # series a bare major tag is not a useful rolling tag, so it is pushed
        # only when MAJOR_TAGS=yes.
        if [ "${params.MAJOR_TAGS}" = "yes" ]; then
            docker tag  percona-search-mongodb:${archTag} \$IMG:\$MAJ_VER-${archTag}
            docker push \$IMG:\$MAJ_VER-${archTag}
        fi
    """
}

void manifestPush(String registry) {
    sh """
        set -ex
        MIN_VER=\$(echo ${params.PS4M_VERSION} | awk -F "-" '{print \$1}')
        MAJ_VER=\$(echo \$MIN_VER | awk -F "." '{print \$1}')
        IMG=${registry}/percona-search-mongodb
        LATEST=${params.LATEST}
        CH=${params.PS4M_REPO_CH}

        # Assemble the multi-arch manifest from the per-arch tags with
        # `docker buildx imagetools create` (not the legacy `docker manifest`
        # family). imagetools handles both plain schema-v2 images and OCI
        # lists and infers os/arch from the source images, so no separate
        # `docker manifest annotate` call is needed.
        for TAG in \$MIN_VER ${params.PS4M_VERSION}; do
            docker buildx imagetools create -t \$IMG:\$TAG \\
                \$IMG:\$TAG-amd64 \\
                \$IMG:\$TAG-arm64
            docker buildx imagetools inspect \$IMG:\$TAG
        done

        # Major-only manifest tag (:0) — gated while on the 0.x series, same
        # as the per-arch push above.
        if [ "${params.MAJOR_TAGS}" = "yes" ]; then
            docker buildx imagetools create -t \$IMG:\$MAJ_VER \\
                \$IMG:\$MAJ_VER-amd64 \\
                \$IMG:\$MAJ_VER-arm64
            docker buildx imagetools inspect \$IMG:\$MAJ_VER
        fi

        # `latest` is built from the always-pushed MIN_VER per-arch images
        # (not MAJ_VER, which may be gated off). Fail-closed on channel: only
        # a `release` build may move the rolling :latest tag, so a preview
        # build (experimental/testing/laboratory) can never publish :latest
        # even if LATEST=yes.
        if [ "\$LATEST" = "yes" ] && [ "\$CH" = "release" ]; then
            docker buildx imagetools create -t \$IMG:latest \\
                \$IMG:\$MIN_VER-amd64 \\
                \$IMG:\$MIN_VER-arm64
            docker buildx imagetools inspect \$IMG:latest
        fi
    """
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(name: 'CLOUD',         choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra for build')
        string(name: 'PS4M_VERSION', defaultValue: '1.70.1-1',
            description: 'mongot package version (e.g. 1.70.1-1). Used to tag the image.')
        choice(name: 'PS4M_REPO_CH', choices: ['experimental', 'laboratory', 'testing', 'release'],
            description: 'percona-release channel containing the mongot RPM. Preview builds currently land in `experimental`. Promotion path: laboratory → testing → experimental → release.')
        string(name: 'PS4M_REPO',     defaultValue: 'ps4m',
            description: 'percona-release repo name (mongot ships under the ps4m repo).')
        string(name: 'DOCKER_REPO',    defaultValue: 'https://github.com/percona/percona-docker.git',
            description: 'Source repo for the Dockerfile.')
        string(name: 'DOCKER_BRANCH',  defaultValue: 'main',
            description: 'Branch/tag in DOCKER_REPO.')
        choice(name: 'TARGET_REPO',    choices: ['perconalab', 'percona'],
            description: 'Docker Hub namespace to push to. Use `percona` for releases, `perconalab` for previews.')
        choice(name: 'LATEST',         choices: ['yes', 'no'],
            description: 'Also tag the multi-arch manifest as :latest. Takes effect ONLY when PS4M_REPO_CH=release — a non-release (preview) build never publishes :latest regardless of this value.')
        choice(name: 'MAJOR_TAGS',     choices: ['no', 'yes'],
            description: 'Also push major-only tags (:0, :0-<arch>). Off by default — mongot is on the 0.x series, so a bare major tag (0) is not yet meaningful.')
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${params.TARGET_REPO}/ps4m:${params.PS4M_VERSION} (${params.PS4M_REPO_CH})"
                }
            }
        }
        stage('Build per-arch images') {
            parallel {
                stage('PS4M amd64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        buildPerArch('Dockerfile', 'amd64')
                        scanPerArch('amd64')
                        sbomPerArch('amd64')
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                                passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh 'echo "$PASS" | docker login -u "$USER" --password-stdin'
                            pushPerArch(params.TARGET_REPO, 'amd64')
                        }
                    }
                    post {
                        always {
                            junit testResults: "trivy-high-junit-amd64.xml", keepLongStdio: true,
                                  allowEmptyResults: true, skipPublishingChecks: true
                            sh "sudo docker rmi -f \$(sudo docker images -q | uniq) || true"
                            deleteDir()
                        }
                    }
                }
                stage('PS4M arm64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        buildPerArch('Dockerfile.aarch64', 'arm64')
                        scanPerArch('arm64')
                        sbomPerArch('arm64')
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                                passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh 'echo "$PASS" | docker login -u "$USER" --password-stdin'
                            pushPerArch(params.TARGET_REPO, 'arm64')
                        }
                    }
                    post {
                        always {
                            junit testResults: "trivy-high-junit-arm64.xml", keepLongStdio: true,
                                  allowEmptyResults: true, skipPublishingChecks: true
                            sh "sudo docker rmi -f \$(sudo docker images -q | uniq) || true"
                            deleteDir()
                        }
                    }
                }
            }
        }
        stage('PS4M multi-arch manifest') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh 'echo "$PASS" | docker login -u "$USER" --password-stdin'
                    manifestPush(params.TARGET_REPO)
                }
            }
        }
        stage('PS4M attach SBOMs') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
            }
            steps {
                unstash 'sbom-amd64'
                unstash 'sbom-arm64'
                installSbomTools()
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh 'echo "$PASS" | /usr/local/bin/oras login -u "$USER" --password-stdin docker.io'
                    attachSboms(params.TARGET_REPO)
                }
                archiveArtifacts artifacts: '*.cdx.json', allowEmptyArchive: false, fingerprint: true
            }
        }
    }
    post {
        always {
            sh '''
                sudo docker rmi -f $(sudo docker images -q | uniq) 2>/dev/null || true
                sudo rm -rf ${WORKSPACE}/* || true
            '''
            deleteDir()
        }
        success {
            slackNotify("#mongodb_autofeed", "#00FF00",
                "[${JOB_NAME}]: Built mongot ${params.PS4M_VERSION} (${params.TARGET_REPO}, channel ${params.PS4M_REPO_CH}) — [${BUILD_URL}]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000",
                "[${JOB_NAME}]: Build of mongot ${params.PS4M_VERSION} failed — [${BUILD_URL}]")
        }
    }
}
