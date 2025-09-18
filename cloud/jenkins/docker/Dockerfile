FROM debian:bullseye-slim

ARG TARGETARCH
ENV DEBIAN_FRONTEND=noninteractive
ENV HOME=/home/jenkins
ENV PATH="$HOME/.local/bin:$HOME/.venv/bin:$HOME/google-cloud-sdk/bin:$PATH"
ENV CLOUDSDK_CORE_DISABLE_PROMPTS=1
WORKDIR $HOME

# Base dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        sudo bash curl wget tar unzip git python3 python3-venv python3-pip jq gnupg ca-certificates docker.io && \
    ln -sf /bin/bash /bin/sh && \
    curl -L "https://github.com/mikefarah/yq/releases/latest/download/yq_linux_${TARGETARCH}" -o /usr/local/bin/yq && chmod +x /usr/local/bin/yq && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Jenkins user
RUN useradd -u 1000 -m jenkins && \
    echo "jenkins ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers && \
    mkdir -p /home/jenkins && \
    chown -R jenkins:jenkins /home/jenkins
USER jenkins

# Install yq, uv, Helm, kubectl, kuttl, kubectl-assert, 
RUN set -euo pipefail && \
    # uv
    curl -Ls https://astral.sh/uv/install.sh | bash && uv venv "$HOME/.venv" && \
    # Helm
    HELM_VERSION="$(curl -sSL https://api.github.com/repos/helm/helm/releases/latest | jq -r '.tag_name')" && \
    curl -fsSL -o helm.tar.gz "https://get.helm.sh/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" && \
    tar -xzf helm.tar.gz && mv linux-${TARGETARCH}/helm "$HOME/.local/bin/helm" && rm -rf helm.tar.gz "linux-${TARGETARCH}" && helm version --client && \
    # kubectl
    KUBECTL_VERSION="$(curl -L -s https://dl.k8s.io/release/stable.txt)" && \
    curl -sLO "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl" && \
    install -m 0755 kubectl "$HOME/.local/bin/kubectl" && rm kubectl && kubectl version --client && \
    # kuttl
    KUTTL_VERSION="$(curl -s https://api.github.com/repos/kudobuilder/kuttl/releases/latest | jq -r .tag_name)" && \
    KUTTL_ARCH="${TARGETARCH/amd64/x86_64}" && \
    curl -sLo kubectl-kuttl "https://github.com/kudobuilder/kuttl/releases/download/${KUTTL_VERSION}/kubectl-kuttl_${KUTTL_VERSION#v}_linux_${KUTTL_ARCH}" && \
    chmod +x kubectl-kuttl && mv kubectl-kuttl "$HOME/.local/bin/" && kubectl kuttl --version && \
    # kubectl-assert
    curl -Lo kubectl-assert https://raw.githubusercontent.com/morningspace/kubeassert/master/kubectl-assert.sh && \
    chmod +x kubectl-assert && mv kubectl-assert "$HOME/.local/bin/" && kubectl assert --version

# Install clients: eksctl, doctl, gcloud
RUN set -euo pipefail && \
    echo "Installing AWS client" && \
    EKS_VERSION=$(curl -s https://api.github.com/repos/eksctl-io/eksctl/releases/latest | jq -r .tag_name) && \
    curl -sL "https://github.com/eksctl-io/eksctl/releases/download/${EKS_VERSION}/eksctl_$(uname -s)_${TARGETARCH}.tar.gz" | tar -xz && \
    mv eksctl "$HOME/.local/bin/eksctl" && chmod +x "$HOME/.local/bin/eksctl" && eksctl version && \
    echo "Installing Digital Ocean client" && \
    DO_LATEST=$(curl -s https://api.github.com/repos/digitalocean/doctl/releases/latest | jq -r '.tag_name') && \
    curl -sSL "https://github.com/digitalocean/doctl/releases/download/${DO_LATEST}/doctl-${DO_LATEST#v}-linux-${TARGETARCH}.tar.gz" | tar -xz && \
    mv doctl "$HOME/.local/bin/" && chmod +x "$HOME/.local/bin/doctl" && doctl version && \
    echo "Installing Google client" && \
    GC_LATEST=$(curl -sSL https://dl.google.com/dl/cloudsdk/channels/rapid/components-2.json | jq -r '.version') && \
    curl -sSL "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-${GC_LATEST}-linux-x86_64.tar.gz" | tar -xz && \
    ./google-cloud-sdk/install.sh --quiet && \
    ./google-cloud-sdk/bin/gcloud components install gke-gcloud-auth-plugin --quiet && gcloud --version

# Azure cli requires root
USER root
RUN echo "Installing Azure client" && \
    curl -sL https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor > /etc/apt/trusted.gpg.d/microsoft.gpg && \
    AZ_REPO=$(grep VERSION_CODENAME= /etc/os-release | cut -d= -f2) && \
    echo "deb [arch=$(dpkg --print-architecture)] https://packages.microsoft.com/repos/azure-cli/ $AZ_REPO main" > /etc/apt/sources.list.d/azure-cli.list && \
    apt-get update && apt-get install -y --no-install-recommends azure-cli && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

USER jenkins
WORKDIR /home/jenkins
ENV PATH="$HOME/.local/bin:$HOME/.venv/bin:$HOME/google-cloud-sdk/bin:$PATH"
ENTRYPOINT ["/bin/bash", "-c"]