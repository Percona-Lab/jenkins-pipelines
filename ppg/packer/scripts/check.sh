#!/bin/bash
# Single no-AWS gate for the factory templates: fmt-check, validate every
# os x major x arch combo (plus Rocky seed variants and the sub-templates),
# and the drift guards. Run from ppg/packer. Both `just check` and the
# workflow check job execute THIS file, so the two gates cannot drift apart.
set -euo pipefail

packer fmt -check -diff .
packer fmt -check -diff smoke
packer fmt -check -diff reimage
packer fmt -check -diff bootstrap

packer init .

for os_name in oraclelinux rocky; do
  for major in 8 9 10; do
    for arch in x86_64 arm64; do
      echo "validate ${os_name} ${major} ${arch}"
      packer validate -var "os=${os_name}" -var "os_major=${major}" -var "arch=${arch}" .
    done
  done
done

for major in 8 9 10; do
  for arch in x86_64 arm64; do
    echo "validate rocky seed ${major} ${arch}"
    packer validate -var os=rocky -var seed=true -var "os_major=${major}" -var "arch=${arch}" .
  done
done

(
  cd smoke
  packer init .
  packer validate -var candidate_ami=ami-00000000000000000 -var os=oraclelinux -var os_major=9 -var arch=x86_64 .
  packer validate -var candidate_ami=ami-00000000000000000 -var os=rocky -var os_major=8 -var arch=x86_64 .
)

(
  cd reimage
  packer init .
  packer validate -var arch=x86_64 .
)

(
  cd bootstrap
  packer init .
  packer validate -var raw_ami=ami-00000000000000000 -var arch=x86_64 .
)

# Drift guards: root_gib must equal var.volume_size in BOTH the refresh and
# reimage templates, else a reimaged base would be the wrong root size for the
# refresh to launch.
root_gib=$(awk -F'"' '/^root_gib[[:space:]]*:=/{print $2; exit}' justfile)
refresh_size=$(awk '/variable "volume_size"/{f=1} f&&/default/{gsub(/[^0-9]/,"");print;exit}' refresh.pkr.hcl)
reimage_size=$(awk '/variable "volume_size"/{f=1} f&&/default/{gsub(/[^0-9]/,"");print;exit}' reimage/reimage-ol10.pkr.hcl)

if [[ -z "${root_gib}" || "${root_gib}" != "${refresh_size}" || "${root_gib}" != "${reimage_size}" ]]; then
  echo "DRIFT: root_gib=${root_gib} != refresh=${refresh_size} / reimage=${reimage_size}" >&2
  exit 1
fi

# Packer HCL escapes ONLY $${ (and %%{). A bare $$ before anything else passes
# through literally and the target shell then expands $$ to its process id,
# which broke the smoke identity check once. Fail on any $$ not followed by {.
for template in refresh.pkr.hcl smoke/smoke.pkr.hcl reimage/reimage-ol10.pkr.hcl bootstrap/finalize-ol10.pkr.hcl; do
  # shellcheck disable=SC2016  # the regex must stay literal, no expansion wanted
  if grep -nE '\$\$([^{]|$)' "${template}"; then
    echo "DRIFT: bare \$\$ in ${template} renders literally and the shell expands it to a PID; use \$\${...} for shell expansions" >&2
    exit 1
  fi
done

# The amazon plugin must be pinned to ONE version across every template, so a
# supply-chain bump is all-or-nothing instead of drifting silently per file.
amazon_pin() {
  awk '/hashicorp\/amazon/{f=1} f&&/version =/{gsub(/[^0-9.]/,"");print;exit}' "$1"
}

pins=""

for template in refresh.pkr.hcl smoke/smoke.pkr.hcl reimage/reimage-ol10.pkr.hcl bootstrap/finalize-ol10.pkr.hcl; do
  pins+="$(amazon_pin "${template}")"$'\n'
done

pin_count=$(printf '%s' "${pins}" | grep -c .)
unique_pins=$(printf '%s' "${pins}" | sort -u | grep -c .)

if [[ "${pin_count}" -ne 4 || "${unique_pins}" -ne 1 ]]; then
  echo "DRIFT: amazon plugin pin not uniform across the 4 templates:" >&2
  printf '%s' "${pins}" >&2
  exit 1
fi

echo "check OK"
