// percona-binlog-server property-based tests.
//
// Builds percona-binlog-server with -DWITH_PROPERTY_TESTS=ON and runs
// extra/qa/run_property_tests.py (see extra/qa/README.md in
// percona-binlog-server). The steps are in package-testing
// binlog-server-pbt/pbt.sh, which runs inside the image built from
// binlog-server-pbt/Dockerfile (Ubuntu 24.04, g++-14).
//
// Kept ready for a later Claude Code analysis stage (Percona-Lab/ci-ai-kit,
// examples/jobs-report/claude-code.Jenkinsfile):
// - the job runs on an EC2 worker ('docker-32gb'). EC2 workers on ps80 carry
//   the jenkins-ps80-worker instance role, which may call Claude on Bedrock;
//   containers reach it through the instance metadata service. Hetzner
//   workers have no AWS role, so never move this job to a Hetzner label.
// - every step runs in a container, the AWS key credential (AWS_STASH) is
//   bound only around the S3 cache transfers, and the finished report is
//   copied to results/, which an agent stage can mount read-only.

void runInPbtImage(String command) {
    sh """
        docker run --rm --init --name "\${PBT_NAME}" \\
            -u "\$(id -u):\$(id -g)" -e HOME=/tmp/home \\
            --cap-add SYS_PTRACE --shm-size 2g \\
            -e WORKDIR=/work -e BUILD_TYPE -e PS_VERSION -e CASES_SCALE -e KNOWN_AS \\
            -e EXTRA_RUN_ARGS -e IMAGE_ID \\
            -v "\${WORKSPACE}/ws:/work" \\
            -v "\${WORKSPACE}/package-testing/binlog-server-pbt:/pbt:ro" \\
            -w /work "\${PBT_IMAGE}" /pbt/pbt.sh ${command}
    """
}

void removePbtContainer() {
    // an aborted build kills the docker client, not the container
    sh 'docker rm -f "${PBT_NAME}" >/dev/null 2>&1 || true'
}

pipeline {
    agent {
        label 'docker-32gb'
    }

    parameters {
        string(
            name: 'PBS_REPO',
            defaultValue: 'https://github.com/tplavcic/percona-binlog-server.git',
            description: 'percona-binlog-server repository'
        )
        string(
            name: 'PBS_BRANCH',
            defaultValue: 'pbt-init-tomislav',
            description: 'percona-binlog-server branch (must contain extra/qa)'
        )
        choice(
            name: 'BUILD_TYPE',
            choices: ['asan_gcc14', 'asan_gcc14-ubsan', 'release_gcc14', 'debug_gcc14'],
            description: 'Build variant: CMake preset, -ubsan adds UndefinedBehaviorSanitizer (extra/qa/README.md, "Build variants")'
        )
        string(
            name: 'PS_VERSION',
            defaultValue: '8.4.5-5',
            description: 'Percona Server version for the private mysqld of the MySQL tests (minimal glibc2.35 tarball from downloads.percona.com)'
        )
        string(
            name: 'CASES_SCALE',
            defaultValue: '1',
            description: 'Multiplies every property\'s case count: 0.1 = smoke run, 10 = nightly'
        )
        string(
            name: 'EXTRA_RUN_ARGS',
            defaultValue: '',
            description: 'More run_property_tests.py arguments, split on spaces, no quoting: e.g. --filter gtid_set.*Decode --seed 42'
        )
        choice(
            name: 'KNOWN_AS',
            choices: ['failure', 'skipped'],
            description: 'How failures listed in extra/qa/known_bugs.md appear in the test results'
        )
        booleanParam(
            name: 'REBUILD_DEPS',
            defaultValue: false,
            description: 'Rebuild Boost and the AWS SDK and replace the S3 cache'
        )
        string(
            name: 'TESTING_REPO',
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: 'package-testing repository'
        )
        string(
            name: 'TESTING_BRANCH',
            defaultValue: 'pbt-jenkins',
            description: 'package-testing branch'
        )
    }

    options {
        disableConcurrentBuilds()
        timeout(time: 6, unit: 'HOURS')
        timestamps()
    }

    environment {
        // BUILD_TAG is "jenkins-<job name>-<build>"; the job name may contain
        // spaces and capitals, which docker refuses in tags and names
        PBT_NAME = "pbs-pbt-${env.BUILD_TAG.toLowerCase().replaceAll('[^a-z0-9_.-]+', '-')}"
        PBT_IMAGE = "pbs-pbt:${env.BUILD_TAG.toLowerCase().replaceAll('[^a-z0-9_.-]+', '-')}"
        // Boost and the AWS SDK are built from source per preset (30-60 min)
        // and cached here, keyed by preset, versions, CMake presets and image
        DEPS_CACHE = 's3://percona-jenkins-artifactory/percona-binlog-server-pbt/deps'

        // copied from the parameters so the first build, which registers
        // them, still sees the defaults
        BUILD_TYPE = "${params.BUILD_TYPE ?: 'asan_gcc14'}"
        PS_VERSION = "${params.PS_VERSION ?: '8.4.5-5'}"
        CASES_SCALE = "${params.CASES_SCALE ?: '1'}"
        KNOWN_AS = "${params.KNOWN_AS ?: 'failure'}"
        EXTRA_RUN_ARGS = "${params.EXTRA_RUN_ARGS ?: ''}"
    }

    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${env.BUILD_TYPE}-${params.PBS_BRANCH}"
                }
                sh '''
                    git clone --depth 1 -b "${TESTING_BRANCH}" "${TESTING_REPO}" package-testing
                    mkdir -p ws
                    git clone --depth 1 -b "${PBS_BRANCH}" "${PBS_REPO}" ws/percona-binlog-server
                    git -C ws/percona-binlog-server log -1 --format='percona-binlog-server %H %s'

                    docker build --pull -t "${PBT_IMAGE}" package-testing/binlog-server-pbt
                    docker image inspect -f '{{.Id}}' "${PBT_IMAGE}" | cut -d: -f2 > image-id

                    # gcc-14 AddressSanitizer cannot map its shadow memory with
                    # the 32-bit mmap randomisation of recent kernels
                    if [ "$(sysctl -n vm.mmap_rnd_bits)" -gt 28 ]; then
                        sudo sysctl -w vm.mmap_rnd_bits=28 || echo "WARNING: cannot lower vm.mmap_rnd_bits, ASan builds may crash at start"
                    fi
                '''
                script {
                    env.IMAGE_ID = readFile('image-id').trim()
                    env.DEPS_KEY = sh(
                        script: 'WORKDIR="${WORKSPACE}/ws" package-testing/binlog-server-pbt/pbt.sh deps-key',
                        returnStdout: true
                    ).trim()
                    currentBuild.description = "${params.PBS_REPO.replaceAll('^https://github.com/', '').replaceAll('\\.git$', '')} " +
                        "${sh(script: 'git -C ws/percona-binlog-server rev-parse --short HEAD', returnStdout: true).trim()}, PS ${env.PS_VERSION}"
                }
                echo "Boost / AWS SDK cache key: ${env.DEPS_KEY}"
            }
        }

        stage('Boost and AWS SDK') {
            steps {
                script {
                    String cacheObject = "${env.DEPS_CACHE}/${env.DEPS_KEY}.tar.gz"
                    boolean restored = false
                    if (!params.REBUILD_DEPS) {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            restored = sh(script: """
                                aws s3 cp --no-progress "${cacheObject}" deps.tar.gz || exit 1
                                # a partial extract would look like a finished install
                                tar -xzf deps.tar.gz -C ws || { rm -rf ws/boost-install-* ws/aws-sdk-cpp-install-*; exit 1; }
                            """, returnStatus: true) == 0
                        }
                        sh 'rm -f deps.tar.gz'
                    }
                    if (restored) {
                        echo "Restored Boost and AWS SDK from ${cacheObject}"
                    } else {
                        echo "No usable cache at ${cacheObject}: building Boost and AWS SDK"
                        runInPbtImage('deps')
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            // a failed upload only costs the next build a rebuild
                            sh """
                                PRESET="\$(echo "\${DEPS_KEY}" | cut -d/ -f1)"
                                tar -czf deps.tar.gz -C ws "boost-install-\${PRESET}" "aws-sdk-cpp-install-\${PRESET}"
                                aws s3 cp --no-progress deps.tar.gz "${cacheObject}" || echo "WARNING: cache upload failed"
                                rm -f deps.tar.gz
                            """
                        }
                    }
                }
            }
        }

        stage('Test environment') {
            steps {
                // libhegel, hegel-cpp, SeaweedFS and the Percona Server tarball
                runInPbtImage('setup')
            }
        }

        stage('Build') {
            steps {
                runInPbtImage('build')
            }
        }

        stage('Property tests') {
            steps {
                // exit code 1 = new issues, regressions or infrastructure
                // errors (--fail-on new); known bugs alone do not fail it
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    runInPbtImage('run')
                }
            }
        }

        // A Claude Code analysis of results/ goes here later, following
        // ci-ai-kit examples/jobs-report/claude-code.Jenkinsfile: a pinned
        // ci-ai-kit image, results/ mounted read-only, writes only to work/
        // and report/, then the audit and verify steps.
    }

    post {
        always {
            removePbtContainer()
            sh '''
                rm -rf results
                if [ -d ws/testruns/jenkins ]; then
                    cp -rL ws/testruns/jenkins results
                fi
            '''
            script {
                if (fileExists('results/summary.json')) {
                    String summary = sh(script: '''
                        docker run --rm -u "$(id -u):$(id -g)" -e WORKDIR=/work \
                            -v "${WORKSPACE}/ws:/work:ro" \
                            -v "${WORKSPACE}/package-testing/binlog-server-pbt:/pbt:ro" \
                            "${PBT_IMAGE}" /pbt/pbt.sh describe
                    ''', returnStdout: true).trim()
                    currentBuild.description = "${currentBuild.description ?: ''}<br>${summary}"
                    sh 'cat results/summary.md'
                }
            }
            junit allowEmptyResults: true, keepLongStdio: true, skipPublishingChecks: true, testResults: 'results/junit.xml'
            archiveArtifacts allowEmptyArchive: true, artifacts: 'results/**'
            sh 'docker rmi -f "${PBT_IMAGE}" >/dev/null 2>&1 || true'
        }
        cleanup {
            deleteDir()
        }
    }
}
