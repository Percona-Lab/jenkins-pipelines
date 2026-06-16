// installTrivy.groovy — Shared Jenkins library function for Trivy installation
//
// Centralizes Trivy version, checksums, and installation logic.
// Update ONLY this file when upgrading Trivy across all pipelines.
//
// https://github.com/aquasecurity/trivy/releases
// trivy_0.71.1_Linux-64bit.tar.gz
// trivy_0.71.1_Linux-ARM64.tar.gz
//
// Usage in pipelines:
//   installTrivy()                          // auto-detect method & arch
//   installTrivy(method: 'binary')          // force GitHub tar.gz method
//   installTrivy(method: 'apt')             // force APT method
//   installTrivy(method: 'binary', junitTpl: true)   // also download junit.tpl

def call(Map args = [:]) {
    def TRIVY_VERSION    = "0.71.1"
    def CHECKSUM_AMD64   = "3cbae37cd440cd8676e5ce9207fe460b5641c7579a17e9d00f8894928c41a88d"
    def CHECKSUM_ARM64   = "a7daaee66817d67a4963e8f9ddf15f5238ee021b55d3cd8695b1b7801afd34a7"

    def method    = args.get('method', 'auto')
    def junitTpl  = args.get('junitTpl', false)

    if (method == 'auto') {
        // Detect: use APT on Debian/Ubuntu, binary elsewhere
        def isDebian = sh(script: 'test -f /etc/debian_version', returnStatus: true) == 0
        method = isDebian ? 'apt' : 'binary'
    }

    if (method == 'apt') {
        sh """
            TRIVY_EXPECTED="${TRIVY_VERSION}"
            TRIVY_CURRENT=\$(trivy --version 2>/dev/null | sed -n 's/.*Version: \\([0-9.]*\\).*/\\1/p'); TRIVY_CURRENT=\${TRIVY_CURRENT:-none}
            if [ "\$TRIVY_CURRENT" != "\$TRIVY_EXPECTED" ]; then
                echo "Installing Trivy \$TRIVY_EXPECTED via APT (current: \$TRIVY_CURRENT)..."
                sudo apt-get install -y wget gnupg lsb-release || true
                wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | gpg --dearmor | sudo tee /usr/share/keyrings/trivy.gpg > /dev/null
                echo "deb [signed-by=/usr/share/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb \$(lsb_release -sc) main" | sudo tee /etc/apt/sources.list.d/trivy.list
                sudo apt-get update
                sudo apt-get install -y trivy=${TRIVY_VERSION}
            else
                echo "Trivy \$TRIVY_EXPECTED already installed."
            fi
        """
    } else {
        // Binary download with checksum verification
        sh """
            TRIVY_VERSION="${TRIVY_VERSION}"
            ARCH=\$(uname -m)
            if [ "\$ARCH" = "aarch64" ]; then
                ARCH_NAME="ARM64"
                TRIVY_CHECKSUM="${CHECKSUM_ARM64}"
            else
                ARCH_NAME="64bit"
                TRIVY_CHECKSUM="${CHECKSUM_AMD64}"
            fi

            TRIVY_CURRENT=\$(trivy --version 2>/dev/null | sed -n 's/.*Version: \\([0-9.]*\\).*/\\1/p'); TRIVY_CURRENT=\${TRIVY_CURRENT:-none}
            if [ "\$TRIVY_CURRENT" != "\$TRIVY_VERSION" ]; then
                echo "Installing Trivy \${TRIVY_VERSION} (\${ARCH_NAME}) via binary download (current: \$TRIVY_CURRENT)..."
                wget -q https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz
                echo "\${TRIVY_CHECKSUM}  trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz" | sha256sum -c -
                sudo tar zxf trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz -C /usr/local/bin/
                rm -f trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz
            else
                echo "Trivy \${TRIVY_VERSION} already installed."
            fi
        """
    }

    if (junitTpl) {
        sh """
            if [ ! -f junit.tpl ]; then
                wget -q https://raw.githubusercontent.com/aquasecurity/trivy/v${TRIVY_VERSION}/contrib/junit.tpl
            fi
        """
    }
}
