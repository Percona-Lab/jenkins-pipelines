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
    def images = [:]

    readFile("source/e2e-tests/release_versions").readLines().each { line ->
        def releaseItem = line.trim()
        if (!releaseItem || releaseItem.startsWith("#")) return

        def releaseParts = releaseItem.split("=", 2)
        if (releaseParts.size() != 2) return

        def imageName = releaseParts[0].trim()
        if (!imageName.startsWith("IMAGE_")) return

        images[imageName] = releaseParts[1].trim().replace('"', '')
    }

    return images
}

def certifyImage(key, target, params, tests) {
    def startedAt = System.currentTimeMillis()
    def status
    def dest = target.dest
    def component = target.component

    withCredentials([
        string(credentialsId: 'PYXIS_TOKEN', variable: 'PYXIS_TOKEN'),
        usernamePassword(
            credentialsId: target.credentials,
            usernameVariable: 'REGISTRY_USER',
            passwordVariable: 'REGISTRY_KEY'
        )
    ]) {
        component = component ?: env.REGISTRY_USER.replaceAll(/^.*\+/, '').replaceAll(/-robot$/, '')
        dest = dest.replace('__PROJECT_ID__', component)

        status = sh(
            returnStatus: true,
            script: """
            set -e
            python3 cloud/scripts/certify_images.py \
              --image ${target.src} \
              --dest_image ${dest} \
              --component ${component} \
              --platform ${params.PLATFORM}
        """
        )
    }

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

def sendSlack(tests, branch, platform, release) {
    try {
        def passedImages = tests.findAll { it.result == 'passed' }.collect { it.name }
        def failedImages = tests.findAll { it.result == 'failure' }.collect { it.name }
        def skippedImages = tests.findAll { it.result == 'skipped' }.collect { it.name }
        if (!passedImages && !failedImages) {
            echo "No image certification results found. Slack notification skipped."
            return
        }

        def color = failedImages ? '#DAA038' : '#36A64F'
        def status = failedImages ? 'UNSTABLE' : 'SUCCESS'
        def message = "*${env.JOB_NAME} #${env.BUILD_NUMBER}* - ${status}\n"
        message += "*Build:* ${env.BUILD_URL}\n"
        message += "*Version:* ${release}\n"
        message += "*Branch:* ${branch}\n"
        message += "*Platform:* ${platform}\n"
        if (passedImages) {
            message += "*Passed:* ${passedImages.join(', ')}\n"
        }
        if (failedImages) {
            message += "*Failed:* ${failedImages.join(', ')}\n"
        }
        if (skippedImages) {
            message += "*Skipped:* ${skippedImages.join(', ')}"
        }

        slackSend channel: '#cloud-dev-ci', color: color, message: message
    } catch (err) {
        echo "Slack notification failed: ${err}"
    }
}

return this
