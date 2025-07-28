// OpenShift tools installation and utilities library

def install(Map config) {
    def params = [
        baseUrl: 'https://mirror.openshift.com/pub/openshift-v4/clients/ocp'
    ] + config

    if (!params.openshiftVersion) {
        error 'Missing required parameter: openshiftVersion'
    }

    echo "Installing OpenShift tools version: ${params.openshiftVersion}"

    try {
        def resolvedVersion = resolveVersion(params.openshiftVersion, params.baseUrl)
        echo "Resolved OpenShift version: ${resolvedVersion}"

        def downloadUrl = "${params.baseUrl}/${resolvedVersion}"

        sh """
            # Create tools directory
            mkdir -p \$HOME/.local/bin

            # Download and extract OpenShift installer
            echo "Downloading OpenShift installer..."
            curl -sL ${downloadUrl}/openshift-install-linux.tar.gz | tar xzf - -C \$HOME/.local/bin

            # Download and extract OpenShift CLI
            echo "Downloading OpenShift CLI..."
            curl -sL ${downloadUrl}/openshift-client-linux.tar.gz | tar xzf - -C \$HOME/.local/bin

            # Make sure binaries are executable
            chmod +x \$HOME/.local/bin/openshift-install
            chmod +x \$HOME/.local/bin/oc
            chmod +x \$HOME/.local/bin/kubectl

            # Add to PATH if not already there
            if [[ ":\$PATH:" != *":\$HOME/.local/bin:"* ]]; then
                export PATH="\$HOME/.local/bin:\$PATH"
            fi

            # Verify installation
            echo "Verifying installation..."
            \$HOME/.local/bin/openshift-install version
            \$HOME/.local/bin/oc version --client
        """

        // Also install AWS CLI v2 if not present
        installAWSCLI()

        // Install Helm if not present
        installHelm()

        return resolvedVersion
    } catch (Exception e) {
        error "Failed to install OpenShift tools: ${e.message}"
    }
}

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

def getLatestPatchVersion(String minorVersion, String baseUrl) {
    def output = sh(
        script: """
            curl -sL ${baseUrl}/ | grep -oE '${minorVersion}\\.[0-9]+/' | \
            sed 's|/||g' | sort -V | tail -1
        """,
        returnStdout: true
    ).trim()

    if (!output) {
        error "No patch versions found for ${minorVersion}"
    }

    return output
}

def getChannelVersion(String channel) {
    def validChannels = ['latest', 'stable', 'fast', 'candidate']
    def channelName = channel

    // Handle eus- prefix
    if (channel.startsWith('eus-')) {
        channelName = 'eus'
    }

    if (!validChannels.contains(channelName) && channelName != 'eus') {
        return null
    }

    try {
        def output = sh(
            script: """
                curl -sL https://api.openshift.com/api/upgrades_info/v1/graph?channel=${channelName}-4.14 | \
                jq -r '.nodes[-1].version' 2>/dev/null || echo ''
            """,
            returnStdout: true
        ).trim()

        if (output && output != 'null') {
            return output
        }
    } catch (Exception e) {
        echo "Failed to get channel version: ${e.message}"
    }

    // Fallback to a known good version
    return '4.14.35'
}

def installAWSCLI() {
    def awsInstalled = sh(
        script: 'command -v aws >/dev/null 2>&1 && echo "true" || echo "false"',
        returnStdout: true
    ).trim()

    if (awsInstalled == 'false') {
        echo 'Installing AWS CLI v2...'
        sh '''
            curl -sL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
            unzip -q awscliv2.zip
            sudo ./aws/install || ./aws/install --install-dir $HOME/.local/aws-cli --bin-dir $HOME/.local/bin
            rm -rf awscliv2.zip aws/
        '''
    }

    sh 'aws --version'
}

def installHelm() {
    def helmInstalled = sh(
        script: 'command -v helm >/dev/null 2>&1 && echo "true" || echo "false"',
        returnStdout: true
    ).trim()

    if (helmInstalled == 'false') {
        echo 'Installing Helm...'
        sh '''
            curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/master/scripts/get-helm-3
            chmod 700 get_helm.sh
            HELM_INSTALL_DIR=$HOME/.local/bin ./get_helm.sh --no-sudo
            rm get_helm.sh
        '''
    }

    sh 'helm version --short'
}

// Utility functions for OpenShift operations
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

def waitForClusterReady(String kubeconfig, int timeoutMinutes = 45) {
    def endTime = System.currentTimeMillis() + (timeoutMinutes * 60 * 1000)

    while (System.currentTimeMillis() < endTime) {
        if (checkClusterStatus(kubeconfig)) {
            def nodesReady = sh(
                script: """
                    export KUBECONFIG=${kubeconfig}
                    oc get nodes --no-headers | grep -v Ready | wc -l
                """,
                returnStdout: true
            ).trim()

            if (nodesReady == '0') {
                echo 'All nodes are ready!'
                return true
            }
        }

        echo 'Waiting for cluster to be ready...'
        sleep(30)
    }

    error "Cluster did not become ready within ${timeoutMinutes} minutes"
}

def getClusterVersion(String kubeconfig) {
    return sh(
        script: """
            export KUBECONFIG=${kubeconfig}
            oc version -o json | jq -r '.openshiftVersion // .serverVersion.gitVersion'
        """,
        returnStdout: true
    ).trim()
}

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

// Backward compatibility
def call(Map config) {
    return install(config)
}
