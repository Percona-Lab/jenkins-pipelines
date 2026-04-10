/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

void cleanUpWS() {
    sh '''
        sudo rm -rf ./* || true
    '''
}

// Download the public percona-server builder script on the host, then run
// it inside an oraclelinux:9 container. Both x86_64 and aarch64 agents use
// the same image name — docker pulls the platform-matching variant.
//
// STAGE_PARAM should bundle every flag the build needs in a single
// invocation, e.g. "--get_sources=1 --build_src_rpm=1 --build_rpm=1".
//
// --install_deps=1 is invoked first so build dependencies are resolved
// inside the container, not on the Jenkins agent.
void buildStage(String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir -p test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -O ps_builder.sh \\
            || curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -o ps_builder.sh
        ls -la
        export build_dir=\$(pwd -P)
        sudo docker run --rm -u root -v \${build_dir}:\${build_dir} oraclelinux:9 sh -c "
            set -o xtrace
            cd \${build_dir}
            if [ -f ./test/percona-server-8.0.properties ]; then
                . ./test/percona-server-8.0.properties
            fi
            bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
            if [ ${BUILD_TOKUDB_TOKUBACKUP} = \"ON\" ]; then
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
            else
                bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
            fi"
    """
}

// Assemble the Docker build context for the test image.
//
// The Dockerfile + vendored scripts live in this jenkins-pipelines repo
// under ps/jenkins/docker-test-build/; we grab them via `checkout scm`
// into a subdirectory (skipDefaultCheckout keeps the workspace root clean
// for the rpm build). All percona-server-* RPMs produced by the preceding
// buildStage are copied into docker-ctx/local-rpms/ and createrepo_c is
// run against that directory (inside an oraclelinux:9 container so we do
// not need it on the Jenkins agent) to build a file:// repo the Dockerfile
// consumes as ps-local.
//
// Also seds PS_VERSION in the Dockerfile to the exact version the builder
// produced so the microdnf install can pin the EVR — same pattern as the
// release hetzner-ps8.0-docker-build job uses against the upstream
// Dockerfile.
//
// Fails loudly if the four required packages cannot be located, which
// catches cases where the builder silently skipped a stage.
void stageDockerContext() {
    dir('jenkins-pipelines-src') {
        checkout scm
    }
    sh """
        set -ex
        rm -rf docker-ctx
        mkdir -p docker-ctx/local-rpms
        cp jenkins-pipelines-src/ps/jenkins/docker-test-build/Dockerfile                    docker-ctx/
        cp jenkins-pipelines-src/ps/jenkins/docker-test-build/ps-entry.sh                   docker-ctx/
        cp jenkins-pipelines-src/ps/jenkins/docker-test-build/telemetry-agent-supervisor.sh docker-ctx/

        WORKROOT=\$(pwd -P)

        # Sanity check that the four runtime packages we actually install in
        # the Dockerfile landed in the build output. If any is missing, fail
        # loudly before we bother running createrepo_c.
        for pkg in percona-server-server percona-server-client percona-server-shared percona-icu-data-files; do
            found=\$(find "\$WORKROOT" -maxdepth 5 -type f -name "\${pkg}-[0-9]*.el9.*.rpm" \\
                        ! -name '*debuginfo*' \\
                        ! -path '*/docker-ctx/*' \\
                        ! -path '*/jenkins-pipelines-src/*' \\
                        | head -1)
            if [ -z "\$found" ]; then
                echo "ERROR: could not locate \${pkg}-*.el9.*.rpm under \$WORKROOT" >&2
                echo "---- rpm files present under workspace ----" >&2
                find "\$WORKROOT" -type f -name '*.rpm' >&2 || true
                exit 1
            fi
        done

        # Copy every percona-server-* + percona-icu-data-files RPM into
        # local-rpms/ so createrepo_c indexes the full family (server,
        # client, shared, shared-compat, devel, rocksdb, ...). Skip
        # debuginfo so the metadata and image layer stay small.
        find "\$WORKROOT" \\( -name 'percona-server-*.el9.*.rpm' -o -name 'percona-icu-data-files-*.el9.*.rpm' \\) \\
            ! -name '*debuginfo*' \\
            ! -path '*/docker-ctx/*' \\
            ! -path '*/jenkins-pipelines-src/*' \\
            -exec cp -v {} docker-ctx/local-rpms/ \\;

        echo '---- local-rpms before createrepo_c ----'
        ls -la docker-ctx/local-rpms/

        # Generate repodata inside oraclelinux:9 so we do not need
        # createrepo_c on the Jenkins agent itself. Bind-mount the
        # local-rpms dir, install createrepo_c via microdnf, run it, then
        # chown the result back to the agent user so Docker can read it.
        sudo docker run --rm \\
            -v "\$WORKROOT/docker-ctx/local-rpms:/work" \\
            oraclelinux:9 sh -c '
                set -ex
                microdnf -y install createrepo_c
                createrepo_c /work
            '
        sudo chown -R \$(id -u):\$(id -g) docker-ctx/local-rpms

        echo '---- local-rpms after createrepo_c ----'
        ls -la docker-ctx/local-rpms/ docker-ctx/local-rpms/repodata/

        # Pin the Dockerfile's PS_VERSION to the exact EVR we just built.
        PS_RELEASE=\$(echo ${BRANCH} | sed 's/release-//g')
        sed -i "s/^ENV PS_VERSION .*/ENV PS_VERSION \${PS_RELEASE}.${RPM_RELEASE}/" docker-ctx/Dockerfile
        grep '^ENV PS_VERSION ' docker-ctx/Dockerfile
    """
}

// Build + push the per-arch Docker image. `arch` is the Docker platform
// suffix ("amd64" or "arm64"), used both as the --platform value and as the
// tag suffix that the final manifest stage stitches together.
void dockerBuildAndPush(String arch) {
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            set -ex
            cd docker-ctx
            PS_RELEASE=\$(echo ${BRANCH} | sed 's/release-//g')
            IMAGE=perconalab/percona-server:\${PS_RELEASE}-${DOCKER_IMAGE_TAG}-${arch}
            echo "\${PASS}" | sudo docker login -u "\${USER}" --password-stdin
            sudo docker build --provenance=false \\
                --platform=linux/${arch} \\
                --pull \\
                -f Dockerfile \\
                -t \${IMAGE} \\
                .
            sudo docker push \${IMAGE}
            sudo docker image inspect \${IMAGE} --format 'built {{.Id}} size={{.Size}}'
        """
    }
}

pipeline {
    agent none
    parameters {
        choice(
            choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(defaultValue: 'https://github.com/percona/percona-server.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'release-8.0.43-34', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version (unused, kept for ps_builder.sh compatibility)', name: 'DEB_RELEASE')
        choice(
            choices: 'OFF\nON',
            description: 'The TokuDB storage is no longer supported since 8.0.28',
            name: 'BUILD_TOKUDB_TOKUBACKUP')
        string(defaultValue: '0', description: 'PerconaFT repository', name: 'PERCONAFT_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for PerconaFT repository', name: 'PERCONAFT_BRANCH')
        string(defaultValue: '0', description: 'TokuBackup repository', name: 'TOKUBACKUP_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for TokuBackup repository', name: 'TOKUBACKUP_BRANCH')
        string(defaultValue: 'test_build', description: 'Image tag suffix; final image = perconalab/percona-server:<ps_release>-<suffix>[-arch]', name: 'DOCKER_IMAGE_TAG')
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
        stage('Build + push per-arch') {
            parallel {
                stage('x86_64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        slackNotify("${SLACKNOTIFY}", '#00FF00', "[${JOB_NAME}]: x86_64 test build started for ${BRANCH} - [${BUILD_URL}]")
                        cleanUpWS()
                        script { buildStage('--get_sources=1 --build_src_rpm=1 --build_rpm=1') }
                        archiveArtifacts artifacts: 'source_tarball/**, srpm/**, rpm/**, test/source_tarball/**, test/srpm/**, test/rpm/**', allowEmptyArchive: true
                        script {
                            stageDockerContext()
                            dockerBuildAndPush('amd64')
                        }
                    }
                    post {
                        always {
                            sh 'sudo rm -rf ./* || true'
                            deleteDir()
                        }
                    }
                }
                stage('aarch64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        slackNotify("${SLACKNOTIFY}", '#00FF00', "[${JOB_NAME}]: aarch64 test build started for ${BRANCH} - [${BUILD_URL}]")
                        cleanUpWS()
                        script { buildStage('--get_sources=1 --build_src_rpm=1 --build_rpm=1') }
                        archiveArtifacts artifacts: 'source_tarball/**, srpm/**, rpm/**, test/source_tarball/**, test/srpm/**, test/rpm/**', allowEmptyArchive: true
                        script {
                            stageDockerContext()
                            dockerBuildAndPush('arm64')
                        }
                    }
                    post {
                        always {
                            sh 'sudo rm -rf ./* || true'
                            deleteDir()
                        }
                    }
                }
            }
        }
        stage('Create multi-arch manifest') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                        sh """
                            set -ex
                            PS_RELEASE=\$(echo ${BRANCH} | sed 's/release-//g')
                            BASE=perconalab/percona-server:\${PS_RELEASE}-${DOCKER_IMAGE_TAG}
                            echo "\${PASS}" | sudo docker login -u "\${USER}" --password-stdin
                            sudo docker manifest rm \${BASE} 2>/dev/null || true
                            sudo docker manifest create --amend \${BASE} \\
                                \${BASE}-amd64 \\
                                \${BASE}-arm64
                            sudo docker manifest annotate \${BASE} \${BASE}-amd64 --os linux --arch amd64
                            sudo docker manifest annotate \${BASE} \${BASE}-arm64 --os linux --arch arm64 --variant v8
                            sudo docker manifest inspect \${BASE}
                            sudo docker manifest push \${BASE}
                        """
                    }
                }
            }
            post {
                always {
                    sh 'sudo rm -rf ./* || true'
                    deleteDir()
                }
            }
        }
    }
    post {
        success {
            script {
                def PS_RELEASE = BRANCH.replaceAll('release-', '')
                slackNotify("${SLACKNOTIFY}", '#00FF00', "[${JOB_NAME}]: test build finished for ${BRANCH} -> perconalab/percona-server:${PS_RELEASE}-${DOCKER_IMAGE_TAG} - [${BUILD_URL}]")
            }
        }
        failure {
            slackNotify("${SLACKNOTIFY}", '#FF0000', "[${JOB_NAME}]: test build FAILED for ${BRANCH} - [${BUILD_URL}]")
        }
    }
}
