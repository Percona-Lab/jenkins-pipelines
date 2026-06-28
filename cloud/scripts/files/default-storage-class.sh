#!/usr/bin/env bash
set -euo pipefail

DEFAULT_STORAGE_CLASS="${1:-longhorn}"

echo "Ensuring only '${DEFAULT_STORAGE_CLASS}' is the default StorageClass"

for sc in $(kubectl get storageclass -o jsonpath='{.items[*].metadata.name}'); do
    if [[ "$sc" != "$DEFAULT_STORAGE_CLASS" ]]; then
        echo "Removing default annotation from StorageClass: $sc"
        kubectl annotate storageclass "$sc" \
            storageclass.kubernetes.io/is-default-class- \
            --overwrite || true
    fi
done

echo "Setting '${DEFAULT_STORAGE_CLASS}' as default StorageClass"
kubectl annotate storageclass "$DEFAULT_STORAGE_CLASS" \
    storageclass.kubernetes.io/is-default-class="true" \
    --overwrite

kubectl get storageclass
