library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// -----------------------------------------------------------------------------
// PSMDB-2057 helpers — three small primitives keep stage bodies one-liners:
//
//   runnerImage(distro)
//       Returns the full Docker Hub reference for a PSMDB BuildBarn runner
//       image. Tag schema is `<distro>-<psmdb-version>` (moving alias for
//       this 8.0 release line). The same multi-arch manifest list the
//       on-demand Buildbarn workers pull is used here, so install_deps()
//       ran at image-build time and Jenkins build agents skip the ~5–10 min
//       apt/dnf phase. Arch dimension lives in the manifest list (Docker
//       picks x86_64 vs aarch64 automatically from the host platform);
//       there is NO `-x86_64`/`-aarch64` suffix on the image tag. Registry
//       + tag are hardcoded because this is a 8.0-line-specific pipeline.
//       For immutable PR-pinned tags (`<distro>-8.0-<mongo-sha>`) see
//       bazel/platforms/psmdb_rbe_containers.bzl in the PSMDB workspace.
//
//   withRBE { … }
//       Wraps a block in withCredentials([string(...)]) + withEnv([…]) so
//       Jenkins's OIDC Provider plugin mints a fresh JWT into
//       PSMDB_RBE_JENKINS_TOKEN and the helper-related env vars
//       (PSMDB_RBE_OIDC_ISSUER, PSMDB_RBE_OIDC_CONNECTOR_ID,
//       PSMDB_RBE_BAZEL_FLAGS) reach the docker container. credential_helper
//       reads them at every Bazel /token call, so a 50-min build can
//       transparently roll the Dex token mid-action.
//
//   buildStage(image, stageParam, rbeEnabled = false)
//       Original Jenkins shim, now parameterised by image/RBE. When
//       rbeEnabled is true it appends `-e PSMDB_RBE_*` to the docker run line
//       so the four env vars cross the container boundary; with `-e VAR` (no
//       `=value`) docker inherits the value from the surrounding shell, so
//       `set -o xtrace` won't leak the Bearer token. install_deps is no
//       longer invoked from this helper because runner images already have
//       it baked in — to fall back to a stock distro image, restore the
//       legacy `--install_deps=1` call and switch runnerImage() to a stock
//       distro reference (e.g. "ubuntu:noble").
// -----------------------------------------------------------------------------
String runnerImage(String distro) {
    return "docker.io/perconalab/psmdb-rbe:${distro}-8.0"
}

def withRBE(Closure body) {
    // PSMDB-2057: augment user-supplied PSMDB_RBE_BAZEL_FLAGS with CI metadata
    // (bb-portal close-out item from buildbarn-portal-plan.md §"/builds page").
    //
    // bb-portal-backend creates a Build entity (visible at
    // https://bb-psmdb.ddns.net:7986/builds) ONLY when a BES event in the
    // invocation carries one of: BUILD_URL (Jenkins), CI_PIPELINE_URL
    // (GitLab) or the GitHub Actions trio. Without this metadata the
    // invocation still shows up in /invocations but is orphaned — no
    // grouping by Jenkins job, no per-job historical view.
    //
    // We pass the data via Bazel `--build_metadata=KEY=VALUE` (rather than
    // `--client_env=BUILD_URL` which would also leak BUILD_URL into every
    // remote action's env, polluting cache keys). build_metadata flows into
    // BES BuildMetadata events, which is exactly what bb-portal-backend
    // greps for. ROLE=CI marks the invocation as automated so /invocations
    // can filter human vs CI runs.
    def baseFlags = (params.PSMDB_RBE_BAZEL_FLAGS ?: '').trim()
    def ciMetadata = [
        "--build_metadata=BUILD_URL=${env.BUILD_URL}",
        "--build_metadata=BUILD_NUMBER=${env.BUILD_NUMBER}",
        "--build_metadata=JOB_NAME=${env.JOB_NAME}",
        "--build_metadata=ROLE=CI"
    ].join(' ')
    def augmentedFlags = "${baseFlags} ${ciMetadata}".trim()

    // PSMDB-2034 token-rotation sidecar:
    //
    // The Bazel CredentialHelper inside the build container can't refresh
    // a JWT it received via `-e PSMDB_RBE_JENKINS_TOKEN` once that JWT's
    // `exp` slips past now() — it has nothing to negotiate with Dex
    // (subject_token expired ⇒ Dex returns access_denied). Builds that
    // outlive one Jenkins-OIDC TTL (~2 h) therefore fail mid-build, even
    // though the pipeline itself is still alive and could happily mint
    // a fresh JWT.
    //
    // We fix that by running a sidecar `parallel` branch on the same
    // node / workspace that re-issues a JWT every TTL/2 minutes via the
    // OIDC Provider plugin and atomic-renames it into a workspace file
    // the helper reads. Every getSecret() call mints a fresh token
    // (io.jenkins.plugins.oidc_provider.IdTokenCredentials#token sets
    // iat=now / exp=now+lifetime on every call), so the loop needs no
    // custom plugin. Build branch signals end-of-life by touching a
    // flag file in `finally`, which lets the sidecar exit cleanly
    // without depending on the hard 24h timeout.
    //
    // The legacy PSMDB_RBE_JENKINS_TOKEN env is still set so dev runs
    // and any agent that hasn't picked up the helper's file-source
    // patch keep working. The helper prefers the file when both are
    // present.
    def tokenDir  = "${env.WORKSPACE}/.psmdb_rbe"
    def tokenFile = "${tokenDir}/token"
    def doneFlag  = "${tokenDir}/build.done"

    sh "mkdir -p ${tokenDir} && rm -f ${tokenFile} ${doneFlag}"

    withCredentials([
        string(
            credentialsId: params.PSMDB_RBE_OIDC_CREDENTIALS_ID,
            variable: 'PSMDB_RBE_JENKINS_TOKEN'
        )
    ]) {
        withEnv([
            "PSMDB_RBE_OIDC_ISSUER=${params.PSMDB_RBE_OIDC_ISSUER}",
            "PSMDB_RBE_OIDC_CONNECTOR_ID=${params.PSMDB_RBE_OIDC_CONNECTOR_ID}",
            "PSMDB_RBE_BAZEL_FLAGS=${augmentedFlags}",
            "PSMDB_RBE_JENKINS_TOKEN_FILE=${tokenFile}"
        ]) {
            // Seed the file with the initial token so the first helper
            // invocation is unblocked even before the sidecar produces
            // a write. A single sh (umask 077 + printf + atomic mv)
            // collapses writeFile+chmod+mv into one Pipeline UI step.
            sh '''
                set -e
                umask 077
                printf '%s' "$PSMDB_RBE_JENKINS_TOKEN" > "${PSMDB_RBE_JENKINS_TOKEN_FILE}.tmp"
                mv "${PSMDB_RBE_JENKINS_TOKEN_FILE}.tmp" "${PSMDB_RBE_JENKINS_TOKEN_FILE}"
            '''

            parallel(
                'token-refresh': {
                    timeout(time: 24, unit: 'HOURS') {
                        boolean done = false
                        while (!done) {
                            withCredentials([
                                string(
                                    credentialsId: params.PSMDB_RBE_OIDC_CREDENTIALS_ID,
                                    variable: 'TOK'
                                )
                            ]) {
                                int rc = sh(returnStatus: true, script: """
                                    set -e
                                    if [ -f '${doneFlag}' ]; then exit 42; fi
                                    umask 077
                                    printf '%s' "\$TOK" > '${tokenFile}.tmp'
                                    mv '${tokenFile}.tmp' '${tokenFile}'
                                """)
                                done = (rc == 42) // )))
                            }
                            if (!done) {
                                sleep(time: 50, unit: 'MINUTES')
                            }
                        }
                    }
                },
                'build-task': {
                    try {
                        body()
                    } finally {
                        sh "touch ${doneFlag}"
                    }
                },
                failFast: true
            )
        }
    }
}

void buildStage(String DOCKER_OS, String STAGE_PARAM, boolean RBE_ENABLED = false) {
    String dockerEnvFlags = ""
    if (RBE_ENABLED) {
        // -v ${build_dir}:${build_dir} (set later) makes the sidecar-rotated
        // file at $PSMDB_RBE_JENKINS_TOKEN_FILE visible inside the container;
        // passing the path here lets the helper find it.
        dockerEnvFlags = "-e PSMDB_RBE_JENKINS_TOKEN -e PSMDB_RBE_JENKINS_TOKEN_FILE -e PSMDB_RBE_OIDC_ISSUER -e PSMDB_RBE_OIDC_CONNECTOR_ID -e PSMDB_RBE_BAZEL_FLAGS"
    }
    sh """
        set -o xtrace
        ls -laR ./
        # Backup properties file if it exists
        if [ -f test/percona-server-mongodb-80.properties ]; then
            cp test/percona-server-mongodb-80.properties percona-server-mongodb-80.properties.backup
        fi
        rm -rf test/*
        mkdir -p test
        # Restore properties file if it was backed up
        if [ -f percona-server-mongodb-80.properties.backup ]; then
            mv percona-server-mongodb-80.properties.backup test/percona-server-mongodb-80.properties
        fi
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run ${dockerEnvFlags} -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            ls -laR ./
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} --mongo_tools_tag=${MONGO_TOOLS_TAG} ${STAGE_PARAM}"
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
        label params.CLOUD == 'AWS' ? 'micro-amazon' : 'launcher-x64'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for  percona-server-mongodb repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'v8.0',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '8.0.0',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.17.0',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-80',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            name: 'TESTS',
            choices: ['yes', 'no'],
            description: 'Run functional tests on packages and tarballs after building')
        // PSMDB-2057: RBE-related parameters. All three are consumed by
        // percona-packaging (PSMDB-2054 patch) and by bazel/wrapper_hook/
        // credential_helper.py (PSMDB-2034). Empty PSMDB_RBE_BAZEL_FLAGS
        // disables RBE and falls back to the legacy local build path.
        string(
            defaultValue: 'PSMDB-RBE-OIDC',
            description: 'Jenkins credentialsId of the OIDC token credential (audience=bazel-jenkins). The credential type must be "OpenID Connect ID token" issued by the OIDC Provider plugin.',
            name: 'PSMDB_RBE_OIDC_CREDENTIALS_ID')
        string(
            defaultValue: '',
            description: 'Dex issuer URL the credential_helper exchanges the Jenkins OIDC token against (RFC 8693 token-exchange).',
            name: 'PSMDB_RBE_OIDC_ISSUER')
        // Dex requires the `connector_id` form field on /token when the
        // grant is RFC 8693 token-exchange — without it Dex cannot pick
        // the OIDC connector that validates the subject_token's iss
        // (returns "invalid_request: Requested connector does not exist").
        // Default tracks the `jenkins-psmdb-rbe` connector id in dex.yaml.
        string(
            defaultValue: 'jenkins-psmdb-rbe',
            description: 'Dex connector_id form field for the token-exchange grant. Must match `connectors[].id` in dex.yaml.',
            name: 'PSMDB_RBE_OIDC_CONNECTOR_ID')
        string(
            defaultValue: '',
            description: 'Bazel flags injected by percona-packaging into the bazel build command line. Leave empty to disable RBE.',
            name: 'PSMDB_RBE_BAZEL_FLAGS')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PSMDB source tarball') {
            agent {
                label params.CLOUD == 'AWS' ? 'docker' : 'docker-x64'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    // Source tarball does not invoke bazel; no RBE wiring needed.
                    buildStage(runnerImage('oraclelinux-8'), "--get_sources=1")
                }
                sh '''
                   # Use 80 properties file; if build script created a different one, copy to 80 for pipeline
                   if [ ! -f test/percona-server-mongodb-80.properties ]; then
                       OTHER=$(ls test/percona-server-mongodb-*.properties 2>/dev/null | head -1)
                       if [ -n "$OTHER" ]; then
                           cp "$OTHER" test/percona-server-mongodb-80.properties
                       else
                           echo "No percona-server-mongodb-*.properties found in test/"
                           ls -la test/ || true
                           exit 1
                       fi
                   fi
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-80.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-80.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-mongodb-80.properties', name: 'psmdb-properties'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PSMDB generic source packages') {
            parallel {
                stage('Build PSMDB generic source rpm') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            // src_rpm is a pure rpmbuild step (no bazel); no RBE wiring needed.
                            buildStage(runnerImage('oraclelinux-8'), "--build_src_rpm=1")
                        }

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PSMDB generic source deb') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            // src_deb runs dpkg-buildpackage -S only (no bazel); no RBE wiring.
                            // Needs a debian-based runner so dch/dpkg-dev are present —
                            // ubuntu-jammy is the historical default.
                            buildStage(runnerImage('ubuntu-jammy'), "--build_src_deb=1")
                        }
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PSMDB RPMs/DEBs/Binary tarballs') {
            parallel {
                // PSMDB-2057: every stage below invokes bazel (rpmbuild's %build,
                // debian/rules, or psmdb_builder.sh build_tarball) so all are
                // wrapped in withRBE { ... } and pass RBE_ENABLED=true to
                // buildStage(). Runner images are resolved through runnerImage()
                // so a single PSMDB_RBE_RUNNER_REGISTRY/_TAG flip retargets them.
                stage('Oracle Linux 8(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('oraclelinux-8'), "--build_rpm=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('oraclelinux-8'), "--build_rpm=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('oraclelinux-9'), "--build_rpm=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('oraclelinux-9'), "--build_rpm=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('amazonlinux-2023'), "--build_rpm=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('amazonlinux-2023'), "--build_rpm=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('ubuntu-jammy'), "--build_deb=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04)(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('ubuntu-jammy'), "--build_deb=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)(x86_64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('ubuntu-noble'), "--build_deb=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)(aarch64)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb-aarch64' : 'docker-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('ubuntu-noble'), "--build_deb=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bookworm(12)(x86_64)') {
                    // Note: only x86_64 — no debian-bookworm-aarch64 runner image
                    // exists in IaC/buildbarn/runners/ (see README.md matrix).
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('debian-bookworm'), "--build_deb=1", true)
                            }
                        }
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 8 binary tarball(glibc2.28)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('oraclelinux-8'), "--build_tarball=1", true)
                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 binary tarball(glibc2.34)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('oraclelinux-9'), "--build_tarball=1", true)
                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Amazon Linux 2023 binary tarball(glibc2.34)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('amazonlinux-2023'), "--build_tarball=1", true)
                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) binary tarball(glibc2.35)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('ubuntu-jammy'), "--build_tarball=1", true)
                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Noble(24.04) binary tarball(glibc2.39)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('ubuntu-noble'), "--build_tarball=1", true)
                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12) binary tarball(glibc2.36)') {
                    agent {
                        label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'psmdb-properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        withRBE {
                            script {
                                buildStage(runnerImage('debian-bookworm'), "--build_tarball=1", true)
                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
            }
        }

        stage('Upload packages and tarballs from S3') {
            agent {
                label params.CLOUD == 'AWS' ? 'docker-64gb' : 'docker-x64'
            }
            steps {
                cleanUpWS()

                uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
            }
        }

        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                script {
                    sync2ProdAutoBuild(params.CLOUD, PSMDB_REPO, COMPONENT)
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "psmdb", "${PSMDB_VERSION}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Run testing job') {
            // PSMDB-2057: v8.0 release line keeps `hetzner-psmdb-multijob-testing`
            // (matches v8.0 testing infra). v8.3 swapped to psmdb-tarball-functional
            // + psmdb-parallel — do not propagate that here without an 8.0
            // counterpart job being defined.
            when {
                expression { return params.TESTS == 'yes' }
            }
            steps {
                script {
                    build job: 'hetzner-psmdb-multijob-testing', propagate: false, wait: false, quietPeriod: 1800, parameters: [string(name: 'PSMDB_VERSION', value: PSMDB_VERSION), string(name: 'PSMDB_RELEASE', value: PSMDB_RELEASE)]
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"

                // PSMDB-2057: write branch_commit_id_80.last_successful to S3 so
                // get-psmdb-branches-8.0 (cron-triggered every 15 min) skips a
                // rebuild for this branch/commit on the next tick. This is the
                // authoritative "we already built X" record — independent of
                // branch_commit_id_80.properties, which is just the last-detected
                // state and can be corrupt/nulled without re-triggering builds.
                String S3_STASH = (params.CLOUD == 'AWS') ? 'AWS_STASH' : 'HTZ_STASH'
                String S3_ENDPOINT = (params.CLOUD == 'AWS') ? '--endpoint-url https://s3.amazonaws.com' : '--endpoint-url https://fsn1.your-objectstorage.com'
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_STASH, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -euo pipefail
                        BUILT_COMMIT=\$(git ls-remote --heads ${GIT_REPO} ${GIT_BRANCH} | awk '{print \$1}')
                        if [ -z "\${BUILT_COMMIT}" ]; then
                            echo "WARN: could not resolve commit for ${GIT_BRANCH}; not writing last_successful"
                            exit 0
                        fi
                        cat > branch_commit_id_80.last_successful <<EOF
BRANCH_NAME=${GIT_BRANCH}
COMMIT_ID=\${BUILT_COMMIT}
MONGO_TOOLS_TAG=${MONGO_TOOLS_TAG}
EOF
                        AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp branch_commit_id_80.last_successful s3://percona-jenkins-artifactory/percona-server-mongodb/ ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                        echo "Recorded last_successful: ${GIT_BRANCH}@\${BUILT_COMMIT}"
                    """
                }
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
