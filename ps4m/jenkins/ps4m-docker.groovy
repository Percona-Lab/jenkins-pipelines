library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Build the Percona Search for MongoDB (mongot) container image.
//
// Two per-arch images are produced in parallel (amd64 + arm64), each tagged
// `<version>-<arch>` and pushed to the chosen registry. A final stage
// stitches them together into multi-arch manifest tags (no -arch suffix)
// using `docker manifest`.
//
// Docker itself is installed at job start via the upstream convenience
// script (`curl -fsSL https://get.docker.com | sh`) so the pipeline can run
// on plain launcher agents that don't have docker pre-baked.

void installDocker() {
    sh '''
        set -ex
        # Always run the installer: most agents already have docker but ship
        # without the buildx plugin, and the get.docker.com script makes the
        # full docker-ce + docker-buildx-plugin stack version-consistent.
        curl -fsSL https://get.docker.com | sudo sh
        # Start daemon — systemd in CentOS-family launchers, service on older.
        sudo systemctl start docker 2>/dev/null || sudo service docker start 2>/dev/null || true

        # Clean any leftover containers, images and buildkit cache from
        # previous runs. The Dockerfile uses ENV (not ARG) for the channel/
        # version, so Buildkit will happily reuse a cached RUN layer that was
        # built with a different channel — even though the textual command
        # references ${MONGOT_REPO_CH}, the cache-key is the literal command,
        # not the expanded value. Nuke the cache to force a fresh build.
        sudo docker container prune -f         2>/dev/null || true
        sudo docker rmi -f $(sudo docker images -q) 2>/dev/null || true
        sudo docker builder prune -af          2>/dev/null || true

        # Verify before continuing.
        sudo docker info >/dev/null
        sudo docker buildx version
    '''
}

void buildPerArch(String dockerfile, String archTag) {
    sh """
        set -ex
        rm -rf percona-docker
        git clone --depth 1 --branch ${params.DOCKER_BRANCH} ${params.DOCKER_REPO} percona-docker
        cd percona-docker/percona-server-mongodb-mongot

        # Stamp version + repo channel into the Dockerfile so the image
        # pulls the exact RPM the user requested. Dockerfile uses the modern
        # `ENV KEY=VALUE` form, so sed matches the `=` not a space.
        sed -E "s|^ENV MONGOT_VERSION=.*|ENV MONGOT_VERSION=${params.MONGOT_VERSION}|"   -i ${dockerfile}
        sed -E "s|^ENV MONGOT_REPO_CH=.*|ENV MONGOT_REPO_CH=${params.MONGOT_REPO_CH}|"   -i ${dockerfile}
        sed -E "s|^ENV PSMDB_REPO=.*|ENV PSMDB_REPO=${params.PSMDB_REPO}|"               -i ${dockerfile}

        sudo docker build . -f ${dockerfile} -t percona-server-mongodb-mongot:${archTag}
    """
}

void pushPerArch(String registry, String archTag) {
    sh """
        set -ex
        MIN_VER=\$(echo ${params.MONGOT_VERSION} | awk -F "-" '{print \$1}')
        MAJ_VER=\$(echo \$MIN_VER | awk -F "." '{print \$1"."\$2}')

        IMG=${registry}/percona-server-mongodb-mongot

        sudo docker tag  percona-server-mongodb-mongot:${archTag} \$IMG:${params.MONGOT_VERSION}-${archTag}
        sudo docker push \$IMG:${params.MONGOT_VERSION}-${archTag}

        sudo docker tag  percona-server-mongodb-mongot:${archTag} \$IMG:\$MIN_VER-${archTag}
        sudo docker push \$IMG:\$MIN_VER-${archTag}

        sudo docker tag  percona-server-mongodb-mongot:${archTag} \$IMG:\$MAJ_VER-${archTag}
        sudo docker push \$IMG:\$MAJ_VER-${archTag}
    """
}

void manifestPush(String registry) {
    sh """
        set -ex
        MIN_VER=\$(echo ${params.MONGOT_VERSION} | awk -F "-" '{print \$1}')
        MAJ_VER=\$(echo \$MIN_VER | awk -F "." '{print \$1"."\$2}')
        IMG=${registry}/percona-server-mongodb-mongot
        LATEST=${params.LATEST}

        # Use `docker buildx imagetools create` instead of the legacy
        # `docker manifest` family: modern `docker build` pushes single-arch
        # images as OCI manifest lists with one entry, which the legacy
        # `docker manifest create` rejects with "is a manifest list".
        # imagetools handles both classic schema-v2 images and OCI lists,
        # and infers os/arch from the source images so no `annotate` call
        # is needed.
        for TAG in \$MAJ_VER \$MIN_VER ${params.MONGOT_VERSION}; do
            sudo docker buildx imagetools create -t \$IMG:\$TAG \\
                \$IMG:\$TAG-amd64 \\
                \$IMG:\$TAG-arm64
            sudo docker buildx imagetools inspect \$IMG:\$TAG
        done

        if [ "\$LATEST" = "yes" ]; then
            sudo docker buildx imagetools create -t \$IMG:latest \\
                \$IMG:\$MAJ_VER-amd64 \\
                \$IMG:\$MAJ_VER-arm64
            sudo docker buildx imagetools inspect \$IMG:latest
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
        string(name: 'MONGOT_VERSION', defaultValue: '0.50.0-1',
            description: 'mongot package version (e.g. 0.50.0-1). Used to tag the image.')
        choice(name: 'MONGOT_REPO_CH', choices: ['experimental', 'laboratory', 'testing', 'release'],
            description: 'percona-release channel containing the mongot RPM. Preview builds currently land in `experimental`. Promotion path: laboratory → testing → experimental → release.')
        string(name: 'PSMDB_REPO',     defaultValue: 'psmdb-83',
            description: 'percona-release repo name (mongot ships under PSMDB repo).')
        string(name: 'DOCKER_REPO',    defaultValue: 'https://github.com/vorsel/percona-docker.git',
            description: 'Source repo for the Dockerfile.')
        string(name: 'DOCKER_BRANCH',  defaultValue: 'main',
            description: 'Branch/tag in DOCKER_REPO.')
        choice(name: 'TARGET_REPO',    choices: ['perconalab', 'percona'],
            description: 'Docker Hub namespace to push to. Use `percona` for releases, `perconalab` for previews.')
        choice(name: 'LATEST',         choices: ['yes', 'no'],
            description: 'Also tag the multi-arch manifest as :latest.')
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
                    currentBuild.displayName = "${params.TARGET_REPO}/mongot:${params.MONGOT_VERSION} (${params.MONGOT_REPO_CH})"
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
                        installDocker()
                        buildPerArch('Dockerfile', 'amd64')
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                                passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh 'echo "$PASS" | sudo docker login -u "$USER" --password-stdin'
                            pushPerArch(params.TARGET_REPO, 'amd64')
                        }
                    }
                }
                stage('PS4M arm64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        installDocker()
                        buildPerArch('Dockerfile.aarch64', 'arm64')
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                                passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh 'echo "$PASS" | sudo docker login -u "$USER" --password-stdin'
                            pushPerArch(params.TARGET_REPO, 'arm64')
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
                installDocker()
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh 'echo "$PASS" | sudo docker login -u "$USER" --password-stdin'
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
            slackNotify("#releases-ci", "#00FF00",
                "[${JOB_NAME}]: Built mongot ${params.MONGOT_VERSION} (${params.TARGET_REPO}, channel ${params.MONGOT_REPO_CH}) — [${BUILD_URL}]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000",
                "[${JOB_NAME}]: Build of mongot ${params.MONGOT_VERSION} failed — [${BUILD_URL}]")
        }
    }
}
