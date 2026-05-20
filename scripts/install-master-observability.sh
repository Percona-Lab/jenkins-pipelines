#!/bin/bash
# install-master-observability.sh
#
# Back-compat shim. The canonical script now lives in
# Percona/percona-cd-platform/scripts/install-master-observability.sh
# alongside the rest of the alloy-gateway + AlloyGatewayBearerRead
# IAM contract that this script depends on (one PR per platform
# change instead of two).
#
# Existing CFN stacks pin to a specific SHA of this file and fetch
# it from GitHub raw; those URLs serve immutable content by SHA and
# continue working unchanged. Anyone fetching at master HEAD gets
# this shim, which curls the canonical version from the platform
# repo and pipes it to bash. JENKINS_HOST and any other caller env
# vars pass through.
#
# When the last CFN-managed Jenkins master is migrated to Terraform
# (Percona/percona-cd-platform), this file can be deleted.

set -euo pipefail

CANONICAL_URL="${CANONICAL_URL:-https://raw.githubusercontent.com/percona/percona-cd-platform/6f5346eadc4bfa44e6d86d73ceb62ce7efbb7b79/scripts/install-master-observability.sh}"

exec bash -c "$(curl -fsSL --retry 5 "$CANONICAL_URL")"
