def call(Map config = [:]) {
    // Default configuration
    def defaults = [
        openshiftVersion: 'latest',
        installDir: '/usr/local/bin',
        architecture: 'x86_64'
    ]

    // Merge defaults with provided config
    def params = defaults + config

    echo "Installing OpenShift tools for version: ${params.openshiftVersion}"

    // Determine actual version to install
    def actualVersion = determineVersion(params.openshiftVersion)
    echo "Resolved OpenShift version: ${actualVersion}"

    // Install OpenShift tools
    installOpenShiftClient(actualVersion, params.installDir, params.architecture)
    installOpenShiftInstaller(actualVersion, params.installDir, params.architecture)

    // Install additional tools if requested
    if (params.installKubectl != false) {
        installKubectl(params.installDir)
    }

    if (params.installHelm != false) {
        installHelm(params.installDir)
    }

    if (params.installYq != false) {
        installYq(params.installDir)
    }

    if (params.installJq != false) {
        installJq(params.installDir)
    }

    // Verify installations
    verifyInstallations()

    return actualVersion
}

def determineVersion(String requestedVersion) {
    if (requestedVersion == 'latest' || requestedVersion == 'stable') {
        // Get the latest stable version from OpenShift mirror
        try {
            def releaseUrl = new URL('https://mirror.openshift.com/pub/openshift-v4/x86_64/clients/ocp/stable/release.txt')
            def releaseText = releaseUrl.text
            def versionLine = releaseText.readLines().find { it.contains('Version:') }

            if (!versionLine) {
                error 'Failed to find version in release.txt'
            }

            def latestVersion = versionLine.split(/\s+/)[1].trim()

            if (!latestVersion) {
                error 'Failed to determine latest OpenShift version'
            }

            return latestVersion
        } catch (Exception e) {
            error "Failed to fetch latest OpenShift version: ${e.message}"
        }
    }

    // Validate version format
    if (!requestedVersion.matches(/^\d+\.\d+\.\d+$/)) {
        error "Invalid OpenShift version format: ${requestedVersion}. Expected format: X.Y.Z"
    }

    return requestedVersion
}

def installOpenShiftClient(String version, String installDir, String arch) {
    echo "Installing OpenShift client (oc) version ${version}..."

    def platform = 'linux'
    def fullArch = arch == 'x86_64' ? 'x86_64' : 'aarch64'

    sh """
        # Create temp directory
        TEMP_DIR=\$(mktemp -d)
        cd \$TEMP_DIR

        # Download and extract oc
        curl -sL https://mirror.openshift.com/pub/openshift-v4/${fullArch}/clients/ocp/${version}/openshift-client-${platform}.tar.gz | \
        tar -xzf -

        # Install oc
        sudo install -m 755 oc ${installDir}/oc

        # Clean up
        cd -
        rm -rf \$TEMP_DIR

        # Verify installation
        ${installDir}/oc version --client
    """
}

def installOpenShiftInstaller(String version, String installDir, String arch) {
    echo "Installing OpenShift installer version ${version}..."

    def platform = 'linux'
    def fullArch = arch == 'x86_64' ? 'x86_64' : 'aarch64'

    sh """
        # Create temp directory
        TEMP_DIR=\$(mktemp -d)
        cd \$TEMP_DIR

        # Download and extract openshift-install
        curl -sL https://mirror.openshift.com/pub/openshift-v4/${fullArch}/clients/ocp/${version}/openshift-install-${platform}.tar.gz | \
        tar -xzf -

        # Install openshift-install
        sudo install -m 755 openshift-install ${installDir}/openshift-install

        # Clean up
        cd -
        rm -rf \$TEMP_DIR

        # Verify installation
        ${installDir}/openshift-install version
    """
}

def installKubectl(String installDir) {
    echo 'Installing kubectl...'

    sh """
        # Get latest stable version
        KUBECTL_VERSION=\$(curl -L -s https://dl.k8s.io/release/stable.txt)

        # Download kubectl
        curl -LO "https://dl.k8s.io/release/\${KUBECTL_VERSION}/bin/linux/amd64/kubectl"

        # Install kubectl
        sudo install -m 755 kubectl ${installDir}/kubectl
        rm kubectl

        # Verify installation
        ${installDir}/kubectl version --client --output=yaml
    """
}

def installHelm(String installDir) {
    echo 'Installing Helm...'

    sh """
        # Download and install Helm
        curl -fsSL https://get.helm.sh/helm-v3.12.3-linux-amd64.tar.gz | \
        sudo tar -C ${installDir} --strip-components 1 -xzf - linux-amd64/helm

        # Verify installation
        ${installDir}/helm version
    """
}

def installYq(String installDir) {
    echo 'Installing yq...'

    sh """
        # Download and install yq
        sudo curl -fsSL https://github.com/mikefarah/yq/releases/download/v4.44.1/yq_linux_amd64 \
            -o ${installDir}/yq
        sudo chmod +x ${installDir}/yq

        # Verify installation
        ${installDir}/yq --version
    """
}

def installJq(String installDir) {
    echo 'Installing jq...'

    sh """
        # Download and install jq
        sudo curl -fsSL https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux64 \
            -o ${installDir}/jq
        sudo chmod +x ${installDir}/jq

        # Verify installation
        ${installDir}/jq --version
    """
}

def verifyInstallations() {
    echo 'Verifying tool installations...'

    def tools = ['oc', 'openshift-install']
    def failures = []

    tools.each { tool ->
        def result = sh(script: "which ${tool}", returnStatus: true)
        if (result != 0) {
            failures << tool
        } else {
            echo "✓ ${tool} is installed"
        }
    }

    if (failures) {
        error "Failed to install tools: ${failures.join(', ')}"
    }

    echo 'All OpenShift tools installed successfully'
}

// Method to get compatible oc version for a given OpenShift version
def getCompatibleOcVersion(String openshiftVersion) {
    // Extract major.minor version
    def parts = openshiftVersion.tokenize('.')
    if (parts.size() < 2) {
        return openshiftVersion
    }

    def majorMinor = "${parts[0]}.${parts[1]}"

    // Define compatibility mapping
    def compatibility = [
        '4.16': '4.16.0',
        '4.17': '4.17.0',
        '4.18': '4.18.0',
        '4.19': '4.19.0'
    ]

    // Return compatible version or use the same version
    return compatibility[majorMinor] ?: openshiftVersion
}
