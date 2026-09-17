import groovy.transform.Field
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

@Field String gitNamespace = 'percona'
@Field String slackChannel = '#cloud-security-builds'
@Field String securityBuildDate
@Field Map libraries = [:]

@Field List repositories = [
    [
        name            : 'percona-server-mysql-operator',
        sourceBranch    : 'main',
        imageName       : 'percona-server-mysql-operator',
        imageRepo       : 'docker.io/perconalab',
        releaseImageRepo: 'docker.io/percona',
        testJob         : 'pso-gke-1',
        pillarVersion    : '84'
    ]
]

void getLibraries() {
    libraries = load('cloud/common/libraries.groovy').loadLibraries()
}

void validateRepositories() {
    def required = [
        'name', 'sourceBranch', 'imageName',
        'imageRepo', 'releaseImageRepo', 'testJob'
    ]
    repositories.each { repository ->
        def missing = required.findAll { !repository[it] }

        if (missing) {
            error("${repository.name ?: 'Repository'} is missing: ${missing.join(', ')}")
        }
    }

    def duplicates = repositories*.name.groupBy { it }.findAll { it.value.size() > 1 }.keySet()

    if (duplicates) {
        error("Repository names must be unique: ${duplicates.join(', ')}")
    }
}

Map selectRepository(String name) {
    def repository = repositories.find { it.name == name }

    if (!repository) {
        error("Unknown operator '${name}'. Available operators: ${repositories*.name.join(', ')}")
    }

    repository
}

String findDockerfile() {
    if (fileExists('Dockerfile')) {
        return 'Dockerfile'
    }

    if (fileExists('build/Dockerfile')) {
        return 'build/Dockerfile'
    }

    def output = sh(
        script: "find build -type f -name Dockerfile -print 2>/dev/null | sort || true",
        returnStdout: true
    ).trim()
    def dockerfiles = output ? output.split('\n') as List : []

    if (dockerfiles.size() > 1) {
        error("Multiple Dockerfiles found under build/: ${dockerfiles.join(', ')}")
    }

    if (!dockerfiles) {
        error('Dockerfile was not found in the repository root or under build/')
    }

    dockerfiles.first()
}

Map buildContext(Map repository, String date) {
    def versionTag = sh(
        script: "git tag --list --sort=-v:refname | grep -E '^v[0-9]+[.][0-9]+[.][0-9]+\$' | head -1",
        returnStdout: true
    ).trim()

    if (!versionTag) {
        error("${repository.name} has no version tag matching vMAJOR.MINOR.PATCH")
    }

    def version = versionTag.substring(1)
    def tag = "security/${version}-${date}"
    def floatingTag = "security/${version}"
    def checkoutTag = versionTag
    def securityBaseBranch = "security/${version}"
    def securityBranch = "${securityBaseBranch}-${date}"
    def imageTag = "${version}-sec-${date}"
    def floatingImageTag = "${version}-sec-latest"

    [
        BUILD_URL             : env.BUILD_URL,
        REPO_PATH             : "${gitNamespace}/${repository.name}",
        GIT_NAMESPACE         : gitNamespace,
        SOURCE_BRANCH         : repository.sourceBranch,
        VERSION_TAG           : versionTag,
        CHECKOUT_TAG          : checkoutTag,
        SECURITY_BASE_BRANCH  : securityBaseBranch,
        SECURITY_BRANCH       : securityBranch,
        TAG                   : tag,
        FLOATING_TAG          : floatingTag,
        SOURCE_IMAGE          : "${repository.releaseImageRepo}/${repository.imageName}:${version}",
        IMAGE                 : "${repository.imageRepo}/${repository.imageName}:${imageTag}",
        FLOATING_IMAGE        : "${repository.imageRepo}/${repository.imageName}:${floatingImageTag}",
        RELEASE_IMAGE         : "${repository.releaseImageRepo}/${repository.imageName}:${imageTag}",
        FLOATING_RELEASE_IMAGE: "${repository.releaseImageRepo}/${repository.imageName}:${floatingImageTag}"
    ]
}

String pullProductionImage(Map context) {
    def image = context.SECURITY_BASE_EXISTS ? context.FLOATING_RELEASE_IMAGE : context.SOURCE_IMAGE

    withEnv(["PRODUCTION_IMAGE=${image}"]) {
        sh '''
            set -eu
            echo "Pulling production image ${PRODUCTION_IMAGE}"
            docker pull "${PRODUCTION_IMAGE}"
        '''
    }

    return image
}

boolean ensureSecurityBaseBranch(Map context) {
    def branchExists

    withEnv([
        "REPO_PATH=${context.REPO_PATH}",
        "SECURITY_BASE_BRANCH=${context.SECURITY_BASE_BRANCH}"
    ]) {
        libraries.credentials.withGitHubCredentials {
            branchExists = sh(
                script: 'git ls-remote --exit-code --heads origin "refs/heads/${SECURITY_BASE_BRANCH}"',
                returnStatus: true
            ) == 0

            if (branchExists) {
                echo "Using existing security base branch ${context.SECURITY_BASE_BRANCH}"
                sh '''
                    git fetch origin \
                        "refs/heads/${SECURITY_BASE_BRANCH}:refs/remotes/origin/${SECURITY_BASE_BRANCH}"
                    git checkout --detach "refs/remotes/origin/${SECURITY_BASE_BRANCH}"
                '''
            } else {
                echo "Using release tag ${context.VERSION_TAG} and creating ${context.SECURITY_BASE_BRANCH}"
                sh 'git push origin "HEAD:refs/heads/${SECURITY_BASE_BRANCH}"'
            }
        }
    }

    return branchExists
}

void goSecurityFixScript(Map context) {
    withEnv([
        "DOCKERFILE=${context.DOCKERFILE}",
        "REPO_PATH=${context.REPO_PATH}",
        "TAG=${context.TAG}"
    ]) {
        libraries.credentials.withGitHubCredentials {
            def scriptFile = '.jenkins-security_build_fix_go_vulnerabilities.py'
            writeFile file: scriptFile, text: readFile('../cloud/scripts/security_build_fix_go_vulnerabilities.py')

            try {
                sh '''
                    docker run --rm \
                      -v "${PWD}:${PWD}" \
                      -e HOME=/tmp \
                      -e GOCACHE=/tmp/go-cache \
                      -e GOMODCACHE=/tmp/go-mod-cache \
                      -e HOST_UID="$(id -u)" \
                      -e HOST_GID="$(id -g)" \
                      -e GITHUB_TOKEN \
                      -e DOCKERFILE \
                      -e TAG \
                      -w "${PWD}" \
                      golang:1.27-alpine \
                      sh -ceu 'apk add --no-cache bash curl git jq make patch python3 su-exec yq; \
                        exec su-exec "${HOST_UID}:${HOST_GID}" \
                          python3 -u .jenkins-security_build_fix_go_vulnerabilities.py \
                            --trivy-report trivy.json \
                            --go-mod go.mod \
                            --dockerfile "${DOCKERFILE}" \
                            --tag "${TAG}"'
                '''
            } finally {
                sh "rm -f '${scriptFile}'"
            }
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

void pushDevelopmentBuild(Map context) {
    libraries.credentials.withDockerCredentials {
        libraries.tools.dockerCopyImage(context.IMAGE, [context.FLOATING_IMAGE])
    }
}

void addVulnerabilityBadge() {
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
    def badgeStyle = 'padding: 4px 12px; border-radius: 8px; font-weight: 600;'

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
    } else {
        addBadge(
            id: 'trivy-clean',
            text: 'NO VULNERABILITIES',
            style: "${badgeStyle} color: #2ecc71; background-color: #183d2b;"
        )
    }
}

void runTests(Map repository, Map context) {
    retry(2) {
        build(
            job: repository.testJob,
            wait: true,
            propagate: true,
            parameters: [
                string(name: 'TEST_SUITE', value: 'run-release.csv'),
                string(name: 'GIT_BRANCH', value: context.SECURITY_BASE_BRANCH),
                string(name: 'IMAGE_OPERATOR', value: context.RELEASE_IMAGE),
                string(name: 'GKE_RELEASE_CHANNEL', value: 'stable'),
                string(name: 'CLUSTER_WIDE', value: 'YES'),
                string(name: 'PILLAR_VERSION', value: repository.pillarVersion)
            ]
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

void waitForApprovalOrMerge(Map repository, Map context, String vulnerabilitySummary, String prUrl) {
    slackSend(
        botUser: true,
        channel: slackChannel,
        color: '#FFA500',
        failOnError: true,
        message: [
            ':warning: *Security build awaiting PR approval or merge*',
            '',
            "*Repository*: `${gitNamespace}/${repository.name}`",
            "*Branch*: `${context.SECURITY_BRANCH}`",
            "*Target branch*: `${context.SECURITY_BASE_BRANCH}`",
            "*DEV image*: `${context.IMAGE}`",
            "*Image*: `${context.RELEASE_IMAGE}`",
            "*Latest*: `${context.FLOATING_RELEASE_IMAGE}`",
            '',
            '*Vulnerabilities and selected fixes:*',
            vulnerabilitySummary,
            '',
            "<${prUrl}|Review security pull request>",
            "<${context.BUILD_URL}|Open Jenkins build>"
        ].join('\n')
    )

    addSummary(
        id: 'security-build-approval',
        text: """
            <b>Security build awaiting PR approval or merge</b><br>
            <b>Repository:</b> ${gitNamespace}/${repository.name}<br>
            <b>Branch:</b> ${context.SECURITY_BRANCH}<br>
            <b>Target branch:</b> ${context.SECURITY_BASE_BRANCH}<br>
            <b>DEV image:</b> ${context.IMAGE}<br>
            <b>PROD image:</b> ${context.RELEASE_IMAGE}<br>
            <b>Latest PROD image:</b> ${context.FLOATING_RELEASE_IMAGE}<br><br>
            <b>Vulnerabilities and selected fixes:</b><br>
            <pre>${vulnerabilitySummary}</pre>
            <a href="${prUrl}" target="_blank"
               style="display: inline-block; padding: 8px 14px; color: #fff; background-color: #238636; border-radius: 6px; text-decoration: none; font-weight: 600;">
                Review security pull request
            </a><br>
            <a href="${context.BUILD_URL}">Open Jenkins build</a>
        """.stripIndent().trim()
    )

    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            waitUntil(initialRecurrencePeriod: 30000, quiet: true) {
                if (libraries.tools.githubMergeApprovedPullRequest(context.REPO_PATH, prUrl)) {
                    echo 'Pull request is merged; continuing the security build'
                    return true
                }

                echo 'Pull request is waiting for approval, required checks, or merge'
                return false
            }
        }
    }
}

void publishBuild(Map context) {
    libraries.credentials.withDockerCredentials {
        libraries.tools.dockerCopyImage(
            context.IMAGE,
            [context.RELEASE_IMAGE, context.FLOATING_RELEASE_IMAGE]
        )
    }

    withEnv([
        "REPO_PATH=${context.REPO_PATH}",
        "SECURITY_BASE_BRANCH=${context.SECURITY_BASE_BRANCH}"
    ]) {
        libraries.credentials.withGitHubCredentials {
            sh '''
                set -eu
                git fetch origin \
                    "refs/heads/${SECURITY_BASE_BRANCH}:refs/remotes/origin/${SECURITY_BASE_BRANCH}"
                git checkout --detach "refs/remotes/origin/${SECURITY_BASE_BRANCH}"
            '''
            def releaseCommit = libraries.tools.gitHead()

            if (libraries.tools.gitTagExists(context.TAG)) {
                def taggedCommit = libraries.tools.gitTagCommit(context.TAG)

                if (taggedCommit != releaseCommit) {
                    error(
                        "Tag ${context.TAG} already points to ${taggedCommit}, " +
                        "but the approved branch points to ${releaseCommit}"
                    )
                }

                echo "Reusing ${context.TAG}, which already points to ${releaseCommit}"
            } else {
                libraries.tools.gitCreateTag(context.TAG, "Security build ${context.TAG}")
                libraries.tools.gitPushTag(context.TAG)
            }
            libraries.tools.gitCreateTag(
                context.FLOATING_TAG,
                "Latest security build for ${context.FLOATING_TAG}",
                true
            )
            libraries.tools.gitPushTag(context.FLOATING_TAG, true)
        }
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

void processRepository(Map repository, String date) {
    def context

    stage("Clone, Prepare & Checkout") {
        libraries.tools.gitClone([
            branch: repository.sourceBranch,
            repo  : "https://github.com/${gitNamespace}/${repository.name}.git"
        ], repository.name)

        dir(repository.name) {
            libraries.tools.gitFetchTags()
            context = buildContext(repository, date)
            def testJobPrefix = repository.testJob.tokenize('-_').first()
            currentBuild.displayName = "${testJobPrefix} | ${context.VERSION_TAG} | ${date}"
            echo """
                Security build: ${gitNamespace}/${repository.name}
                Base tag: ${context.VERSION_TAG}
                Checkout tag: ${context.CHECKOUT_TAG}
                Branch: ${context.SECURITY_BRANCH}
                Target branch: ${context.SECURITY_BASE_BRANCH}
                Image: ${context.IMAGE}
                Floating image: ${context.FLOATING_IMAGE}
                Release image: ${context.RELEASE_IMAGE}
                Floating release image: ${context.FLOATING_RELEASE_IMAGE}
            """.stripIndent().trim()

            libraries.tools.gitCheckoutTag(context.CHECKOUT_TAG)
            context.SECURITY_BASE_EXISTS = ensureSecurityBaseBranch(context)
            context.DOCKERFILE = findDockerfile()
            echo "Dockerfile: ${context.DOCKERFILE}"
        }
    }

    dir(repository.name) {
        def vulnerabilitySummary
        def prUrl

        stage("Pull PROD Image") {
            context.SCAN_IMAGE = pullProductionImage(context)
            echo "Scanning production image: ${context.SCAN_IMAGE}"
        }
        stage("Trivy Scan") {
            libraries.tools.trivyScanImage(context.SCAN_IMAGE)
            addVulnerabilityBadge()
        }

        stage("Fix vulnerabilities") {
            vulnerabilitySummary = fixVulnerabilities(context)
        }

        if (!vulnerabilitySummary) {
            def reason = "No fixable Go vulnerabilities found for ${repository.name}"
            skipStages([
                'Rebuild',
                'Trivy Verify',
                'Push DEV Image',
                'Create Pull Request',
                'Approval or Merge',
                'Publish',
                'E2E Tests'
            ], reason)
            return
        }
        stage("Rebuild") {
            libraries.credentials.withDockerCredentials {
                libraries.tools.dockerBuildMultiarchImage(context.DOCKERFILE, context.IMAGE)
            }
        }
        stage("Trivy Verify") {
            libraries.tools.trivyVerifyImage(context.IMAGE)
        }
        stage("Push DEV Image") {
            pushDevelopmentBuild(context)
        }
        stage("Create Pull Request") {
            prUrl = createPullRequest(context)
            echo "Pull request: ${prUrl}"
        }
        stage("Approval or Merge") {
            timeout(time: 8, unit: 'HOURS') {
                waitForApprovalOrMerge(repository, context, vulnerabilitySummary, prUrl)
            }
        }
        stage("Publish") {
            publishBuild(context)
        }
        stage("E2E Tests") {
            timeout(time: 8, unit: 'HOURS') {
                runTests(repository, context)
            }
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

    parameters {
        choice(
            name: 'OPERATOR',
            choices: repositories*.name,
            description: 'Operator repository to process in this security build'
        )
    }

    stages {
        stage('Initialize & Build repository') {
            steps {
                script {
                    getLibraries()
                    validateRepositories()
                    installTrivy(method: 'binary')
                    securityBuildDate = sh(script: 'date -u +%Y%m%d', returnStdout: true).trim()
                    echo "Build date: ${securityBuildDate}; operator: ${params.OPERATOR}"
                    processRepository(selectRepository(params.OPERATOR), securityBuildDate)
                }
            }
        }
    }
}
