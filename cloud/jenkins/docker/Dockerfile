FROM debian:bullseye-slim

ARG TARGETARCH
ENV DEBIAN_FRONTEND=noninteractive
ENV HOME=/home/clouduser
ENV PATH="${HOME}/.local/bin:${HOME}/.venv/bin:${HOME}/.local/bin/google-cloud-sdk/bin:${PATH}"
ENV CLOUDSDK_CORE_DISABLE_PROMPTS=1
WORKDIR "${HOME}"

# Base dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        sudo bash curl wget tar unzip git python3 python3-venv python3-pip jq gnupg ca-certificates docker.io && \
    ln -sf /bin/bash /bin/sh && \
    curl -L "https://github.com/mikefarah/yq/releases/latest/download/yq_linux_${TARGETARCH}" -o /usr/local/bin/yq && chmod +x /usr/local/bin/yq && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Cloud user
RUN useradd -u 1000 -m clouduser && \
    echo "clouduser ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers && \
    mkdir -p "${HOME}/.local/bin" && \
    chown -R clouduser:clouduser /home/clouduser
USER clouduser

# Create a checksum verifier for downloaded files
COPY --chmod=755 install_dependency.sh $HOME/.local/bin/install_dependency

# Install yq, uv, Helm, kubectl, kuttl, kubectl-assert, 
RUN set -euo pipefail && \
    # uv
    UV_LATEST="$(curl -s https://api.github.com/repos/astral-sh/uv/releases/latest | jq -r .tag_name)" && \
    UV_ARCH="${TARGETARCH/amd64/x86_64}" && UV_ARCH="${UV_ARCH/arm64/aarch64}" && \
    install_dependency "https://github.com/astral-sh/uv/releases/download/${UV_LATEST}/uv-${UV_ARCH}-unknown-linux-gnu.tar.gz" && \
    uv --version && \
    # Helm
    HELM_VERSION="$(curl -sSL https://api.github.com/repos/helm/helm/releases/latest | jq -r '.tag_name')" && \
    install_dependency "https://get.helm.sh/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" && \
    helm version --client && \
    # kubectl
    KUBECTL_VERSION="$(curl -L -s https://dl.k8s.io/release/stable.txt)" && \
    install_dependency "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl" && \
    kubectl version --client && \
    # kubectl-assert - has no published checksum
    KUTTL_VERSION="$(curl -s https://api.github.com/repos/kudobuilder/kuttl/releases/latest | jq -r .tag_name)" && \
    KUTTL_ARCH="${TARGETARCH/amd64/x86_64}" && \
    KUTTL_CHECKSUM="https://github.com/kudobuilder/kuttl/releases/download/${KUTTL_VERSION}/checksums.txt" && \
    KUTTL_FILENAME="kubectl-kuttl_${KUTTL_VERSION#v}_linux_${KUTTL_ARCH}" && \
    install_dependency "https://github.com/kudobuilder/kuttl/releases/download/${KUTTL_VERSION}/${KUTTL_FILENAME}" "1" "${KUTTL_CHECKSUM}" && \
    mv "$HOME/.local/bin/$KUTTL_FILENAME" "$HOME/.local/bin/kubectl-kuttl" && \
    kubectl-kuttl --version && \
    # kubectl-assert - has no published checksum
    install_dependency "https://raw.githubusercontent.com/morningspace/kubeassert/master/kubectl-assert.sh" "0" && \
    mv "$HOME/.local/bin/kubectl-assert.sh" "$HOME/.local/bin/kubectl-assert" && \
    kubectl-assert --version

# Install clients: eksctl, doctl, gcloud
RUN set -euo pipefail && \
    # AWS client
    EKS_VERSION=$(curl -s https://api.github.com/repos/eksctl-io/eksctl/releases/latest | jq -r .tag_name) && \
    EKS_CHECKSUM="https://github.com/eksctl-io/eksctl/releases/download/${EKS_VERSION}/eksctl_checksums.txt" && \
    EKS_FILENAME="eksctl_linux_${TARGETARCH}.tar.gz" && \
    install_dependency "https://github.com/eksctl-io/eksctl/releases/download/${EKS_VERSION}/$EKS_FILENAME" "1" "${EKS_CHECKSUM}" "0" && \
    chmod +x "${HOME}/.local/bin/eksctl" && eksctl version && \
    # Digital Ocean client    
    DO_LATEST=$(curl -s https://api.github.com/repos/digitalocean/doctl/releases/latest | jq -r '.tag_name') && \
    DO_CHECKSUM="https://github.com/digitalocean/doctl/releases/download/${DO_LATEST}/doctl-${DO_LATEST#v}-checksums.sha256" && \
    DO_FILENAME="doctl-${DO_LATEST#v}-linux-${TARGETARCH}.tar.gz" && \
    install_dependency "https://github.com/digitalocean/doctl/releases/download/${DO_LATEST}/${DO_FILENAME}" "1" "${DO_CHECKSUM}" "0" && \
    chmod +x "${HOME}/.local/bin/doctl" && doctl version && \
    # Google client
    GC_LATEST=$(curl -sSL https://dl.google.com/dl/cloudsdk/channels/rapid/components-2.json | jq -r '.version') && \
    install_dependency "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-${GC_LATEST}-linux-x86_64.tar.gz" "0" "" "0" && \
    "${HOME}/.local/bin/google-cloud-sdk/install.sh" --quiet && \
    "${HOME}/.local/bin/google-cloud-sdk/bin/gcloud" components install gke-gcloud-auth-plugin --quiet && gcloud --version && \
    # Minikube
    install_dependency "https://storage.googleapis.com/minikube/releases/latest/minikube-linux-${TARGETARCH}" && \
    mv "$HOME/.local/bin/minikube-linux-${TARGETARCH}" "$HOME/.local/bin/minikube" && minikube version

# Azure cli requires root
USER root
RUN echo "Installing Azure client" && \
    curl -sL https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor > /etc/apt/trusted.gpg.d/microsoft.gpg && \
    AZ_REPO=$(grep VERSION_CODENAME= /etc/os-release | cut -d= -f2) && \
    echo "deb [arch=$(dpkg --print-architecture)] https://packages.microsoft.com/repos/azure-cli/ $AZ_REPO main" > /etc/apt/sources.list.d/azure-cli.list && \
    apt-get update && apt-get install -y --no-install-recommends azure-cli && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

USER clouduser
WORKDIR /home/clouduser
ENV PATH="${HOME}/.local/bin:${HOME}/.venv/bin:${HOME}/.local/bin/google-cloud-sdk/bin:${PATH}"
ENTRYPOINT ["/bin/bash", "-c"]