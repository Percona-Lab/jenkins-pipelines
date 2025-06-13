FROM rockylinux:9

ENV ARCH="amd64"

# Update & install base packages
RUN dnf -y update && \
    dnf -y install --nodocs wget unzip git curl tar zip which diffutils bc ca-certificates gnupg jq python3 --allowerasing && \
    dnf clean all && \
    curl -L "https://github.com/mikefarah/yq/releases/latest/download/yq_linux_${ARCH}" -o /usr/local/bin/yq && \
    chmod +x /usr/local/bin/yq

# Install uv and dependencies
COPY pyproject.toml pyproject.toml
ENV PATH="/root/.local/bin:/root/.venv/bin:$PATH"
RUN curl -Ls https://astral.sh/uv/install.sh | bash && \
    uv venv .venv && \
    uv pip install -r pyproject.toml

# Install Helm 3 - latest
RUN curl "https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3" | bash

# Install kubectl - latest
RUN curl -sLO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/${ARCH}/kubectl" && \
    install -m 0755 kubectl /usr/local/bin/kubectl && \
    rm kubectl

# Install krew and kuttl - latest
RUN curl -fsSLO "https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_${ARCH}.tar.gz" && \
    tar zxvf "krew-linux_${ARCH}.tar.gz" && \
    ./krew-linux_$ARCH install krew && \
    rm -rf "krew-linux_${ARCH}" "krew-linux_${ARCH}.tar.gz"
ENV PATH=$PATH:/root/.krew/bin
RUN kubectl krew install kuttl &&\
    kubectl krew install assert

# Install kubectl-assert (via krew)
RUN curl -s https://raw.githubusercontent.com/gekoko/kubectl-assert/main/install.sh | bash && \
    mv $HOME/.krew/bin/kubectl-assert /usr/local/bin/ || true

# Install eksctl - latest
RUN curl -sL "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_$(uname -s)_${ARCH}.tar.gz" -o eksctl.tar.gz && \
    tar -xzf eksctl.tar.gz && \
    mv eksctl /usr/local/bin/eksctl && \
    chmod +x /usr/local/bin/eksctl && \
    rm -f eksctl.tar.gz

# Install doctl - latest
RUN DO_LATEST=$(curl -s https://api.github.com/repos/digitalocean/doctl/releases/latest | jq -r '.tag_name') && \
    curl -sSL "https://github.com/digitalocean/doctl/releases/download/${DO_LATEST}/doctl-${DO_LATEST#v}-linux-${ARCH}.tar.gz" | tar -xz && \
    mv doctl /usr/local/bin/ && \
    chmod +x /usr/local/bin/doctl

# Install Google Cloud SDK - latest
RUN GC_LATEST=$(curl -sSL https://dl.google.com/dl/cloudsdk/channels/rapid/components-2.json | jq -r '.version') && \
    curl -sSL "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-${GC_LATEST}-linux-x86_64.tar.gz" | tar -xz && \
    ./google-cloud-sdk/install.sh --quiet

ENV PATH="/google-cloud-sdk/bin:$PATH"

# Install Azure CLI - latest
RUN rpm --import https://packages.microsoft.com/keys/microsoft.asc && \
    curl -o /etc/yum.repos.d/azure-cli.repo https://packages.microsoft.com/config/rhel/9/prod.repo && \
    dnf install -y azure-cli && \
    dnf clean all

ENTRYPOINT ["/bin/bash", "-c"]