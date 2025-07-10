FROM debian:bullseye-slim

ENV ARCH="amd64"
ENV DEBIAN_FRONTEND=noninteractive
ENV PATH="/root/.local/bin:/root/.venv/bin:$PATH"

# Install base tools
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates curl wget unzip git tar zip \
        python3 python3-venv python3-pip \
        jq gnupg diffutils bc && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# yq
RUN curl -L "https://github.com/mikefarah/yq/releases/latest/download/yq_linux_${ARCH}" -o /usr/local/bin/yq && \
    chmod +x /usr/local/bin/yq

# Install uv
RUN curl -Ls https://astral.sh/uv/install.sh | bash && \
    uv venv .venv

# Helm 3
RUN curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# kubectl
RUN curl -sLO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/${ARCH}/kubectl" && \
    install -m 0755 kubectl /usr/local/bin/kubectl && rm kubectl

# krew + kuttl + assert
RUN curl -fsSLO "https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_${ARCH}.tar.gz" && \
    tar zxvf "krew-linux_${ARCH}.tar.gz" && \
    ./krew-linux_$ARCH install krew && \
    rm -rf "krew-linux_${ARCH}" "krew-linux_${ARCH}.tar.gz"
ENV PATH=$PATH:/root/.krew/bin
RUN kubectl krew install kuttl && \
    kubectl krew install assert

# kubectl-assert
RUN curl -s https://raw.githubusercontent.com/gekoko/kubectl-assert/main/install.sh | bash && \
    mv $HOME/.krew/bin/kubectl-assert /usr/local/bin/ || true

# EKS CLI
RUN curl -sL "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_$(uname -s)_${ARCH}.tar.gz" -o eksctl.tar.gz && \
    tar -xzf eksctl.tar.gz && \
    mv eksctl /usr/local/bin/eksctl && \
    chmod +x /usr/local/bin/eksctl && \
    rm -f eksctl.tar.gz

# Digital Ocean CLI
RUN DO_LATEST=$(curl -s https://api.github.com/repos/digitalocean/doctl/releases/latest | jq -r '.tag_name') && \
    curl -sSL "https://github.com/digitalocean/doctl/releases/download/${DO_LATEST}/doctl-${DO_LATEST#v}-linux-${ARCH}.tar.gz" | tar -xz && \
    mv doctl /usr/local/bin/ && \
    chmod +x /usr/local/bin/doctl

# Google Cloud SDK
RUN GC_LATEST=$(curl -sSL https://dl.google.com/dl/cloudsdk/channels/rapid/components-2.json | jq -r '.version') && \
    curl -sSL "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-${GC_LATEST}-linux-x86_64.tar.gz" | tar -xz && \
    ./google-cloud-sdk/install.sh --quiet
ENV PATH="/google-cloud-sdk/bin:$PATH"

# Azure CLI
RUN curl -sL https://aka.ms/InstallAzureCLIDeb | bash

# Enforce bash by default
RUN ln -sf /bin/bash /bin/sh
ENTRYPOINT ["/bin/bash", "-c"]