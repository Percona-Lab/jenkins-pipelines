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
                local repo="$1"
                curl -fsSL -o /dev/null -w '%{url_effective}' "https://github.com/${repo}/releases/latest" \
                    | awk -F/ '{print $NF}'
            }

            install_if_missing() {
                local bin="$1"
                local repo="$2"
                local url_template="$3"

                if command -v "$bin" >/dev/null 2>&1; then
                    echo "$bin already installed: $($bin --version 2>/dev/null || $bin version)"
                    return
                fi

                local version_var="$(echo "$bin" | tr '[:lower:]' '[:upper:]')_VERSION"
                local version="${!version_var:-$(latest "$repo")}"

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

void installUv() {
    sh '''
        set -euo pipefail
        export PATH="$HOME/.local/bin:$PATH"
        if command -v uv >/dev/null 2>&1; then
            echo "uv already installed: $(uv --version)"
        else
            curl -LsSf https://astral.sh/uv/install.sh | sh
            export PATH="$HOME/.local/bin:$PATH"
        fi
        uv --version
    '''
}

void syncPythonDeps(String sourceDir = 'source') {
    sh """
        set -euo pipefail
        export PATH="\$HOME/.local/bin:\$PATH"
        cd ${sourceDir}
        uv sync --locked
    """
}

void installGoogleCLI() {
    sh '''
        sudo cp cloud/common/files/google-cloud-sdk.repo /etc/yum.repos.d/google-cloud-sdk.repo
        sudo yum install -y google-cloud-cli google-cloud-cli-gke-gcloud-auth-plugin
        command -v gsutil
        gsutil version -l | head -n1
    '''
}

void installPxcTools() {
    sh '''
        # install cfssl for PXC operator tests
        sudo curl -fsSL https://github.com/cloudflare/cfssl/releases/download/v1.6.5/cfssl_1.6.5_linux_amd64 -o /usr/local/bin/cfssl
        sudo curl -fsSL https://github.com/cloudflare/cfssl/releases/download/v1.6.5/cfssljson_1.6.5_linux_amd64 -o /usr/local/bin/cfssljson
        sudo chmod +x /usr/local/bin/cfssl /usr/local/bin/cfssljson

        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable pxb-84-lts
        sudo yum install -y percona-xtrabackup-84
    '''
}
 
void installKuttl(String version = '0.25.0') {
    sh """
        export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"
        command -v kubectl-krew >/dev/null || {
            curl -fsSL https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_amd64.tar.gz | tar -xzf -
            ./krew-linux_amd64 install krew
        }

        command -v kubectl-assert >/dev/null || kubectl krew install assert

        dir="\$(mktemp -d)"
        git clone -q https://github.com/kubernetes-sigs/krew-index.git "\$dir"
        commit=\$(git -C "\$dir" log -S"v${version}" --format='%H' -- plugins/kuttl.yaml | tail -1)
        rm -rf "\$dir"

        kubectl krew install --manifest-url "https://raw.githubusercontent.com/kubernetes-sigs/krew-index/\$commit/plugins/kuttl.yaml"
        kubectl kuttl --version
    """
}

void installOpenshiftClient(String platformVersion) {
    withEnv(["PLATFORM_VER=${platformVersion}"]) {
        sh '''
            curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-client-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - oc
            curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - openshift-install
        '''
    }
}

void installEksctl() {
    sh '''
        curl -sL https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz | sudo tar -C /usr/local/bin -xzf - && sudo chmod +x /usr/local/bin/eksctl
    '''
}

void installDoctl() {
    sh '''
        client_version=$(curl -s https://api.github.com/repos/digitalocean/doctl/releases/latest | grep '"tag_name":' | cut -d '"' -f4 | sed 's/^v//')
        curl -sL "https://github.com/digitalocean/doctl/releases/download/v$client_version/doctl-$client_version-linux-amd64.tar.gz" | tar -xz && sudo mv doctl /usr/local/bin
        doctl version
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

void installExecutorDependencies(String testExecutorType) {
    switch (testExecutorType) {
        case 'kuttl':
            installKuttl()
            break

        case 'make':
            installUv()
            syncPythonDeps()
            break
    }
}

void installProviderDependencies(Map libraries, String operator, String provider) {
    // PSMDB requires Google and Azure CLIs, regardless of the provider.
    // Rancher requires Google CLI to create cluster as GCE instances are used.

    if (provider == 'gcloud' || provider == 'rancher' || operator == 'psmdb-operator') {
        installGoogleCLI()
        libraries.gcloud.auth()
    }

    if (provider == 'azure' || operator == 'psmdb-operator') {
        installAzureCLI()
        libraries.azure.auth()
    }
}

void prepareNode(Map libraries, String testExecutorType,String operator, String provider) {
    install()
    installExecutorDependencies(testExecutorType)
    installProviderDependencies(libraries, operator, provider)
}

return this
