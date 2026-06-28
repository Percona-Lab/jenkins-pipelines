#!/usr/bin/env bash
set -Eeuo pipefail

if command -v mount.nfs >/dev/null 2>&1; then
    echo "NFS client already installed"
    exit 0
fi

if command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y nfs-utils
elif command -v yum >/dev/null 2>&1; then
    sudo yum install -y nfs-utils
elif command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y nfs-common
else
    echo "Unsupported OS"
    exit 1
fi

which mount.nfs
