library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Build the Percona Search for MongoDB (mongot) container image.
//
// Two per-arch images are produced in parallel (amd64 + arm64), each tagged
// `<version>-<arch>` and pushed to the chosen registry. A final stage
// stitches them together into multi-arch manifest tags (no -arch suffix)
// using `docker buildx imagetools`.

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
