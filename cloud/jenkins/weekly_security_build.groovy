import groovy.transform.Field
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

@Field String gitNamespace = 'percona'
@Field String slackChannel = '#cloud-dev-ci'
@Field Map libraries = [:]

@Field List repositories = [
    [
        operator        : 'ps-operator',
        name            : 'percona-server-mysql-operator',
        sourceBranch    : 'main',
        imageName       : 'percona-server-mysql-operator',
        imageRepo       : 'docker.io/perconalab',
        releaseImageRepo: 'docker.io/percona',
        testJob         : 'pso-gke-1',
        pillarVersion    : '84',
        goVersionFiles   : [] // List of files containing Go version references
    ]
]

void getLibraries() {
    libraries = load('cloud/common/libraries.groovy').loadLibraries()
}

List slackMessageAttachments(String text, String color, List<Map> buttons) {
    def blocks = [
        [
            type: 'section',
            text: [
                type: 'mrkdwn',
                text: text
            ]
        ]
    ]

    if (buttons) {
        blocks.add([
            type: 'actions',
            elements: buttons
        ])
    }

    return [[
        color: color,
        fallback: text,
        blocks: blocks
    ]]
}

String findDockerfile() {
    if (fileExists('Dockerfile')) {
        return 'Dockerfile'
    }

    if (fileExists('build/Dockerfile')) {
        return 'build/Dockerfile'
    }

    def output = sh(
        script: 'find build -type f -name Dockerfile -print 2>/dev/null | sort || true',
        returnStdout: true
    ).trim()

    def dockerfiles = output ? output.split('\n') as List : []

    if (dockerfiles.size() > 1) {
        error("Multiple Dockerfiles found under build/: ${dockerfiles.join(', ')}")
    }

    if (!dockerfiles) {
        error('Dockerfile was not found in the repository root or under build/')
    }

    return dockerfiles.first()
}

int nextSecurityBuildNumber(String version) {
    def latest = withEnv(["VERSION=${version}"]) {
        sh(
            script: '''
                git ls-remote --tags origin "refs/tags/security/${VERSION}-*" 2>/dev/null |
                sed 's|.*-||; s|\\^{}||' |
                grep -E '^[0-9]+$' |
                sort -n |
                tail -1
            ''',
            returnStdout: true
        ).trim()
    }

    return latest ? latest.toInteger() + 1 : 1
}

Map buildContext(Map repository) {
    def versionTag = sh(
        script: "git tag --list --sort=-v:refname | grep -E '^v[0-9]+[.][0-9]+[.][0-9]+\$' | head -1",
        returnStdout: true
    ).trim()

    if (!versionTag) {
        error("${repository.name} has no version tag matching vMAJOR.MINOR.PATCH")
    }

    def version = versionTag.substring(1)
    def buildNumber = nextSecurityBuildNumber(version)

    def securityBaseBranch = "security/${version}"
    def securityBranch = "${securityBaseBranch}-${buildNumber}"

    def buildImageTag = "${version}-${buildNumber}"
    def floatingImageTag = "${version}-latest"

    return [
        BUILD_URL             : env.BUILD_URL,
        REPO_PATH             : "${gitNamespace}/${repository.name}",
        GIT_NAMESPACE         : gitNamespace,
        OPERATOR              : repository.operator,
        SOURCE_BRANCH         : repository.sourceBranch,

        VERSION               : version,
        VERSION_TAG           : versionTag,
        SECURITY_BUILD_NUMBER : buildNumber,

        // Example: security/1.2.3
        SECURITY_BASE_BRANCH  : securityBaseBranch,
        FLOATING_TAG          : securityBaseBranch,

        // Example: security/1.2.3-1
        SECURITY_BRANCH       : securityBranch,
        TAG                   : securityBranch,

        BASE_RELEASED_IMAGE   : "${repository.releaseImageRepo}/${repository.imageName}:${version}",

        BUILD_IMAGE_REPOSITORY: "${repository.imageRepo}/${repository.imageName}",
        BUILD_IMAGE_TAG       : buildImageTag,
        BUILD_IMAGE           : "${repository.imageRepo}/${repository.imageName}:${buildImageTag}",
        FLOATING_BUILD_IMAGE  : "${repository.imageRepo}/${repository.imageName}:${floatingImageTag}",

        RELEASE_IMAGE         : "${repository.releaseImageRepo}/${repository.imageName}:${buildImageTag}",
        FLOATING_RELEASE_IMAGE: "${repository.releaseImageRepo}/${repository.imageName}:${floatingImageTag}"
    ]
}

boolean ensureSecurityBaseBranch(Map context) {
    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            def tagExists = libraries.tools.gitTagExists(context.SECURITY_BASE_BRANCH)
            def branchExists = libraries.tools.gitBranchExists(context.SECURITY_BASE_BRANCH)

            if (tagExists && branchExists) {
                echo "Using existing security tag and tag: ${context.SECURITY_BASE_BRANCH}"
                libraries.tools.gitFetchTag(context.SECURITY_BASE_BRANCH)
                libraries.tools.gitCheckoutTag(context.SECURITY_BASE_BRANCH)
                return true
            }

            echo "Creating security branch ${context.SECURITY_BASE_BRANCH} from ${context.VERSION_TAG}"

            libraries.tools.gitFetchTag(context.VERSION_TAG)
            libraries.tools.gitCheckoutTag(context.VERSION_TAG)

            libraries.tools.gitDeleteRemoteBranch(context.SECURITY_BASE_BRANCH)
            libraries.tools.gitCreateBranch(context.SECURITY_BASE_BRANCH)
            libraries.tools.gitPushBranch(context.SECURITY_BASE_BRANCH)

            return false
        }
    }
}

String pullReleasedImage(Map context) {
    def image = context.SECURITY_BASE_EXISTS ?
        context.FLOATING_RELEASE_IMAGE :
        context.BASE_RELEASED_IMAGE

    withEnv(["RELEASED_IMAGE=${image}"]) {
        sh '''
            set -eu

            echo "Pulling RELEASED image ${RELEASED_IMAGE}"
            docker pull "${RELEASED_IMAGE}"
        '''
    }

    return image
}

void goSecurityFixScript(Map context) {
    def goVersionFileArguments = context.GO_VERSION_FILES.collect {
        "--go-version-file=${it}"
    }.join(' ')

    withEnv([
        "DOCKERFILE=${context.DOCKERFILE}",
        "GO_VERSION_FILE_ARGUMENTS=${goVersionFileArguments}",
        "REPO_PATH=${context.REPO_PATH}",
        "TAG=${context.TAG}",
        "SCRIPT=jenkins-fix_go_vulnerabilities.py"
    ]) {
        libraries.credentials.withGitHubCredentials {
            try {
                sh '''
                    cp -f ../cloud/scripts/security_build/fix_go_vulnerabilities.py "${SCRIPT}"

                    docker run --rm \
                      -v "${PWD}:${PWD}" \
                      -e HOME=/tmp \
                      -e GOCACHE=/tmp/go-cache \
                      -e GOMODCACHE=/tmp/go-mod-cache \
                      -e HOST_UID="$(id -u)" \
                      -e HOST_GID="$(id -g)" \
                      -e GITHUB_TOKEN \
                      -e DOCKERFILE \
                      -e "GO_VERSION_FILE_ARGUMENTS=${GO_VERSION_FILE_ARGUMENTS:-}" \
                      -e SCRIPT \
                      -e TAG \
                      -w "${PWD}" \
                      golang:1.27-alpine \
                      sh -ceu '
                        apk add --no-cache bash curl git jq make patch python3 su-exec yq

                        exec su-exec "${HOST_UID}:${HOST_GID}" \
                          python3 -u "${SCRIPT}" \
                            --trivy-report trivy.json \
                            --go-mod go.mod \
                            --dockerfile "${DOCKERFILE}" \
                            ${GO_VERSION_FILE_ARGUMENTS:-} \
                            --tag "${TAG}"
                      '
                '''
            } finally {
                sh 'rm -f "${SCRIPT}"'
            }
        }
    }
}

void updateOperatorImageReferences(Map context) {
    withEnv([
        "OPERATOR_RELEASE_IMAGE=${context.RELEASE_IMAGE}",
        "SCRIPT=jenkins-update_operator_images.py",
        "TAG=${context.TAG}"
    ]) {
        try {
            sh '''
                set -eu

                cp -f ../cloud/scripts/security_build/update_operator_images.py "${SCRIPT}"

                docker run --rm \
                  -v "${PWD}:${PWD}" \
                  -e HOST_UID="$(id -u)" \
                  -e HOST_GID="$(id -g)" \
                  -e OPERATOR_RELEASE_IMAGE \
                  -e SCRIPT \
                  -w "${PWD}" \
                  golang:1.27-alpine \
                  sh -ceu '
                    apk add --no-cache python3 su-exec

                    exec su-exec "${HOST_UID}:${HOST_GID}" \
                      python3 -u "${SCRIPT}" \
                        --image "${OPERATOR_RELEASE_IMAGE}" \
                        --deploy-dir deploy \
                        --release-versions e2e-tests/release_versions
                  '

                git add -- deploy e2e-tests/release_versions

                if ! git diff --cached --quiet; then
                    git commit -m "Update operator image references for ${TAG}"
                fi
            '''
        } finally {
            sh 'rm -f "${SCRIPT}"'
        }
    }
}

String fixVulnerabilities(Map context) {
    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            libraries.tools.gitCreateBranch(context.SECURITY_BRANCH)
        }

        def baseCommit = libraries.tools.gitHead()
        goSecurityFixScript(context)

        if (libraries.tools.gitHead() == baseCommit) {
            return ''
        }

        updateOperatorImageReferences(context)

        def summary = sh(
            script: "git log --reverse --format='• %s%n↳ %b%n' '${baseCommit}..HEAD'",
            returnStdout: true
        ).trim()

        libraries.credentials.withGitHubCredentials {
            libraries.tools.gitPushBranch(context.SECURITY_BRANCH)
        }

        return summary
    }
}

boolean addVulnerabilityBadge() {
    def counts = sh(
        script: '''
            critical=$(grep -Eo '"Severity"[[:space:]]*:[[:space:]]*"CRITICAL"' trivy.json | wc -l)
            high=$(grep -Eo '"Severity"[[:space:]]*:[[:space:]]*"HIGH"' trivy.json | wc -l)
            total=$((critical + high))

            printf '%s %s %s' "${critical}" "${high}" "${total}"
        ''',
        returnStdout: true
    ).trim().tokenize()

    def critical = counts[0]
    def high = counts[1]
    def total = counts[2]

    def badgeStyle =
        'padding: 4px 12px; border-radius: 8px; font-weight: 600;'

    if (total != '0') {
        addBadge(
            id: 'trivy-critical',
            text: "${critical} CRITICAL",
            style: "${badgeStyle} color: #ff4d4f; background-color: #4a2328;"
        )

        addBadge(
            id: 'trivy-high',
            text: "${high} HIGH",
            style: "${badgeStyle} color: #f0a500; background-color: #493817;"
        )

        return true
    }

    addBadge(
        id: 'trivy-clean',
        text: 'NO VULNERABILITIES',
        style: "${badgeStyle} color: #2ecc71; background-color: #183d2b;"
    )

    return false
}

void buildImage(Map context) {
    echo "Building BUILD image: ${context.BUILD_IMAGE}"

    libraries.credentials.withDockerCredentials {
        libraries.tools.dockerBuildAndPush(
            operator: context.OPERATOR,
            operatorImage: context.BUILD_IMAGE_REPOSITORY,
            branch: context.BUILD_IMAGE_TAG,
            sourceDir: '.'
        )
    }
}

String createPullRequest(Map context) {
    def prUrl

    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            prUrl = libraries.tools.githubCreatePullRequest(
                context.REPO_PATH,
                context.GIT_NAMESPACE,
                context.SECURITY_BRANCH,
                context.SECURITY_BASE_BRANCH,
                "Security build ${context.TAG}",
                "Automated security update for ${context.TAG}."
            )
        }
    }

    return prUrl
}

void waitForMerge(
    Map context,
    String vulnerabilitySummary,
    String prUrl
) {
    def message = [
        ':warning: *Security build awaiting PR merge*',
        '',
        "*Repository*: `${context.REPO_PATH}`",
        "*Branch*: `${context.SECURITY_BRANCH}`",
        "*Target branch*: `${context.SECURITY_BASE_BRANCH}`",
        '',
        "*RELEASED image*: `${context.RELEASED_IMAGE}`",
        "*BUILD image*: `${context.BUILD_IMAGE}`",
        "*RELEASE image*: `${context.RELEASE_IMAGE}`",
        "*Latest RELEASE image*: `${context.FLOATING_RELEASE_IMAGE}`",
        '',
        '*Vulnerabilities and selected fixes:*',
        vulnerabilitySummary
    ].join('\n')

    slackSend(
        botUser: true,
        channel: slackChannel,
        failOnError: true,
        attachments: slackMessageAttachments(
            message,
            '#FFA500',
            [
                [
                    type: 'button',
                    text: [
                        type: 'plain_text',
                        text: 'Review security pull request',
                        emoji: true
                    ],
                    url: prUrl,
                    style: 'primary',
                    action_id: 'review_security_pull_request'
                ],
                [
                    type: 'button',
                    text: [
                        type: 'plain_text',
                        text: 'Open Jenkins build',
                        emoji: true
                    ],
                    url: context.BUILD_URL,
                    action_id: 'open_jenkins_build'
                ]
            ]
        )
    )

    addSummary(
        id: 'security-build-approval',
        text: """
            <b>Security build awaiting PR merge</b><br>
            <b>Repository:</b> ${context.REPO_PATH}<br>
            <b>Branch:</b> ${context.SECURITY_BRANCH}<br>
            <b>Target branch:</b> ${context.SECURITY_BASE_BRANCH}<br><br>

            <b>RELEASED image:</b> ${context.RELEASED_IMAGE}<br>
            <b>BUILD image:</b> ${context.BUILD_IMAGE}<br>
            <b>RELEASE image:</b> ${context.RELEASE_IMAGE}<br>
            <b>Latest RELEASE image:</b> ${context.FLOATING_RELEASE_IMAGE}<br><br>

            <b>Vulnerabilities and selected fixes:</b><br>
            <pre>${vulnerabilitySummary}</pre>

            <a href="${prUrl}" target="_blank"
               style="display: inline-block;
                      padding: 8px 14px;
                      color: #fff;
                      background-color: #238636;
                      border-radius: 6px;
                      text-decoration: none;
                      font-weight: 600;">
                Review security pull request
            </a>
        """.stripIndent().trim()
    )

    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            waitUntil(
                initialRecurrencePeriod: 30000,
                quiet: true
            ) {
                def prStatus = libraries.tools.githubPullRequestStatus(
                    context.REPO_PATH,
                    prUrl
                )

                if (prStatus == 'merged') {
                    echo 'Pull request is merged; continuing the security build'
                    return true
                }

                if (prStatus == 'closed') {
                    error("Pull request was closed without being merged: ${prUrl}")
                }

                if (prStatus == 'unknown') {
                    echo 'Unable to determine pull request status; retrying'
                    return false
                }

                echo 'Pull request is waiting to be merged'
                return false
            }
        }
    }
}

void checkoutMergedCommit(Map context) {
    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            libraries.tools.gitFetchBranch(context.SECURITY_BASE_BRANCH)
            context.RELEASE_COMMIT = libraries.tools.gitHead()
        }
    }

    echo "Merged commit selected for release: ${context.RELEASE_COMMIT}"
}

void pushReleaseImage(Map context) {
    echo "Pushing RELEASE image: ${context.RELEASE_IMAGE}"
    libraries.credentials.withDockerCredentials {
        libraries.tools.dockerCopyImage(
            context.BUILD_IMAGE,
            [
                context.RELEASE_IMAGE
            ]
        )
    }
}

void publishRelease(Map context) {
    def currentCommit = libraries.tools.gitHead()
    if (currentCommit != context.RELEASE_COMMIT) {
        error(
            "Workspace changed after rebuilding the merged commit: " +
            "expected ${context.RELEASE_COMMIT}, found ${currentCommit}"
        )
    }

    echo "Updating latest RELEASE image: ${context.FLOATING_RELEASE_IMAGE}"
    libraries.credentials.withDockerCredentials {
        libraries.tools.dockerCopyImage(
            context.RELEASE_IMAGE,
            [
                context.FLOATING_RELEASE_IMAGE
            ]
        )
    }

    withEnv([
        "REPO_PATH=${context.REPO_PATH}"
    ]) {
        libraries.credentials.withGitHubCredentials {
            if (libraries.tools.gitTagExists(context.TAG)) {
                error("Unable to recreate release tag ${context.TAG}")
            } else {
                echo "Creating release tag ${context.TAG}"

                libraries.tools.gitCreateTag(
                    context.TAG,
                    "Security build ${context.TAG}"
                )
                libraries.tools.gitPushTag(context.TAG)
            }

            echo "Updating floating release tag ${context.FLOATING_TAG}"

            libraries.tools.gitCreateTag(
                context.FLOATING_TAG,
                "Latest security build for ${context.FLOATING_TAG}",
                true
            )

            libraries.tools.gitPushTag(
                context.FLOATING_TAG,
                true
            )
        }
    }
}

void runTests(Map repository, Map context) {
    retry(2) {
        build(
            job: repository.testJob,
            wait: true,
            propagate: true,
            parameters: [
                string(
                    name: 'TEST_SUITE',
                    value: 'run-release.csv'
                ),
                string(
                    name: 'GIT_BRANCH',
                    value: context.SECURITY_BASE_BRANCH
                ),
                string(
                    name: 'IMAGE_OPERATOR',
                    value: context.BUILD_IMAGE
                ),
                string(
                    name: 'GKE_RELEASE_CHANNEL',
                    value: 'stable'
                ),
                string(
                    name: 'CLUSTER_WIDE',
                    value: 'YES'
                ),
                string(
                    name: 'PILLAR_VERSION',
                    value: repository.pillarVersion
                )
            ]
        )
    }
}

void skipStages(List<String> stages, String reason) {
    stages.each { stageName ->
        stage(stageName) {
            echo reason
            Utils.markStageSkippedForConditional(stageName)
        }
    }
}

void processRepository(Map repository) {
    def context

    stage('Clone, Prepare & Checkout') {
        dir(repository.name) {
            deleteDir()
        }

        libraries.tools.gitClone([
            branch: repository.sourceBranch,
            repo  : "https://github.com/${gitNamespace}/${repository.name}.git"
        ], repository.name)

        dir(repository.name) {
            libraries.tools.gitFetchTags()

            context = buildContext(repository)

            currentBuild.displayName =
                "${repository.operator} | ${context.VERSION_TAG}-${context.SECURITY_BUILD_NUMBER}"

            echo """
                Security build: ${context.REPO_PATH}
                Base release tag: ${context.VERSION_TAG}
                Security build number: ${context.SECURITY_BUILD_NUMBER}

                Security base branch: ${context.SECURITY_BASE_BRANCH}
                Security build branch: ${context.SECURITY_BRANCH}

                Base RELEASED image: ${context.BASE_RELEASED_IMAGE}
                BUILD image: ${context.BUILD_IMAGE}
                Latest BUILD image: ${context.FLOATING_BUILD_IMAGE}
                RELEASE image: ${context.RELEASE_IMAGE}
                Latest RELEASE image: ${context.FLOATING_RELEASE_IMAGE}
            """.stripIndent().trim()

            context.SECURITY_BASE_EXISTS =
                ensureSecurityBaseBranch(context)

            context.DOCKERFILE = findDockerfile()
            context.GO_VERSION_FILES = repository.goVersionFiles ?: []

            echo "Dockerfile: ${context.DOCKERFILE}"
            echo "Additional Go version files: ${context.GO_VERSION_FILES.join(', ')}"
        }
    }

    dir(repository.name) {
        def vulnerabilitiesFound
        def vulnerabilitySummary
        def prUrl

        stage('Pull RELEASED Image') {
            context.RELEASED_IMAGE =
                pullReleasedImage(context)

            echo "RELEASED image selected: ${context.RELEASED_IMAGE}"
        }

        stage('Trivy Scan') {
            echo "Scanning RELEASED image: ${context.RELEASED_IMAGE}"

            libraries.tools.trivyScanImage(
                context.RELEASED_IMAGE
            )

            vulnerabilitiesFound =
                addVulnerabilityBadge()
        }

        if (!vulnerabilitiesFound) {
            def reason =
                "No critical or high vulnerabilities found for ${repository.name}"

            skipStages([
                'Fix Vulnerabilities',
                'Build',
                'Trivy Verify',
                'Create Pull Request',
                'Wait for Merge',
                'Checkout Merged Commit',
                'Rebuild Merged Image',
                'Trivy Verify Merged Image',
                'E2E Tests',
                'Publish RELEASE'
            ], reason)

            return
        }

        stage('Fix Vulnerabilities') {
            vulnerabilitySummary =
                fixVulnerabilities(context)
        }

        if (!vulnerabilitySummary) {
            def reason =
                "No fixable Go vulnerabilities found for ${repository.name}"

            skipStages([
                'Build',
                'Trivy Verify',
                'Create Pull Request',
                'Wait for Merge',
                'Checkout Merged Commit',
                'Rebuild Merged Image',
                'Trivy Verify Merged Image',
                'E2E Tests',
                'Publish RELEASE'
            ], reason)

            return
        }

        stage('Build') {
            buildImage(context)
        }

        stage('Trivy Verify') {
            echo "Verifying BUILD image: ${context.BUILD_IMAGE}"

            libraries.tools.trivyVerifyImage(
                context.BUILD_IMAGE
            )
        }

        stage('Create Pull Request') {
            prUrl = createPullRequest(context)

            echo "Pull request: ${prUrl}"
        }

        stage('Wait for Merge') {
            timeout(
                time: 48,
                unit: 'HOURS'
            ) {
                waitForMerge(
                    context,
                    vulnerabilitySummary,
                    prUrl
                )
            }
        }

        stage('Checkout Merged Commit') {
            checkoutMergedCommit(context)
        }

        stage('Rebuild Merged Image') {
            echo "Rebuilding ${context.BUILD_IMAGE} from merged commit ${context.RELEASE_COMMIT}"
            buildImage(context)
        }

        stage('Trivy Verify Merged Image') {
            echo "Verifying merged BUILD image: ${context.BUILD_IMAGE}"

            libraries.tools.trivyVerifyImage(
                context.BUILD_IMAGE
            )
        }

        stage('Push RELEASE Image') {
            publishRelease(context)
        }

        stage('E2E Tests') {
            timeout(
                time: 8,
                unit: 'HOURS'
            ) {
                try {
                    runTests(repository, context)
                } catch (Exception error) {
                    env.SECURITY_BUILD_FAILURE_KIND = 'e2e'
                    env.SECURITY_BUILD_REPOSITORY = context.REPO_PATH
                    env.SECURITY_BUILD_TEST_JOB = repository.testJob
                    env.SECURITY_BUILD_BRANCH = context.SECURITY_BASE_BRANCH
                    env.SECURITY_BUILD_TEST_IMAGE = context.RELEASE_IMAGE
                    throw error
                }
            }
        }

        stage('Publish RELEASE') {
            publishRelease(context)
        }
    }
}

pipeline {
    agent {
        label 'docker-x64-min'
    }

    options {
        disableConcurrentBuilds()
    }

    stages {
        stage('Initialize') {
            steps {
                script {
                    checkout scm
                    getLibraries()
                    libraries.dependencies.install()
                    libraries.dependencies.installTrivy()

                    repositories.each { repository ->
                        echo "Repository: ${gitNamespace}/${repository.name}"
                        echo "Slack channel: ${slackChannel}"

                        processRepository(repository)
                    }
                }
            }
        }
    }

    post {
        failure {
            script {
                def message = [
                    env.SECURITY_BUILD_FAILURE_KIND == 'e2e' ?
                        ':x: *Security build E2E tests failed*' :
                        ':x: *Security build failed*',
                    '',
                    "*Job*: `${env.JOB_NAME}`",
                    "*Build*: `#${env.BUILD_NUMBER}`"
                ]

                if (env.SECURITY_BUILD_FAILURE_KIND == 'e2e') {
                    message.addAll([
                        "*Repository*: `${env.SECURITY_BUILD_REPOSITORY}`",
                        "*Test job*: `${env.SECURITY_BUILD_TEST_JOB}`",
                        "*Branch*: `${env.SECURITY_BUILD_BRANCH}`",
                        "*Test image*: `${env.SECURITY_BUILD_TEST_IMAGE}`"
                    ])
                }

                def messageText = message.join('\n')

                slackSend(
                    botUser: true,
                    channel: slackChannel,
                    failOnError: false,
                    attachments: slackMessageAttachments(
                        messageText,
                        '#FF0000',
                        [[
                            type: 'button',
                            text: [
                                type: 'plain_text',
                                text: 'Open Jenkins build',
                                emoji: true
                            ],
                            url: env.BUILD_URL,
                            action_id: 'open_failed_jenkins_build'
                        ]]
                    )
                )
            }
        }
    }
}
