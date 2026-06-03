// installTrivy.groovy — Shared Jenkins library function for Trivy installation
//
// Centralizes Trivy version, checksums, and installation logic.
// Update ONLY this file when upgrading Trivy across all pipelines.
//
// Usage in pipelines:
//   installTrivy()                          // auto-detect method & arch
//   installTrivy(method: 'binary')          // force GitHub tar.gz method
//   installTrivy(method: 'apt')             // force APT method
//   installTrivy(method: 'binary', junitTpl: true)   // also download junit.tpl
//   installTrivy(htmlTpl: true)                      // also download html.tpl

def call(Map args = [:]) {
    def TRIVY_VERSION    = "0.71.0"
    def CHECKSUM_AMD64   = "30a3d22b23f88c233f1658f562fb477cae3b3e8b4761109d515b7698daf85814"
    def CHECKSUM_ARM64   = "2561be394a3199c911f82fced606cbc05e1cb23eb6ce1da6935540adb76f4252"

    def method    = args.get('method', 'auto')
    def junitTpl  = args.get('junitTpl', false)
    def htmlTpl   = args.get('htmlTpl', false)

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

    if (htmlTpl) {
        withEnv(["TRIVY_VERSION=${TRIVY_VERSION}"]) {
            sh '''
                declare -A TPL_SHA256=(
                    ["0.71.0"]="d36b637cc77bc1a49b1f9a2358173756cd27a5d3385fab6365c0bab09abe5f0a"
                )

                expected_sha="${TPL_SHA256[$TRIVY_VERSION]:?No pinned html.tpl checksum for Trivy ${TRIVY_VERSION}. Verify and add one before building}"

                if [ ! -f html.tpl ] || ! echo "${expected_sha}  html.tpl" | sha256sum -c - >/dev/null 2>&1; then
                    tmp="$(mktemp)"
                    wget -q -O "$tmp" \
                        "https://raw.githubusercontent.com/aquasecurity/trivy/v${TRIVY_VERSION}/contrib/html.tpl" \
                        || { echo "html.tpl download failed" >&2; rm -f "$tmp"; exit 1; }
                    echo "${expected_sha}  ${tmp}" | sha256sum -c - \
                        || { echo "html.tpl checksum mismatch for v${TRIVY_VERSION} — refusing to use untrusted template" >&2; rm -f "$tmp"; exit 1; }
                    mv "$tmp" html.tpl
                fi
            '''
        }
    }
}