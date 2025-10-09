/**
 * OpenShift tools installation and utilities library.
 *
 * Provides functionality for installing OpenShift CLI tools, managing versions,
 * and performing cluster operations. Handles version resolution, checksum
 * verification, and Helm integration.
 *
 * @since 1.0.0
 */

/**
 * Logging utility with configurable log levels.
 *
 * Provides structured logging with timestamps and severity levels.
 * Log level hierarchy: DEBUG < INFO < WARN < ERROR
 *
 * @param level Log level (required, one of: DEBUG, INFO, WARN, ERROR)
 * @param message Log message to output (required)
 * @param params Optional parameters map containing:
 *   - logLevel: Override default log level (optional, default: env.OPENSHIFT_LOG_LEVEL or 'INFO')
 *
 * @since 1.0.0
 *
 * @example
 * openshiftTools.log('INFO', 'Starting cluster creation')
 * openshiftTools.log('DEBUG', 'Detailed info', [logLevel: 'DEBUG'])
 */
def log(String level, String message, Map params = [:]) {
    def logLevels = ['DEBUG': 0, 'INFO': 1, 'WARN': 2, 'ERROR': 3]
    def currentLevel = params.logLevel ?: env.OPENSHIFT_LOG_LEVEL ?: 'INFO'

    if (!logLevels.containsKey(currentLevel)) {
        currentLevel = 'INFO'
    }

    if (logLevels[level] >= logLevels[currentLevel]) {
        def timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date())
        echo "[${timestamp}] [${level}] ${message}"
    }
}

/**
 * Installs OpenShift CLI tools (oc and openshift-install).
 *
 * Handles version resolution from channels to specific versions, downloads
 * binaries with checksum verification, and installs to user's local bin directory.
 * Falls back to alternative mirrors if primary download fails.
 *
 * Note: Helm is no longer automatically installed. Use openshiftTools.installHelm()
 * separately if needed, or it will be installed automatically when deploying PMM.
 *
 * @param config Map containing installation configuration:
 *   - openshiftVersion: Version to install (required, e.g., '4.16.20', 'latest', 'stable-4.16')
 *   - baseUrl: Override base download URL (optional, default: 'https://mirror.openshift.com/pub/openshift-v4/clients/ocp')
 *
 * @return String resolved OpenShift version number (e.g., '4.16.20')
 *
 * @throws IllegalArgumentException When openshiftVersion is missing
 * @throws RuntimeException When download fails, checksums don't match, or network errors occur
 *
 * @since 1.0.0
 *
 * @example
 * def version = openshiftTools.install([
 *     openshiftVersion: 'stable-4.16'
 * ])
 * println "Installed OpenShift ${version}"
 */
def install(Map config) {
    def params = [
        baseUrl: 'https://mirror.openshift.com/pub/openshift-v4/clients/ocp'
    ] + config

    if (!params.openshiftVersion) {
        error 'Missing required parameter: openshiftVersion'
    }

    log('INFO', "Installing OpenShift tools version: ${params.openshiftVersion}", params)

    try {
        def resolvedVersion = resolveVersion(params.openshiftVersion, params.baseUrl)
        log('DEBUG', "Resolved OpenShift version: ${resolvedVersion}", params)

        def downloadUrl = "${params.baseUrl}/${resolvedVersion}"

        // Fetch checksums using native Groovy
        log('DEBUG', 'Fetching OpenShift checksums...')
        def checksumUrl = "${downloadUrl}/sha256sum.txt"
        def checksumMap = [:]

        try {
            // Fetch and parse the sha256sum.txt file for integrity verification
            // Format: <checksum>  <filename>
            // Example content:
            //   7549bd34267d297d86d8cfeb35e777bec62382ccddf1e4872e77fb9dbb1bea03  openshift-install-linux-4.16.20.tar.gz
            //   7762c0ad23897772887f0d74e7593005565738fa660765a7e8110974fa4a12d8  openshift-client-linux-4.16.20.tar.gz
            def checksums = new URL(checksumUrl).text
            checksums.split('\n').each { line ->
                if (line.trim()) {
                    def parts = line.split(/\s+/)
                    if (parts.length >= 2) {
                        // Map filename to its SHA256 checksum for verification
                        checksumMap[parts[1]] = parts[0]
                    }
                }
            }
        } catch (Exception e) {
            error("Failed to fetch OpenShift checksums: ${e.message}")
        }

        // Get checksums for required files
        def installerFile = "openshift-install-linux-${resolvedVersion}.tar.gz"
        def clientFile = "openshift-client-linux-${resolvedVersion}.tar.gz"

        def installerChecksum = checksumMap[installerFile]
        def clientChecksum = checksumMap[clientFile]

        if (!installerChecksum || !clientChecksum) {
            error("Failed to find checksums for OpenShift files: installer=${installerChecksum ? 'found' : 'missing'}, client=${clientChecksum ? 'found' : 'missing'}")
        }

        log('DEBUG', "Retrieved checksums - installer: ${installerChecksum}, client: ${clientChecksum}")

        sh """
            # Create tools directory in user's home to avoid requiring sudo permissions
            # Following XDG Base Directory specification
            mkdir -p \$HOME/.local/bin

            # Download and verify OpenShift installer with checksum verification
            echo "Downloading OpenShift installer..."
            curl -sL -o ${installerFile} ${downloadUrl}/${installerFile}
            echo "${installerChecksum}  ${installerFile}" | sha256sum -c -
            tar xzf ${installerFile} -C \$HOME/.local/bin
            rm -f ${installerFile}

            # Download and verify OpenShift CLI (oc) with checksum verification
            echo "Downloading OpenShift CLI..."
            curl -sL -o ${clientFile} ${downloadUrl}/${clientFile}
            echo "${clientChecksum}  ${clientFile}" | sha256sum -c -
            tar xzf ${clientFile} -C \$HOME/.local/bin
            rm -f ${clientFile}

            # Ensure binaries have executable permissions
            chmod +x \$HOME/.local/bin/openshift-install
            chmod +x \$HOME/.local/bin/oc

            # Add to PATH
            export PATH="\$HOME/.local/bin:\$PATH"

            # Verify installation
            echo "Verifying installation..."
            \$HOME/.local/bin/openshift-install version
            \$HOME/.local/bin/oc version --client
        """

        return resolvedVersion
    } catch (Exception e) {
        error "Failed to install OpenShift tools: ${e.message}"
    }
}

/**
 * Resolves OpenShift version aliases to specific version numbers.
 *
 * Handles various version formats including channels, minor versions,
 * and specific versions. Queries OpenShift release API for latest versions.
 *
 * @param version Version specification (required):
 *   - Specific version: '4.16.20'
 *   - Minor version: '4.16' (resolves to latest patch)
 *   - Channel: 'latest', 'stable', 'fast', 'candidate'
 *   - Channel with version: 'stable-4.16', 'eus-4.14'
 * @param baseUrl Base URL for OpenShift mirror (required)
 *
 * @return String specific version number (e.g., '4.16.20')
 *
 * @throws RuntimeException When version cannot be resolved or network errors occur
 *
 * @since 1.0.0
 */
def resolveVersion(String version, String baseUrl) {
    if (version.matches(/^[0-9]+\.[0-9]+\.[0-9]+$/)) {
        // Already a specific version
        return version
    }

    if (version.matches(/^[0-9]+\.[0-9]+$/)) {
        // Minor version specified, get latest patch
        return getLatestPatchVersion(version, baseUrl)
    }

    // Handle channel versions (latest, stable, fast, candidate, eus)
    def channelVersion = getChannelVersion(version)
    if (channelVersion) {
        return channelVersion
    }

    error "Unable to resolve OpenShift version: ${version}"
}

/**
 * Gets the latest patch version for a given minor version.
 *
 * Parses OpenShift mirror directory listing to find available versions
 * and returns the highest patch version. Uses Groovy's built-in URL
 * parsing for reliability.
 *
 * @param minorVersion Minor version (required, e.g., '4.16')
 * @param baseUrl Base URL for OpenShift mirror (required)
 *
 * @return String latest patch version (e.g., '4.16.20')
 *
 * @throws RuntimeException When no versions found or network errors occur
 *
 * @since 1.0.0
 */
def getLatestPatchVersion(String minorVersion, String baseUrl) {
    try {
        // Fetch the directory listing
        def htmlContent = new URL("${baseUrl}/").text

        // Extract version directories using regex
        // Looking for patterns like href="4.16.20/"
        def pattern = ~/href="(${minorVersion}\.[0-9]+)\//
        def versions = []

        htmlContent.eachMatch(pattern) { match ->
            versions << match[1]
        }

        if (versions.isEmpty()) {
            error "No patch versions found for ${minorVersion}"
        }

        // Sort versions using semantic version comparison
        // Splits each version into parts and compares numerically
        versions = versions.sort(false) { a, b ->
            def aParts = a.split('\\.').collect { it as int }
            def bParts = b.split('\\.').collect { it as int }
            [aParts, bParts].transpose().findResult { x, y -> x <=> y ?: null } ?: 0
        }

        return versions.last()
    } catch (Exception e) {
        error "Failed to get latest patch version for ${minorVersion}: ${e.message}"
    }
}

/**
 * Retrieves the current version for an OpenShift release channel.
 *
 * Queries the official OpenShift release API to get the latest
 * version for a given release channel. Supports EUS channels.
 *
 * @param channel Release channel name (required):
 *   - Standard channels: 'latest', 'stable', 'fast', 'candidate'
 *   - EUS channels: 'eus-4.14', 'eus-4.16'
 *   - Channel with version: 'stable-4.16', 'fast-4.15'
 *
 * @return String version number for the channel or null if invalid
 *
 * @throws RuntimeException When API query fails or network errors occur
 *
 * @since 1.0.0
 */
def getChannelVersion(String channel) {
    def validChannels = ['latest', 'stable', 'fast', 'candidate']
    def channelName = channel

    // Handle EUS (Extended Update Support) channels
    if (channel.startsWith('eus-')) {
        channelName = 'eus'
    }

    if (!validChannels.contains(channelName) && channelName != 'eus') {
        return null
    }

    // Get configuration from environment variables or use defaults
    // Versions from 4.16 onwards (can be overridden via environment variable)
    def majorMinorVersions = env.OPENSHIFT_MAJOR_MINOR_VERSIONS?.split(',')?.collect { it.trim() } ?:
        ['4.19', '4.18', '4.17', '4.16']

    // Configurable fallback version
    def fallbackVersion = env.OPENSHIFT_FALLBACK_VERSION ?: '4.16.45'

    try {
        // Map 'latest' to newest stable channel since OpenShift doesn't have a 'latest' channel
        // Iterates through versions from newest to oldest
        if (channel == 'latest') {
            for (def version : majorMinorVersions) {
                def output = sh(
                    script: """
                        curl -sH 'Accept: application/json' https://api.openshift.com/api/upgrades_info/v1/graph?channel=stable-${version} | \
                        jq -r '.nodes | map(.version) | sort | .[-1]' 2>/dev/null || echo ''
                    """,
                    returnStdout: true
                ).trim()

                if (output && output != 'null' && output != '') {
                    return output
                }
            }
        } else {
            // For other channels, collect last 5 patch versions from each minor release
            def allVersions = []

            for (def version : majorMinorVersions) {
                def output = sh(
                    script: """
                        curl -sH 'Accept: application/json' https://api.openshift.com/api/upgrades_info/v1/graph?channel=${channelName}-${version} | \
                        jq -r '.nodes | map(.version) | sort | .[-5:][]' 2>/dev/null || echo ''
                    """,
                    returnStdout: true
                ).trim()

                if (output) {
                    allVersions.addAll(output.split('\n').findAll { it })
                }
            }

            if (allVersions) {
                // Sort versions using semantic version comparison
                allVersions = allVersions.sort(false) { a, b ->
                    def aParts = a.split('\\.').collect { it as int }
                    def bParts = b.split('\\.').collect { it as int }
                    [aParts, bParts].transpose().findResult { x, y -> x <=> y ?: null } ?: 0
                }
                return allVersions.last()
            }
        }
    } catch (Exception e) {
        log('ERROR', "Failed to get channel version: ${e.message}")
    }

    // Return configurable fallback version when API is unavailable
    return fallbackVersion
}

/**
 * Installs Helm 3 if not already present.
 *
 * Required for deploying PMM and other Helm charts to OpenShift.
 * Installs to user's local bin directory without requiring sudo.
 * Verifies checksums for security.
 *
 * @throws RuntimeException When download fails or checksum doesn't match
 *
 * @since 1.0.0
 */
def installHelm() {
    def helmInstalled = sh(
        script: 'command -v helm >/dev/null 2>&1 && echo "true" || echo "false"',
        returnStdout: true
    ).trim()

    if (helmInstalled == 'false') {
        log('INFO', 'Installing Helm...')
        // Install specific Helm version with SHA256 checksum verification
        def helmVersion = env.HELM_VERSION ?: '3.14.0'

        // Fetch checksum dynamically using native Groovy
        log('DEBUG', "Fetching checksum for Helm v${helmVersion}...")
        def checksumUrl = "https://get.helm.sh/helm-v${helmVersion}-linux-amd64.tar.gz.sha256sum"

        def helmChecksum
        try {
            // Parse checksum from official Helm SHA256 file
            def response = new URL(checksumUrl).text
            helmChecksum = response.split(/\s+/)[0].trim()

            if (!helmChecksum || !helmChecksum.matches(/^[a-f0-9]{64}$/)) {
                error("Invalid checksum format received for Helm version ${helmVersion}")
        }

            log('DEBUG', "Retrieved checksum: ${helmChecksum}")
        } catch (Exception e) {
            error("Failed to fetch checksum for Helm version ${helmVersion}. Error: ${e.message}")
    }

        sh """
            # Download Helm binary directly instead of using installer script
            curl -fsSL -o helm.tar.gz https://get.helm.sh/helm-v${helmVersion}-linux-amd64.tar.gz

            # Verify checksum
            echo "${helmChecksum}  helm.tar.gz" | sha256sum -c -

            # Extract helm binary
            tar -xzf helm.tar.gz linux-amd64/helm

            # Install to local bin
            mkdir -p \$HOME/.local/bin
            mv linux-amd64/helm \$HOME/.local/bin/
            chmod +x \$HOME/.local/bin/helm

            # Cleanup
            rm -rf helm.tar.gz linux-amd64/
        """
}

    sh '''
        export PATH="$HOME/.local/bin:$PATH"
        helm version --short
    '''
}

// ============================================================================
// Utility Functions
// ============================================================================

/**
 * Checks the overall status of an OpenShift cluster.
 *
 * @param kubeconfig Path to kubeconfig file
 * @return boolean true if cluster has nodes, false otherwise
 */
def checkClusterStatus(String kubeconfig) {
    try {
        def status = sh(
            script: """
                export KUBECONFIG=${kubeconfig}
                oc get nodes --no-headers | wc -l
            """,
            returnStdout: true
        ).trim()

        return status.toInteger() > 0
    } catch (Exception e) {
        return false
    }
}

/**
 * Waits for an OpenShift cluster to become fully ready.
 * Polls cluster operators until all are available.
 *
 * @param kubeconfig Path to kubeconfig file
 * @param timeoutMinutes Maximum time to wait (default: 45)
 * @return boolean true if cluster is ready, false if timeout
 */
def waitForClusterReady(String kubeconfig, int timeoutMinutes = 45) {
    def endTime = System.currentTimeMillis() + (timeoutMinutes * 60 * 1000)

    while (System.currentTimeMillis() < endTime) {
        if (checkClusterStatus(kubeconfig)) {
            def notReadyNodes = sh(
                script: """
                    export KUBECONFIG=${kubeconfig}
                    oc get nodes --no-headers | grep -E 'NotReady|SchedulingDisabled' | wc -l
                """,
                returnStdout: true
            ).trim()

            if (notReadyNodes == '0') {
                log('INFO', 'All nodes are ready!')
                return true
            }
        }

        log('DEBUG', 'Waiting for cluster to be ready...')
        sleep(30)
    }

    error "Cluster did not become ready within ${timeoutMinutes} minutes"
}

/**
 * Gets the OpenShift version of a running cluster.
 *
 * @param kubeconfig Path to kubeconfig file
 * @return String OpenShift version
 */
def getClusterVersion(String kubeconfig) {
    return sh(
        script: """
            export KUBECONFIG=${kubeconfig}
            oc version -o json | jq -r '.openshiftVersion // .serverVersion.gitVersion'
        """,
        returnStdout: true
    ).trim()
}

/**
 * Lists all nodes in an OpenShift cluster.
 *
 * @param kubeconfig Path to kubeconfig file
 * @return List of node information maps
 */
def getClusterNodes(String kubeconfig) {
    def output = sh(
        script: """
            export KUBECONFIG=${kubeconfig}
            oc get nodes -o json
        """,
        returnStdout: true
    ).trim()

    return readJSON(text: output)
}

/**
 * Determines the status of a cluster based on its data source and resource count.
 *
 * @param cluster Map containing cluster information with source, resource_count, and has_backup fields
 * @return String describing the cluster status
 * @since 1.1.0
 */
def getClusterStatus(Map cluster) {
    if (cluster.source == 'combined') {
        return 'Active (Normal)'
    } else if (cluster.source == 's3' && cluster.resource_count == 0) {
        return 'Deleted (S3 state only)'
    } else if (cluster.source == 'aws-tags' && cluster.has_backup == 'No') {
        return 'Active (Missing S3 backup!)'
    } else {
        return 'Unknown'
    }
}

/**
 * Formats a list of clusters into a readable summary string.
 * Outputs as a single multi-line string for better Jenkins console readability.
 *
 * @param clusters List of cluster maps containing cluster information
 * @param title Optional title for the summary (default: "OPENSHIFT CLUSTERS")
 * @return String formatted cluster summary as a single block
 * @since 1.1.0
 */
def formatClustersSummary(List clusters, String title = "OPENSHIFT CLUSTERS") {
    def summary = new StringBuilder()

    // Header
    summary.append("=" * 80).append("\n")
    summary.append("${title} FOUND: ${clusters.size()}").append("\n")
    summary.append("=" * 80).append("\n\n")

    if (clusters.isEmpty()) {
        summary.append("No OpenShift clusters found in this region\n")
    } else {
        // Format each cluster with improved field names and alignment
        clusters.each { cluster ->
            // Primary info (most important)
            summary.append("Cluster Name:        ${cluster.name}\n")

            // Version and status info
            summary.append("OpenShift Version:   ${cluster.version}\n")
            summary.append("Status:              ${getClusterStatus(cluster)}\n")
            summary.append("PMM Deployed:        ${cluster.pmm_deployed}\n")

            // Metadata
            summary.append("AWS Region:          ${cluster.region}\n")
            summary.append("Created:             ${cluster.created_at}\n")
            summary.append("Created By:          ${cluster.created_by}\n")

            // Technical details
            summary.append("Data Source:         ${cluster.source}\n")
            summary.append("S3 Backup:           ${cluster.has_backup}\n")
            summary.append("Resource Types:      ${cluster.resource_count}\n")

            // Show base name only if it differs (for clusters with random suffixes)
            if (cluster.baseName && cluster.baseName != cluster.name) {
                summary.append("S3 Base Name:        ${cluster.baseName}\n")
            }
            summary.append("-" * 80).append("\n")
        }

        // Legend (more concise)
        summary.append("\nLEGEND:\n")
        summary.append("  Data Source:    combined = S3+AWS (normal) | s3 = S3 only | aws-tags = AWS only\n")
        summary.append("  S3 Backup:      Yes = State saved | No = Missing backup\n")
        summary.append("  Resource Types: Number of AWS resource types for the cluster\n")
    }

    return summary.toString()
}

