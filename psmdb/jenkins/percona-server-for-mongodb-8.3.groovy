library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// new helpers:
//   runnerImage(distro)  -> docker hub ref for the rbe runner (multi-arch, tag <distro>-8.3)
//   withRBE { }          -> mints/rotates the oidc jwt, exports PSMDB_RBE_* into the container
String runnerImage(String distro) {
    return "docker.io/perconalab/psmdb-rbe:${distro}-8.3"
}

def withRBE(Closure body) {
    def baseFlags = (params.PSMDB_RBE_BAZEL_FLAGS ?: '').trim()
    def ciMetadata = [
        "--build_metadata=BUILD_URL=${env.BUILD_URL}",
        "--build_metadata=BUILD_NUMBER=${env.BUILD_NUMBER}",
        "--build_metadata=JOB_NAME=${env.JOB_NAME}",
        "--build_metadata=ROLE=CI"
    ].join(' ')
    def augmentedFlags = "${baseFlags} ${ciMetadata}".trim()

    // the cred helper in the container can't refresh the jwt past its exp, so
    // builds longer than the ~2h oidc ttl fail mid-build. re-mint it every 50 min
    // into a file the helper reads; build signals done via a flag file.
    // the env token stays as fallback.
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
            // seed a token so the first build action has one before the loop writes
            sh '''
                set -e
                umask 077
                printf '%s' "$PSMDB_RBE_JENKINS_TOKEN" > "${PSMDB_RBE_JENKINS_TOKEN_FILE}.tmp"
                mv "${PSMDB_RBE_JENKINS_TOKEN_FILE}.tmp" "${PSMDB_RBE_JENKINS_TOKEN_FILE}"
            '''

            parallel(
                'token-refresh': {
                    timeout(time: 24, unit: 'HOURS') {
                        // while+sleep, not waitUntil — waitUntil ignores the period
                        // here and spins, flooding the log. exit 42 = done.
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
        dockerEnvFlags = "-e PSMDB_RBE_JENKINS_TOKEN -e PSMDB_RBE_JENKINS_TOKEN_FILE -e PSMDB_RBE_OIDC_ISSUER -e PSMDB_RBE_OIDC_CONNECTOR_ID -e PSMDB_RBE_BAZEL_FLAGS"
    }
    sh """
        set -o xtrace
        ls -laR ./
        # save the props file before wiping test/
        if [ -f test/percona-server-mongodb-83.properties ]; then
            cp test/percona-server-mongodb-83.properties percona-server-mongodb-83.properties.backup
        fi
        rm -rf test/*
        mkdir -p test
        # put it back after the wipe
        if [ -f percona-server-mongodb-83.properties.backup ]; then
            mv percona-server-mongodb-83.properties.backup test/percona-server-mongodb-83.properties
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
            defaultValue: 'v8.3',
            description: 'Tag/Branch for percona-server-mongodb repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '8.3.0',
            description: 'PSMDB release value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: '100.15.0',
            description: 'https://docs.mongodb.com/database-tools/installation/',
            name: 'MONGO_TOOLS_TAG')
        string(
            defaultValue: 'psmdb-83',
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
        // rbe params, read by percona-packaging + credential_helper.py.
        // empty PSMDB_RBE_BAZEL_FLAGS turns rbe off and builds locally.
        string(
            defaultValue: 'PSMDB-RBE-OIDC',
            description: 'Jenkins credentialsId of the OIDC token credential (audience=bazel-jenkins). The credential type must be "OpenID Connect ID token" issued by the OIDC Provider plugin.',
            name: 'PSMDB_RBE_OIDC_CREDENTIALS_ID')
        string(
            defaultValue: 'https://bb.psmdb.percona.com:5556',
            description: 'Dex issuer URL the credential_helper exchanges the Jenkins OIDC token against (RFC 8693 token-exchange).',
            name: 'PSMDB_RBE_OIDC_ISSUER')
        // dex connector_id for the token-exchange grant; must match dex.yaml
        string(
            defaultValue: 'jenkins-psmdb-rbe',
            description: 'Dex connector_id form field for the token-exchange grant. Must match `connectors[].id` in dex.yaml.',
            name: 'PSMDB_RBE_OIDC_CONNECTOR_ID')
        string(
            defaultValue: '--config=psmdb_buildfarm --remote_executor=grpcs://bb.psmdb.percona.com:8981 --remote_cache=grpcs://bb.psmdb.percona.com:8981 --bes_backend=grpcs://bb.psmdb.percona.com:1985 --bes_results_url=https://bb.psmdb.percona.com:7986/bazel-invocations/ --credential_helper=bb.psmdb.percona.com=%workspace%/bazel/wrapper_hook/credential_helper.py --jobs=72',
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
                    // source tarball doesn't run bazel, so no rbe needed
                    buildStage(runnerImage('oraclelinux-8'), "--get_sources=1")
                }
                sh '''
                   # use the 83 props file; if the build wrote a different name, copy it to 83
                   if [ ! -f test/percona-server-mongodb-83.properties ]; then
                       OTHER=$(ls test/percona-server-mongodb-*.properties 2>/dev/null | head -1)
                       if [ -n "$OTHER" ]; then
                           cp "$OTHER" test/percona-server-mongodb-83.properties
                       else
                           echo "No percona-server-mongodb-*.properties found in test/"
                           ls -la test/ || true
                           exit 1
                       fi
                   fi
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-mongodb-83.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-mongodb-83.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-mongodb-83.properties', name: 'psmdb-properties'
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
                            // src_rpm is just rpmbuild, no bazel -> no rbe needed
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
                            // src_deb is just dpkg-buildpackage -S, no bazel -> no rbe needed.
                            // needs a debian runner for dch/dpkg-dev; ubuntu-jammy by default
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
                // everything below runs bazel, so wrap in withRBE and pass rbe=true
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

                // record this commit as built so get-psmdb-branches-8.3 skips it next tick.
                // kept separate from .properties, which can get corrupted.
                String S3_STASH = (params.CLOUD == 'AWS') ? 'AWS_STASH' : 'HTZ_STASH'
                String S3_ENDPOINT = (params.CLOUD == 'AWS') ? '--endpoint-url https://s3.amazonaws.com' : '--endpoint-url https://fsn1.your-objectstorage.com'
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_STASH, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    int rc = sh(returnStatus: true, script: """
                        set -euo pipefail
                        BUILT_COMMIT=\$(git ls-remote --heads ${GIT_REPO} ${GIT_BRANCH} | awk '{print \$1}')
                        if [ -z "\${BUILT_COMMIT}" ]; then
                            echo "ERROR: could not resolve commit for ${GIT_BRANCH}; last_successful NOT updated"
                            exit 1
                        fi
                        cat > branch_commit_id_83.last_successful <<EOF
BRANCH_NAME=${GIT_BRANCH}
COMMIT_ID=\${BUILT_COMMIT}
MONGO_TOOLS_TAG=${MONGO_TOOLS_TAG}
EOF
                        AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp branch_commit_id_83.last_successful s3://percona-jenkins-artifactory/percona-server-mongodb/ ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                        echo "Recorded last_successful: ${GIT_BRANCH}@\${BUILT_COMMIT}"
                    """)
                    // build's fine, only the bookkeeping failed; don't go green and hide it
                    if (rc != 0) {
                        echo "WARN: last_successful bookkeeping failed (rc=${rc}); marking build UNSTABLE"
                        currentBuild.result = 'UNSTABLE'
                    }
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
