import groovy.transform.Field
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

@Field String gitNamespace = 'percona'
@Field String slackChannel = '#cloud-dev-ci'
@Field Map libraries = [:]

@Field List securityBuildStages = [
    'Clone, Prepare & Checkout',
    'Pull RELEASED Image',
    'Trivy Scan',
    'Fix Vulnerabilities',
    'Build',
    'Trivy Verify',
    'Create Pull Request',
    'Wait for Merge',
    'Checkout Merged Commit',
    'Rebuild Merged Image',
    'Trivy Verify Merged Image',
    'Push RELEASE Image',
    'E2E Tests',
    'Publish RELEASE'
]

@Field List repositories = [
    [
        operator              : 'ps-operator',
        name                  : 'percona-server-mysql-operator',
        sourceBranch          : 'main',
        imageName             : 'percona-server-mysql-operator',
        imageRepo             : 'perconalab',
        releaseImageRepo      : 'percona',
        testJob               : 'pso-gke-1',
        // Reads the image from the default CR and converts 8.4 to 84
        pillarVersion         : [imagePath: '.spec.mysql.image', strategy: 'majorMinor'],
        goVersionFiles        : [], // List of files containing Go version references to be updated
        operatorVersionFiles  : [
            'pkg/controller/ps/suite_test.go'
        ] // List of files containing operator version references to be updated
    ]
]

void getLibraries() {
    libraries = load('cloud/common/libraries.groovy').loadLibraries()
}

Map buildContext(Map repository) {
    def baseReleaseGitTag = sh(
        script: "git tag --list --sort=-v:refname | grep -E '^v[0-9]+[.][0-9]+[.][0-9]+\$' | head -1",
        returnStdout: true
    ).trim()

    if (!baseReleaseGitTag) {
        error("${repository.name} has no version tag matching vMAJOR.MINOR.PATCH")
    }

    def version = baseReleaseGitTag.substring(1)

    def buildImageRepository =
        "${repository.imageRepo}/${repository.imageName}"

    def releaseImageRepository =
        "${repository.releaseImageRepo}/${repository.imageName}"

    def buildNumber =
        nextSecurityBuildNumber(version, releaseImageRepository)

    def previousBuildNumber = buildNumber - 1

    def securityBaseBranch = "security/${version}"
    def securityBranch = "${securityBaseBranch}-${buildNumber}"

    def numberedImageTag = "${version}-${buildNumber}"
    def floatingImageTag = "${version}-latest"

    def baseReleasedImage = "${releaseImageRepository}:${version}-${previousBuildNumber}"

    def buildImage = "${buildImageRepository}:${numberedImageTag}"
    def floatingBuildImage = "${buildImageRepository}:${floatingImageTag}"

    def releaseImage = "${releaseImageRepository}:${numberedImageTag}"
    def floatingReleaseImage = "${releaseImageRepository}:${floatingImageTag}"

    return [
        // Repository configuration
        REPO_PATH             : "${gitNamespace}/${repository.name}",
        GIT_NAMESPACE         : gitNamespace,
        OPERATOR              : repository.operator,
        OPERATOR_NAME         : repository.name,
        SOURCE_BRANCH         : repository.sourceBranch,
        GO_VERSION_FILES      : repository.goVersionFiles,
        OPERATOR_VERSION_FILES: repository.operatorVersionFiles,
        BUILD_URL             : env.BUILD_URL,

        // Base release
        VERSION               : version, // Example: 1.0.0
        BASE_RELEASE_GIT_TAG  : baseReleaseGitTag, // Git tag example: v1.0.0
        SECURITY_BASE_BRANCH  : securityBaseBranch, // Example: security/1.0.0
        PREVIOUS_BUILD_NUMBER : previousBuildNumber, // Example: 1
        BASE_RELEASED_IMAGE   : baseReleasedImage, // Example: percona/percona-server-mysql-operator:1.0.0-1

        // Security build
        SECURITY_BUILD_NUMBER : buildNumber, // Example: 2
        SECURITY_BRANCH       : securityBranch, // Example: security/1.0.0-2
        NUMBERED_IMAGE_TAG    : numberedImageTag, // Example: 1.0.0-2
        FLOATING_IMAGE_TAG    : floatingImageTag, // Example: 1.0.0-latest

        // Build images
        BUILD_REPOSITORY      : repository.imageRepo, // Example: perconalab
        BUILD_IMAGE_REPOSITORY: buildImageRepository, // Example: perconalab/percona-server-mysql-operator
        BUILD_IMAGE           : buildImage, // Example: perconalab/percona-server-mysql-operator:1.0.0-2
        FLOATING_BUILD_IMAGE  : floatingBuildImage, // Example: perconalab/percona-server-mysql-operator:1.0.0-latest

        // Release publication
        RELEASE_REPOSITORY    : repository.releaseImageRepo, // Example: percona
        RELEASE_IMAGE         : releaseImage, // Example: percona/percona-server-mysql-operator:1.0.0-2
        FLOATING_RELEASE_IMAGE: floatingReleaseImage, // Example: percona/percona-server-mysql-operator:1.0.0-latest
        FLOATING_GIT_TAG      : "security/v${version}" // Git tag example: security/v1.0.0
    ]
}

List slackMessageAttachments(
    String text,
    String color,
    List<Map> buttons = []
) {
    def blocks = [[
        type: 'section',
        text: [
            type: 'mrkdwn',
            text: text
        ]
    ]]

    if (buttons) {
        blocks << [
            type: 'actions',
            elements: buttons
        ]
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

    def dockerfiles = sh(
        script: 'find build -type f -name Dockerfile -print 2>/dev/null | sort || true',
        returnStdout: true
    ).trim().readLines()

    if (dockerfiles.size() > 1) {
        error("Multiple Dockerfiles found under build/: ${dockerfiles.join(', ')}")
    }

    if (!dockerfiles) {
        error('Dockerfile was not found in the repository root or under build/')
    }

    return dockerfiles.first()
}

String getPillarVersionFromDefaultCR(Map repository) {
    def config = repository.pillarVersion

    if (!config?.imagePath) {
        error("pillarVersion.imagePath is not configured for ${repository.name}")
    }

    return withEnv([
        "PILLAR_IMAGE_PATH=${config.imagePath}",
        "PILLAR_VERSION_STRATEGY=${config.strategy ?: 'majorMinor'}"
    ]) {
        sh(
            script: '''
                set -eu

                pillar_image=$(yq eval -r "${PILLAR_IMAGE_PATH} // \\"\\"" deploy/cr.yaml)
                pillar_image_tag="${pillar_image##*:}"

                case "${PILLAR_VERSION_STRATEGY}" in
                    majorMinor)
                        pillar_version=$(printf '%s\n' "${pillar_image_tag}" |
                            sed -nE 's/^[^0-9]*([0-9]+)\.([0-9]+).*/\1\2/p')
                        ;;
                    major)
                        pillar_version=$(printf '%s\n' "${pillar_image_tag}" |
                            sed -nE 's/^[^0-9]*([0-9]+).*/\1/p')
                        ;;
                    *)
                        echo "Unsupported pillar version strategy: ${PILLAR_VERSION_STRATEGY}" >&2
                        exit 1
                        ;;
                esac

                if [ -z "${pillar_version}" ]; then
                    echo "Unable to derive PILLAR_VERSION from ${PILLAR_IMAGE_PATH}: ${pillar_image}" >&2
                    exit 1
                fi

                printf '%s' "${pillar_version}"
            ''',
            returnStdout: true
        ).trim()
    }
}

int nextSecurityBuildNumber(
    String version,
    String releaseImageRepository
) {
    return withEnv([
        "VERSION=${version}",
        "RELEASE_IMAGE_REPOSITORY=${releaseImageRepository}"
    ]) {
        sh(
            script: '''
                set -eu

                candidate=1

                while docker manifest inspect \
                    "${RELEASE_IMAGE_REPOSITORY}:${VERSION}-${candidate}" >/dev/null 2>&1; do
                    candidate=$((candidate + 1))
                done

                echo "${candidate}"
            ''',
            returnStdout: true
        ).trim().toInteger()
    }
}

boolean ensureSecurityBaseBranch(Map context) {
    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            def exists =
                libraries.tools.gitTagExists(context.FLOATING_GIT_TAG) &&
                libraries.tools.gitBranchExists(context.SECURITY_BASE_BRANCH)

            if (exists) {
                echo "Using security branch ${context.SECURITY_BASE_BRANCH} " +
                    "and Git tag ${context.FLOATING_GIT_TAG}"

                libraries.tools.gitFetchTag(context.FLOATING_GIT_TAG)
                libraries.tools.gitCheckoutTag(context.FLOATING_GIT_TAG)

                return true
            }

            echo "Creating security branch ${context.SECURITY_BASE_BRANCH} " +
                "from Git tag ${context.BASE_RELEASE_GIT_TAG}"

            libraries.tools.gitFetchTag(context.BASE_RELEASE_GIT_TAG)
            libraries.tools.gitCheckoutTag(context.BASE_RELEASE_GIT_TAG)

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
        "SECURITY_BUILD_REF=${context.SECURITY_BRANCH}",
        'SCRIPT=jenkins-fix_go_vulnerabilities.py'
    ]) {
        libraries.credentials.withGitHubCredentials {
            try {
                sh '''
                    cp -f ../cloud/scripts/security_fix_go_vulnerabilities.py "${SCRIPT}"

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
                      -e SECURITY_BUILD_REF \
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
                            --tag "${SECURITY_BUILD_REF}"
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
        "OPERATOR=${context.OPERATOR_NAME}",
        "IMAGE_REPO=${context.BUILD_REPOSITORY}",
        "RELEASE_IMAGE_REPO=${context.RELEASE_REPOSITORY}",
        "OPERATOR_RELEASE_IMAGE=${context.RELEASE_IMAGE}",
        "SECURITY_BUILD_REF=${context.SECURITY_BRANCH}",
        "OPERATOR_VERSION_FILES=${context.OPERATOR_VERSION_FILES.join(' ')}"
    ]) {
        sh '''
            set -eu

            BASE_VERSION="$(cat pkg/version/version.txt)"
            OPERATOR_RELEASE_TAG="${OPERATOR_RELEASE_IMAGE##*:}"

            IMAGE_PATTERN="(docker\\.io/)?(${IMAGE_REPO}|${RELEASE_IMAGE_REPO})/${OPERATOR}:${BASE_VERSION}(-[0-9]+)?"
            IMAGE_BOUNDARY="([^[:alnum:]_.-]|$)"

            sed -Ei \
              "s#${IMAGE_PATTERN}${IMAGE_BOUNDARY}#${OPERATOR_RELEASE_IMAGE}\\4#g" \
              config/manager/manager.yaml \
              config/manager/cluster/manager.yaml \
              deploy/bundle.yaml \
              deploy/cw-bundle.yaml \
              deploy/operator.yaml \
              deploy/cw-operator.yaml \
              deploy/cr.yaml \
              e2e-tests/release_versions \
              ${OPERATOR_VERSION_FILES}

            sed -Ei \
              "/name: ${IMAGE_REPO}\\/${OPERATOR}/,/newTag:/ { \
                s#newName: .*#newName: ${RELEASE_IMAGE_REPO}/${OPERATOR}#; \
                s#newTag: .*#newTag: ${OPERATOR_RELEASE_TAG}#; \
              }" \
              config/manager/kustomization.yaml \
              config/manager/cluster/kustomization.yaml

            git add -- \
              config/manager \
              deploy \
              e2e-tests/release_versions \
              ${OPERATOR_VERSION_FILES}

            if ! git diff --cached --quiet; then
                git commit -m "Update operator image references for ${SECURITY_BUILD_REF}"
            fi
        '''
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
            script: """
                git log --reverse --format='%s%x09%b' '${baseCommit}..HEAD' |
                awk -F '\\t' '{
                    printf "• %s\\n", \$1;
                    if (\$2 != "") printf "↳ %s\\n", \$2
                }'
            """,
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

    if (total == '0') {
        addBadge(
            id: 'trivy-clean',
            text: 'NO VULNERABILITIES',
            style: "${badgeStyle} color: #2ecc71; background-color: #183d2b;"
        )

        return false
    }

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

void buildImage(Map context) {
    echo "Building BUILD image: ${context.BUILD_IMAGE}"

    libraries.credentials.withDockerCredentials {
        libraries.tools.dockerBuildAndPush(
            operator: context.OPERATOR,
            operatorImage: context.BUILD_IMAGE_REPOSITORY,
            branch: context.NUMBERED_IMAGE_TAG,
            sourceDir: '.'
        )
    }
}

String createPullRequest(Map context) {
    return withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            libraries.tools.githubCreatePullRequest(
                context.REPO_PATH,
                context.GIT_NAMESPACE,
                context.SECURITY_BRANCH,
                context.SECURITY_BASE_BRANCH,
                "Security build ${context.SECURITY_BRANCH}",
                "Automated security update for ${context.SECURITY_BRANCH}."
            )
        }
    }
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
                def status = libraries.tools.githubPullRequestStatus(
                    context.REPO_PATH,
                    prUrl
                )

                switch (status) {
                    case 'merged':
                        echo 'Pull request is merged; continuing the security build'
                        return true

                    case 'closed':
                        error("Pull request was closed without being merged: ${prUrl}")

                    case 'unknown':
                        echo 'Unable to determine pull request status; retrying'
                        return false

                    default:
                        echo 'Pull request is waiting to be merged'
                        return false
                }
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
            [context.RELEASE_IMAGE]
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
            [context.FLOATING_RELEASE_IMAGE]
        )
    }

    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            echo "Updating floating Git tag ${context.FLOATING_GIT_TAG}"

            libraries.tools.gitCreateTag(
                context.FLOATING_GIT_TAG,
                "Latest security build for ${context.FLOATING_GIT_TAG}",
                true
            )

            libraries.tools.gitPushTag(
                context.FLOATING_GIT_TAG,
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
                    value: context.PILLAR_VERSION
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

void trackedStage(
    Map execution,
    String stageName,
    Closure body
) {
    execution.currentStage = stageName

    stage(stageName) {
        body()
    }
}

void skipRemainingStages(
    String currentStage,
    String reason
) {
    def currentIndex =
        securityBuildStages.indexOf(currentStage)

    skipStages(
        securityBuildStages.drop(currentIndex + 1),
        reason
    )
}

void runRepositoryPipeline(
    Map repository,
    Map execution
) {
    def context
    def vulnerabilitiesFound
    def vulnerabilitySummary
    def prUrl

    trackedStage(execution, 'Clone, Prepare & Checkout') {
        dir(repository.name) {
            deleteDir()
        }

        libraries.tools.gitClone([
            branch: repository.sourceBranch,
            repo: "https://github.com/${gitNamespace}/${repository.name}.git"
        ], repository.name)

        dir(repository.name) {
            libraries.tools.gitFetchTags()

            context = buildContext(repository)

            currentBuild.displayName =
                "${repository.operator} | ${context.BASE_RELEASE_GIT_TAG}-${context.SECURITY_BUILD_NUMBER}"

            echo """
                Security build: ${context.REPO_PATH}
                Base release Git tag: ${context.BASE_RELEASE_GIT_TAG}
                Floating security Git tag: ${context.FLOATING_GIT_TAG}
                Security build number: ${context.SECURITY_BUILD_NUMBER}

                Security base branch: ${context.SECURITY_BASE_BRANCH}
                Security build branch: ${context.SECURITY_BRANCH}

                Numbered image tag: ${context.NUMBERED_IMAGE_TAG}
                Floating image tag: ${context.FLOATING_IMAGE_TAG}
                Base RELEASED image: ${context.BASE_RELEASED_IMAGE}
                BUILD image: ${context.BUILD_IMAGE}
                Latest BUILD image: ${context.FLOATING_BUILD_IMAGE}
                RELEASE image: ${context.RELEASE_IMAGE}
                Latest RELEASE image: ${context.FLOATING_RELEASE_IMAGE}
            """.stripIndent().trim()

            context.SECURITY_BASE_EXISTS =
                ensureSecurityBaseBranch(context)

            context.DOCKERFILE = findDockerfile()

            context.PILLAR_VERSION =
                getPillarVersionFromDefaultCR(repository)

            echo "Dockerfile: ${context.DOCKERFILE}"
            echo "Pillar version: ${context.PILLAR_VERSION}"
            echo "Additional Go version files: ${context.GO_VERSION_FILES.join(', ')}"
        }
    }

    dir(repository.name) {
        trackedStage(execution, 'Pull RELEASED Image') {
            context.RELEASED_IMAGE =
                pullReleasedImage(context)

            echo "RELEASED image selected: ${context.RELEASED_IMAGE}"
        }

        trackedStage(execution, 'Trivy Scan') {
            echo "Scanning RELEASED image: ${context.RELEASED_IMAGE}"

            libraries.tools.trivyScanImage(
                context.RELEASED_IMAGE
            )

            vulnerabilitiesFound =
                addVulnerabilityBadge()
        }

        if (!vulnerabilitiesFound) {
            skipRemainingStages(
                execution.currentStage,
                "No critical or high vulnerabilities found for ${repository.name}"
            )

            return
        }

        trackedStage(execution, 'Fix Vulnerabilities') {
            vulnerabilitySummary = fixVulnerabilities(context)
        }

        if (!vulnerabilitySummary) {
            skipRemainingStages(
                execution.currentStage,
                "No fixable Go vulnerabilities found for ${repository.name}"
            )

            return
        }

        trackedStage(execution, 'Build') {
            buildImage(context)
        }

        trackedStage(execution, 'Trivy Verify') {
            echo "Verifying BUILD image: ${context.BUILD_IMAGE}"

            libraries.tools.trivyVerifyImage(
                context.BUILD_IMAGE
            )
        }

        trackedStage(execution, 'Create Pull Request') {
            prUrl = createPullRequest(context)

            echo "Pull request: ${prUrl}"
        }

        trackedStage(execution, 'Wait for Merge') {
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

        trackedStage(execution, 'Checkout Merged Commit') {
            checkoutMergedCommit(context)
        }

        trackedStage(execution, 'Rebuild Merged Image') {
            echo "Rebuilding ${context.BUILD_IMAGE} from merged commit ${context.RELEASE_COMMIT}"

            buildImage(context)
        }

        trackedStage(execution, 'Trivy Verify Merged Image') {
            echo "Verifying merged BUILD image: ${context.BUILD_IMAGE}"

            libraries.tools.trivyVerifyImage(
                context.BUILD_IMAGE
            )
        }

        trackedStage(execution, 'Push RELEASE Image') {
            pushReleaseImage(context)
        }

        trackedStage(execution, 'E2E Tests') {
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

        trackedStage(execution, 'Publish RELEASE') {
            publishRelease(context)
        }
    }
}

void processRepository(Map repository) {
    def execution = [
        currentStage: null
    ]

    try {
        runRepositoryPipeline(
            repository,
            execution
        )
    } catch (Exception failure) {
        def failedStage =
            execution.currentStage ?: 'pipeline initialization'

        skipRemainingStages(
            execution.currentStage,
            "Skipped because ${failedStage} failed"
        )

        throw failure
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
                    def repositoryStarted = false

                    try {
                        checkout scm

                        getLibraries()

                        libraries.dependencies.install()
                        libraries.dependencies.installTrivy()

                        repositories.each { repository ->
                            repositoryStarted = true

                            echo "Repository: ${gitNamespace}/${repository.name}"
                            echo "Slack channel: ${slackChannel}"

                            processRepository(repository)
                        }
                    } catch (Exception failure) {
                        if (!repositoryStarted) {
                            skipStages(
                                securityBuildStages,
                                'Skipped because Initialize failed'
                            )
                        }

                        throw failure
                    }
                }
            }
        }
    }

    post {
        failure {
            script {
                def isE2EFailure =
                    env.SECURITY_BUILD_FAILURE_KIND == 'e2e'

                def message = [
                    isE2EFailure ?
                        ':x: *Security build E2E tests failed*' :
                        ':x: *Security build failed*',
                    '',
                    "*Job*: `${env.JOB_NAME}`",
                    "*Build*: `#${env.BUILD_NUMBER}`"
                ]

                if (isE2EFailure) {
                    message.addAll([
                        "*Repository*: `${env.SECURITY_BUILD_REPOSITORY}`",
                        "*Test job*: `${env.SECURITY_BUILD_TEST_JOB}`",
                        "*Branch*: `${env.SECURITY_BUILD_BRANCH}`",
                        "*Test image*: `${env.SECURITY_BUILD_TEST_IMAGE}`"
                    ])
                }

                slackSend(
                    botUser: true,
                    channel: slackChannel,
                    failOnError: false,
                    attachments: slackMessageAttachments(
                        message.join('\n'),
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
