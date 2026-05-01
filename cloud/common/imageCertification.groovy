def prepareSources(Map cfg) {
    def branch = cfg.branch
    def repo = cfg.repo

    echo "=========================[ Cloning the sources ]========================="
    echo "Using branch: ${branch}"

    sh """
        set -e
        rm -rf source
        git clone -b ${branch} ${repo} source
    """
}

def loadReleaseVersions() {
    def content = readFile("source/e2e-tests/release_versions")
    def images = [:]

    content.readLines().each { line ->
        line = line.trim()
        if (!line || line.startsWith("#")) return

        def parts = line.split("=", 2)
        if (parts.size() == 2) {
            images[parts[0].trim()] = parts[1].trim().replace('"', '')
        }
    }

    return images
}

def getTag(image) {
    def parts = image.tokenize(":")
    return parts.size() > 1 ? parts[-1] : "latest"
}

def certifyImage(key, target, params, tests) {
    def startedAt = System.currentTimeMillis()
    def status = sh(
        returnStatus: true,
        script: """
        set -e
        python3 cloud/scripts/certify_images.py \
          --image ${target.src} \
          --dest_image ${target.dest} \
          --component ${target.component} \
          --platform ${params.PLATFORM}
    """
    )

    tests.add([
        name: key,
        cluster: params.PLATFORM,
        result: status == 0 ? 'passed' : 'failure',
        time: (System.currentTimeMillis() - startedAt) / 1000,
    ])

    return status == 0
}

def publishResults() {
    junit(
        testResults: 'preflight-results/*.xml',
        allowEmptyResults: true,
        keepLongStdio: true
    )
    archiveArtifacts(
        artifacts: 'preflight-results/**/*',
        allowEmptyArchive: true,
        fingerprint: true
    )
}

def sendSlack(tests, branch, platform) {
    try {
        def passedImages = tests.findAll { it.result == 'passed' }.collect { it.name }
        def failedImages = tests.findAll { it.result == 'failure' }.collect { it.name }
        def color = failedImages ? '#DAA038' : '#36A64F'
        def status = failedImages ? 'UNSTABLE' : 'SUCCESS'
        def message = "*${env.JOB_NAME} #${env.BUILD_NUMBER}* - ${status}\n"
        message += "*Build:* ${env.BUILD_URL}\n"
        message += "*Branch:* ${branch}\n"
        message += "*Platform:* ${platform}\n"
        if (passedImages) {
            message += "*Passed:* ${passedImages.join(', ')}\n"
        }
        if (failedImages) {
            message += "*Failed:* ${failedImages.join(', ')}"
        }

        slackSend channel: '#cloud-dev-ci', color: color, message: message
    } catch (err) {
        echo "Slack notification failed: ${err}"
    }
}

return this
