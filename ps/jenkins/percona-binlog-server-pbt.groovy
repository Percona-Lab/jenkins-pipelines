// percona-binlog-server property-based tests.
//
// Builds percona-binlog-server with -DWITH_PROPERTY_TESTS=ON and runs
// extra/qa/run_property_tests.py (see extra/qa/README.md in
// percona-binlog-server). The steps are in package-testing
// binlog-server-pbt/pbt.sh, which runs inside the image built from
// binlog-server-pbt/Dockerfile (Ubuntu 24.04, g++-14).
//
// The Report stage turns the run into report/report.html. With AI_REPORT,
// Claude Code (headless, on Amazon Bedrock) first writes up the issues from
// the results and the source. The scripts are in package-testing
// binlog-server-pbt/report and follow Percona-Lab/ci-ai-kit
// (examples/jobs-report/claude-code.Jenkinsfile):
// - the job runs on an EC2 worker ('docker-32gb'). EC2 workers on ps80 carry
//   the jenkins-ps80-worker instance role, which may call Claude on Bedrock;
//   containers reach it through the instance metadata service. Hetzner
//   workers have no AWS role, so never move this job to a Hetzner label.
// - no credential is bound while the agent runs (AWS_STASH is bound only
//   around the S3 cache transfers). The agent's container mounts the results,
//   the source and the facts of the run read-only and may write only
//   ai/analysis, ai/work and ai/log. A separate step audits its transcript
//   before the report uses its analysis, and the renderer checks that every
//   file the analysis names exists.

void runInPbtImage(String command) {
    sh """
        docker run --rm --init --name "\${PBT_NAME}" \\
            -u "\$(id -u):\$(id -g)" -e HOME=/tmp/home \\
            --cap-add SYS_PTRACE --shm-size 2g \\
            -e WORKDIR=/work -e BUILD_TYPE -e PS_VERSION -e CASES_SCALE -e KNOWN_AS \\
            -e EXTRA_RUN_ARGS \\
            -v "\${WORKSPACE}/ws:/work" \\
            -v "\${WORKSPACE}/package-testing/binlog-server-pbt:/pbt:ro" \\
            -w /work "\${PBT_IMAGE}" /pbt/pbt.sh ${command}
    """
}

// One step of the report, run in the image with /ai as working directory:
// input/ (facts from prepare.py), results/, source/, analysis/, work/, log/,
// report/, and the scripts in /tools. The agent gets input/ read-only and
// neither log/ nor report/; its transcript is its stdout, written to
// ai/log/agent-transcript.jsonl on the host. Returns the exit code.
int runReportStep(String script, String args, boolean agent = false) {
    List<String> options = [
        '-u "$(id -u):$(id -g)"', '-e HOME=/tmp/home',
        '-v "${WORKSPACE}/results:/ai/results:ro"',
        '-v "${WORKSPACE}/ws/percona-binlog-server:/ai/source:ro"',
        '-v "${WORKSPACE}/ai/analysis:/ai/analysis"',
        '-v "${WORKSPACE}/ai/work:/ai/work"',
        '-v "${WORKSPACE}/package-testing/binlog-server-pbt/report:/tools:ro"',
    ]
    String limit = ''
    String output = ''
    if (agent) {
        // Host network: Claude Code takes the worker's instance role from the
        // metadata service, which answers containers on the default bridge
        // network only when the instance allows two hops. On docker-32gb the
        // bridged container got no credentials (build 9: "Could not load AWS
        // credentials"); run_agent.py checks and names the reason first.
        options += ['--network host', '-v "${WORKSPACE}/ai/input:/ai/input:ro"',
                    '-e AI_MODEL', '-e AI_EFFORT', '-e AI_MAX_TURNS', '-e CLAUDE_CODE_VERSION']
        // exit code 124 when the time limit fires
        limit = 'timeout --kill-after=60 "${AI_TIMEOUT_MINUTES}m"'
        output = '> "${WORKSPACE}/ai/log/agent-transcript.jsonl"'
    } else {
        options += ['-v "${WORKSPACE}/ai/input:/ai/input"', '-v "${WORKSPACE}/ai/log:/ai/log:ro"',
                    '-v "${WORKSPACE}/report:/ai/report"',
                    '-e BUILD_URL', '-e BUILD_NUMBER', '-e BUILD_DISPLAY_NAME']
    }
    return sh(returnStatus: true, script: 'docker run --rm --init --name "${PBT_NAME}" ' + options.join(' ') +
        " -w /ai \"\${PBT_IMAGE}\" ${limit} python3 -I /tools/${script} ${args} ${output}")
}

void copyResults() {
    sh '''
        rm -rf results
        if [ -d ws/testruns/jenkins ]; then
            cp -rL ws/testruns/jenkins results
        fi
    '''
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
            defaultValue: '8.4.10-10',
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
        booleanParam(
            name: 'AI_REPORT',
            defaultValue: true,
            description: 'Let Claude Code (on Amazon Bedrock) write up the issues in report/report.html. Without it the report shows the known_bugs.md descriptions.'
        )
        choice(
            name: 'AI_MODEL',
            choices: ['claude-opus-5-5', 'claude-sonnet-5', 'claude-fable-5-1', 'claude-opus-5'],
            description: 'Model for the report analysis'
        )
        choice(
            name: 'AI_EFFORT',
            choices: ['medium', 'low', 'high', 'xhigh', 'max'],
            description: 'Thinking depth and token spend of the report analysis'
        )
        choice(
            name: 'AI_SCOPE',
            choices: ['all', 'new'],
            description: 'all: write up every failing issue; new: only new issues, regressions, flaky failures and possibly fixed issues (cheaper)'
        )
        string(
            name: 'AI_MAX_TURNS',
            defaultValue: '300',
            description: 'Turn limit of the Claude Code session'
        )
        string(
            name: 'AI_TIMEOUT_MINUTES',
            defaultValue: '120',
            description: 'Time limit of the Claude Code session; the issues written up by then are still used'
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
        timeout(time: 8, unit: 'HOURS')
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
        PS_VERSION = "${params.PS_VERSION ?: '8.4.10-10'}"
        CASES_SCALE = "${params.CASES_SCALE ?: '1'}"
        KNOWN_AS = "${params.KNOWN_AS ?: 'failure'}"
        EXTRA_RUN_ARGS = "${params.EXTRA_RUN_ARGS ?: ''}"
        AI_MODEL = "${params.AI_MODEL ?: 'claude-opus-5-5'}"
        AI_EFFORT = "${params.AI_EFFORT ?: 'medium'}"
        AI_SCOPE = "${params.AI_SCOPE ?: 'all'}"
        AI_MAX_TURNS = "${params.AI_MAX_TURNS ?: '300'}"
        AI_TIMEOUT_MINUTES = "${params.AI_TIMEOUT_MINUTES ?: '120'}"
        // pinned, like the ci-ai-kit examples
        CLAUDE_CODE_VERSION = '2.1.282'
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

                    # gcc-14 AddressSanitizer cannot map its shadow memory with
                    # the 32-bit mmap randomisation of recent kernels
                    if [ "$(sysctl -n vm.mmap_rnd_bits)" -gt 28 ]; then
                        sudo sysctl -w vm.mmap_rnd_bits=28 || echo "WARNING: cannot lower vm.mmap_rnd_bits, ASan builds may crash at start"
                    fi
                '''
                script {
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
                copyResults()
            }
        }

        stage('Report') {
            when {
                expression { fileExists('results/summary.json') }
            }
            steps {
                script {
                    sh 'rm -rf ai report && mkdir -p ai/input ai/analysis ai/work ai/log report'
                    if (runReportStep('prepare.py', "--results results --source source --out input/base.json --scope ${env.AI_SCOPE}") != 0) {
                        error('prepare.py failed')
                    }
                    String aiMode = 'off'
                    if (params.AI_REPORT) {
                        int agent = runReportStep('run_agent.py', '', true)
                        removePbtContainer()
                        aiMode = agent == 0 ? 'claude' : 'failed'
                        if (agent != 0) {
                            echo "Claude Code session did not finish (exit code ${agent}${agent == 124 ? ', time limit' : ''}); the report uses the issues it wrote up"
                        }
                        // a failed audit keeps the agent's analysis out of the report
                        if (runReportStep('audit_agent.py', 'log/agent-transcript.jsonl analysis') != 0) {
                            aiMode = 'rejected'
                        }
                    }
                    if (runReportStep('render_report.py', '--base input/base.json --source source --out report --analysis analysis ' +
                            "--agent-result work/agent-result.json --ai ${aiMode}") != 0) {
                        error('render_report.py failed')
                    }
                    echo "Report: ${env.BUILD_URL}artifact/report/report.html"
                    if (aiMode == 'failed' || aiMode == 'rejected') {
                        // the report exists either way: flag the stage, keep the build result
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            error(aiMode == 'rejected' ? 'Claude Code session failed the audit; its analysis is not in the report'
                                                       : 'Claude Code session did not finish; the report has partial analysis')
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            removePbtContainer()
            script {
                if (!fileExists('results/summary.json')) {
                    copyResults()
                }
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
                if (fileExists('report/report.html')) {
                    currentBuild.description = "${currentBuild.description ?: ''}<br><a href=\"${env.BUILD_URL}artifact/report/report.html\">Report</a>"
                }
            }
            junit allowEmptyResults: true, keepLongStdio: true, skipPublishingChecks: true, testResults: 'results/junit.xml'
            archiveArtifacts allowEmptyArchive: true,
                artifacts: 'results/**, report/**, ai/input/**, ai/analysis/**, ai/log/**, ai/work/agent-result.json'
            sh 'docker rmi -f "${PBT_IMAGE}" >/dev/null 2>&1 || true'
        }
        cleanup {
            deleteDir()
        }
    }
}
