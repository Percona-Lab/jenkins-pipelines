import groovy.transform.Field

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
    def tag = "${versionTag}-sec.${date}"
    def floatingTag = "${versionTag}-sec-latest"
    def checkoutTag = libraries.tools.gitTagExists(floatingTag) ? floatingTag : versionTag
    def imageTag = "${version}-sec.${date}"
    def floatingImageTag = "${version}-sec-latest"

    [
        BUILD_URL             : env.BUILD_URL,
        REPO_PATH             : "${gitNamespace}/${repository.name}",
        GIT_NAMESPACE         : gitNamespace,
        SOURCE_BRANCH         : repository.sourceBranch,
        VERSION_TAG           : versionTag,
        CHECKOUT_TAG          : checkoutTag,
        SECURITY_BRANCH       : tag,
        TAG                   : tag,
        FLOATING_TAG          : floatingTag,
        IMAGE                 : "${repository.imageRepo}/${repository.imageName}:${imageTag}",
        FLOATING_IMAGE        : "${repository.imageRepo}/${repository.imageName}:${floatingImageTag}",
        RELEASE_IMAGE         : "${repository.releaseImageRepo}/${repository.imageName}:${imageTag}",
        FLOATING_RELEASE_IMAGE: "${repository.releaseImageRepo}/${repository.imageName}:${floatingImageTag}"
    ]
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
                      golang:alpine \
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

String applyGoSecurityFixes(Map context) {
    def baseCommit = libraries.tools.gitHead()
    goSecurityFixScript(context)

    if (libraries.tools.gitHead() == baseCommit) {
        return ''
    }

    sh(
        script: "git log --reverse --format='• %s%n↳ %b%n' '${baseCommit}..HEAD'",
        returnStdout: true
    ).trim()
}

String fixVulnerabilities(Map context) {
    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            libraries.tools.gitCreateBranch(context.SECURITY_BRANCH)
        }

        def summary = applyGoSecurityFixes(context)

        if (!summary) {
            return ''
        }

        libraries.credentials.withGitHubCredentials {
            libraries.tools.gitPushBranch(context.SECURITY_BRANCH)
        }

        summary
    }
}

void rebuildVerifyAndPush(Map context) {
    libraries.tools.dockerBuildImage(context.DOCKERFILE, context.IMAGE)
    libraries.tools.trivyVerifyImage(context.IMAGE)
    libraries.tools.dockerTagImage(context.IMAGE, context.FLOATING_IMAGE)

    libraries.credentials.withDockerCredentials {
        libraries.tools.dockerPushImage(context.IMAGE)
        libraries.tools.dockerPushImage(context.FLOATING_IMAGE)
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
                string(name: 'GIT_BRANCH', value: context.SECURITY_BRANCH),
                string(name: 'IMAGE_OPERATOR', value: context.IMAGE),
                string(name: 'GKE_RELEASE_CHANNEL', value: 'stable'),
                string(name: 'CLUSTER_WIDE', value: 'yes'),
                string(name: 'PILLAR_VERSION', value: repository.pillarVersion)
            ]
        )
    }
}

void waitForApproval(Map repository, Map context, String vulnerabilitySummary) {
    def changesUrl = "https://github.com/${gitNamespace}/${repository.name}/compare/" +
        "${context.CHECKOUT_TAG}...${context.SECURITY_BRANCH}"

    slackSend(
        botUser: true,
        channel: slackChannel,
        color: '#FFA500',
        failOnError: true,
        message: [
            ':warning: *Security build awaiting approval*',
            '',
            "*Repository*: `${gitNamespace}/${repository.name}`",
            "*Branch*: `${context.SECURITY_BRANCH}`",
            "*Image*: `${context.RELEASE_IMAGE}`",
            "*Latest*: `${context.FLOATING_RELEASE_IMAGE}`",
            '',
            '*Vulnerabilities and selected fixes:*',
            vulnerabilitySummary,
            '',
            "<${changesUrl}|Review branch changes against ${context.CHECKOUT_TAG}>",
            "<${context.BUILD_URL}|Open Jenkins to approve>"
        ].join('\n')
    )

    input message: "Publish ${context.RELEASE_IMAGE}?", ok: 'Approve'
}

void publishBuild(Map context) {
    libraries.credentials.withDockerCredentials {
        libraries.tools.dockerTagImage(context.IMAGE, context.RELEASE_IMAGE)
        libraries.tools.dockerTagImage(context.IMAGE, context.FLOATING_RELEASE_IMAGE)
        libraries.tools.dockerPushImage(context.RELEASE_IMAGE)
        libraries.tools.dockerPushImage(context.FLOATING_RELEASE_IMAGE)
    }

    withEnv(["REPO_PATH=${context.REPO_PATH}"]) {
        libraries.credentials.withGitHubCredentials {
            libraries.tools.gitCreateTag(context.TAG, "Security build ${context.TAG}")
            libraries.tools.gitPushTag(context.TAG)
            libraries.tools.gitCreateTag(
                context.FLOATING_TAG,
                "Latest security build for ${context.FLOATING_TAG}",
                true
            )
            libraries.tools.gitPushTag(context.FLOATING_TAG, true)

            def prUrl = libraries.tools.githubCreatePullRequest(
                context.REPO_PATH,
                context.GIT_NAMESPACE,
                context.SECURITY_BRANCH,
                context.SOURCE_BRANCH,
                "Security build ${context.TAG}",
                "Automated security update for ${context.TAG}."
            )
            echo "Pull request: ${prUrl}"
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
                Image: ${context.IMAGE}
                Floating image: ${context.FLOATING_IMAGE}
                Release image: ${context.RELEASE_IMAGE}
                Floating release image: ${context.FLOATING_RELEASE_IMAGE}
            """.stripIndent().trim()

            libraries.tools.gitCheckoutTag(context.CHECKOUT_TAG)
            context.DOCKERFILE = findDockerfile()
            echo "Dockerfile: ${context.DOCKERFILE}"
        }
    }

    dir(repository.name) {
        def vulnerabilitySummary

        stage("Build & Scan") {
            libraries.tools.dockerBuildImage(context.DOCKERFILE, context.IMAGE)
            libraries.tools.trivyScanImage(context.IMAGE)
        }

        stage("Fix vulnerabilities") {
            vulnerabilitySummary = fixVulnerabilities(context)
        }

        if (!vulnerabilitySummary) {
            echo "No fixable Go vulnerabilities found for ${repository.name}; skipping security publication"
            return
        }
        stage("Rebuild & Verify") {
            rebuildVerifyAndPush(context)
        }
        stage("E2E Tests") {
            timeout(time: 6, unit: 'HOURS') {
                //runTests(repository, context)
                echo "E2E tests are currently disabled."
            }
        }
        stage("Approval") {
            timeout(time: 8, unit: 'HOURS') {
                waitForApproval(repository, context, vulnerabilitySummary)
            }
        }
        stage("Publish") {
            publishBuild(context)
        }
    }
}

pipeline {
    agent {
        label 'docker-x64-min'
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
                    libraries.dependencies.installTrivy()
                    securityBuildDate = sh(script: 'date -u +%Y%m%d', returnStdout: true).trim()
                    echo "Build date: ${securityBuildDate}; operator: ${params.OPERATOR}"
                    processRepository(selectRepository(params.OPERATOR), securityBuildDate)
                }
            }
        }
    }
}
