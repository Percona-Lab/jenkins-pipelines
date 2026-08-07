#!/bin/bash
# Single no-API gate for the factory templates: fmt-check, validate every
# os x major x arch combo (refresh + seed variants and the smoke template),
# and the drift guards. Run from ppg/packer-hetzner. Both `just check` and the
# workflow check job execute THIS file, so the two gates cannot drift apart.
set -euo pipefail

# The hcloud plugin refuses an empty token even for offline work (its config
# Prepare errors before any API call, unlike the amazon plugin), so export an
# inert placeholder when no real token is present: fmt and validate never call
# the API, and the placeholder never leaves this process.
export HCLOUD_TOKEN="${HCLOUD_TOKEN:-offline-validate-placeholder}"

packer fmt -check -diff .
packer fmt -check -diff smoke

packer init .

for os_name in rocky almalinux oraclelinux; do
  for major in 8 9 10; do
    for arch in x86_64 arm64; do
      echo "validate ${os_name} ${major} ${arch}"
      packer validate -var "os=${os_name}" -var "os_major=${major}" -var "arch=${arch}" .
      echo "validate ${os_name} ${major} ${arch} seed"
      packer validate -var "os=${os_name}" -var seed=true -var "os_major=${major}" -var "arch=${arch}" .
    done
  done
done

(
  cd smoke
  packer init .
  packer validate -var candidate_image=100000000 -var os=rocky -var os_major=9 -var arch=x86_64 .
  packer validate -var candidate_image=100000000 -var os=almalinux -var os_major=8 -var arch=arm64 .
  packer validate -var candidate_image=100000000 -var os=oraclelinux -var os_major=9 -var arch=x86_64 .
)

# Drift guard: the promoted role literal must be identical in the justfile
# (role_prod), the refresh template's lineage selector, the promote and prune
# scripts, the workflow promote step's summary mapping, and the Jenkins env
# var (vars/moleculeEnvPPGHetzner.groovy, whose selector documentation names
# the promoted role + source=factory), else a promoted snapshot would never be
# selected (or never retained) by one of them. The test role keeps the same
# inline env-to-role copies as the AWS factory, guarded over its own consumers.
role_prod=$(awk -F'"' '/^role_prod[[:space:]]*:=/{print $2; exit}' justfile)
role_test=$(awk -F'"' '/^role_test[[:space:]]*:=/{print $2; exit}' justfile)

if [[ -z "${role_prod}" || -z "${role_test}" ]]; then
  echo "DRIFT: role_prod/role_test not found in justfile" >&2
  exit 1
fi

for consumer in refresh.pkr.hcl scripts/promote.sh scripts/prune.sh \
  ../../vars/moleculeEnvPPGHetzner.groovy ../../.github/workflows/ppg-hcloud-factory.yml; do
  if ! grep -q -- "role=${role_prod}\|\"${role_prod}\"" "${consumer}"; then
    echo "DRIFT: promoted role '${role_prod}' (justfile role_prod) missing from ${consumer}" >&2
    exit 1
  fi
done

for consumer in scripts/promote.sh scripts/prune.sh ../../.github/workflows/ppg-hcloud-factory.yml; do
  if ! grep -q -- "role=${role_test}\|\"${role_test}\"" "${consumer}"; then
    echo "DRIFT: test role '${role_test}' (justfile role_test) missing from ${consumer}" >&2
    exit 1
  fi
done

# The retention count lives in three places: the justfile keep_last default,
# prune.sh's own default, and the literal the workflow prune job passes.
keep_last=$(grep -oP 'keep_last\s+:=\s+"\K[0-9]+' justfile)

if ! grep -q "keep=\"\${3:-${keep_last}}\"" scripts/prune.sh; then
  echo "DRIFT: prune.sh default keep does not match justfile keep_last (${keep_last})" >&2
  exit 1
fi

if ! grep -q "prune.sh prod 1 ${keep_last}" ../../.github/workflows/ppg-hcloud-factory.yml; then
  echo "DRIFT: workflow prune job does not pass justfile keep_last (${keep_last})" >&2
  exit 1
fi

# Packer HCL escapes ONLY $${ (and %%{). A bare $$ before anything else passes
# through literally and the target shell then expands $$ to its process id,
# which broke the AWS smoke identity check once. Fail on any $$ not followed by {.
for template in refresh.pkr.hcl smoke/smoke.pkr.hcl; do
  # shellcheck disable=SC2016  # the regex must stay literal, no expansion wanted
  if grep -nE '\$\$([^{]|$)' "${template}"; then
    echo "DRIFT: bare \$\$ in ${template} renders literally and the shell expands it to a PID; use \$\${...} for shell expansions" >&2
    exit 1
  fi
done

# The hcloud plugin must be pinned to ONE version across both templates, so a
# supply-chain bump is all-or-nothing instead of drifting silently per file.
hcloud_pin() {
  awk '/hetznercloud\/hcloud/{f=1} f&&/version =/{gsub(/[^0-9.]/,"");print;exit}' "$1"
}

pins=""

for template in refresh.pkr.hcl smoke/smoke.pkr.hcl; do
  pins+="$(hcloud_pin "${template}")"$'\n'
done

pin_count=$(printf '%s' "${pins}" | grep -c .)
unique_pins=$(printf '%s' "${pins}" | sort -u | grep -c .)

if [[ "${pin_count}" -ne 2 || "${unique_pins}" -ne 1 ]]; then
  echo "DRIFT: hcloud plugin pin not uniform across the 2 templates:" >&2
  printf '%s' "${pins}" >&2
  exit 1
fi

echo "check OK"
