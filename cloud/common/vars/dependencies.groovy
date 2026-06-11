void install(Map config = [:]) {
    withEnv([
        "YQ_VERSION=${config.yqVersion ?: ''}",
        "JQ_VERSION=${config.jqVersion ?: ''}",
        "HELM_VERSION=${config.helmVersion ?: ''}",
        "KUBECTL_VERSION=${config.kubectlVersion ?: ''}"
    ]) {
        sh '''
            set -euo pipefail

            latest() {
                curl -fsSL "https://api.github.com/repos/$1/releases/latest" |
                    grep '"tag_name"' |
                    head -1 |
                    cut -d '"' -f 4
            }

            install_if_missing() {
                local bin="$1"
                local repo="$2"
                local url_template="$3"

                if command -v "$bin" >/dev/null 2>&1; then
                    echo "$bin already installed: $($bin --version 2>/dev/null || $bin version)"
                    return
                fi

                local version
                version="$(latest "$repo")"

                echo "Installing $bin=${version}..."
                local url
                url="${url_template/__VERSION__/$version}"

                sudo curl -fsSL "$url" -o "/usr/local/bin/$bin"
                sudo chmod +x "/usr/local/bin/$bin"
            }

            install_if_missing \
                yq \
                mikefarah/yq \
                "https://github.com/mikefarah/yq/releases/download/__VERSION__/yq_linux_amd64"

            install_if_missing \
                jq \
                jqlang/jq \
                "https://github.com/jqlang/jq/releases/download/__VERSION__/jq-linux64"

            if command -v helm >/dev/null 2>&1; then
                echo "helm already installed: $(helm version --short)"
            else
                HELM_VERSION="${HELM_VERSION:-$(latest helm/helm)}"
                echo "Installing helm=${HELM_VERSION}..."
                curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" |
                    sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm
            fi

            if command -v kubectl >/dev/null 2>&1; then
                echo "kubectl already installed: $(kubectl version --client 2>/dev/null || true)"
            else
                KUBECTL_VERSION="${KUBECTL_VERSION:-$(latest kubernetes/kubernetes)}"
                echo "Installing kubectl=${KUBECTL_VERSION}..."
                sudo curl -fsSL \
                    "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" \
                    -o /usr/local/bin/kubectl
                sudo chmod +x /usr/local/bin/kubectl
            fi

            yq --version
            jq --version
            helm version
            kubectl version --client
        '''
    }
}

void installGoogleCLI() {
    sh '''
        sudo cp cloud/common/files/google-cloud-sdk.repo /etc/yum.repos.d/google-cloud-sdk.repo
        sudo yum install -y google-cloud-cli google-cloud-cli-gke-gcloud-auth-plugin
    '''
}

void installAzureCLI() {
    sh '''
        if ! command -v az &>/dev/null; then
            if [ "$JENKINS_AGENT" = "AWS" ]; then
                curl -s -L https://azurecliprod.blob.core.windows.net/install.py -o install.py
                printf "/usr/azure-cli\\n/usr/bin" | sudo python3 install.py
                sudo /usr/azure-cli/bin/python -m pip install "urllib3<2.0.0" > /dev/null
            else
                echo "Installing Azure CLI for Hetzner instances..."
                sudo rpm --import https://packages.microsoft.com/keys/microsoft.asc
                sudo cp cloud/common/files/azure-cli.repo /etc/yum.repos.d/azure-cli.repo
                sudo dnf install azure-cli -y
            fi
        fi
    '''
}

return this
