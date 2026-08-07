# Fresh-boot smoke test - native Packer, sibling of the AWS factory's
# smoke/smoke.pkr.hcl.
#
# Boots the just-built candidate snapshot with `skip_create_snapshot` (pure
# boot-test, no new image), connects over plain SSH as root (Hetzner re-injects
# the temp key through cloud-init, proving the candidate re-initialises after
# the bake cleaned cloud-init state), and runs the identity + real PPG-install
# checks as a Packer provisioner. This template ONLY validates. It does NOT
# promote. The caller (scripts/smoke-run.sh, shared by justfile and workflow)
# deletes the candidate only when this build fails AFTER the provisioner's
# SMOKE-PROVISIONER-STARTED sentinel appeared WITHOUT its COMPLETED twin
# (the provisioner began and did not finish: a genuine boot/install failure).
# A failure without the sentinel is transient infrastructure and keeps the
# candidate. Promotion (role -> ppg-package-test) is a SEPARATE retried step
# ONLY after a pass, so a transient promote-label failure can never delete a
# smoke-passed snapshot.
#
#   packer init . && packer build -var candidate_image=123456789 -var os=rocky -var os_major=9 -var arch=x86_64 .

packer {
  required_plugins {
    hcloud = {
      source  = "github.com/hetznercloud/hcloud"
      version = "1.7.2" # pinned exact (supply-chain), matches the refresh template
    }
  }
}

variable "candidate_image" {
  type        = string
  description = "Numeric ID of the candidate snapshot to boot-test (snapshots have no API name; ID lookup is arch-exact)."
}
variable "os" {
  type    = string # "rocky" | "almalinux" | "oraclelinux"
  default = "rocky"
  validation {
    condition     = contains(["rocky", "almalinux", "oraclelinux"], var.os)
    error_message = "Value of os must be rocky, almalinux, or oraclelinux."
  }
}
variable "os_major" { type = string }
variable "arch" { type = string } # x86_64 | arm64
variable "location" {
  type    = string
  default = "fsn1"
}
variable "server_type_x86" {
  type    = string
  default = "cpx32"
}
variable "server_type_arm" {
  type    = string
  default = "cax21"
}

locals {
  server_type = var.arch == "arm64" ? var.server_type_arm : var.server_type_x86
  uname_arch  = var.arch == "arm64" ? "aarch64" : "x86_64"
  # Identity via /etc/os-release ID (release-file names cannot tell EL distros
  # apart once more are added: every clone ships /etc/redhat-release).
  expected_id = var.os == "rocky" ? "rocky" : var.os == "almalinux" ? "almalinux" : "ol"
  # CRB naming is shared by Rocky and Alma ("powertools" on 8, "crb" on 9/10).
  # Oracle names it per major: ol<major>_codeready_builder.
  crb_repo      = var.os == "oraclelinux" ? "ol${var.os_major}_codeready_builder" : var.os_major == "8" ? "powertools" : "crb"
  hostname_arch = replace(var.arch, "_", "-")
}

source "hcloud" "smoke" {
  location             = var.location
  server_type          = local.server_type
  server_name          = "ppg-smoke-${var.os}${var.os_major}-${local.hostname_arch}"
  image                = var.candidate_image
  skip_create_snapshot = true # boot-test only, produce no image
  ssh_username         = "root"
  ssh_timeout          = "10m"

  server_labels = {
    role = "ppg-smoke"
  }

  # The plugin uploads a temporary packer-<uuid> SSH key per build and labels
  # it with ONLY this map: unlabeled keys from a killed run would be invisible
  # to the janitor's selectors and leak forever.
  ssh_keys_labels = {
    role = "ppg-smoke"
  }
}

build {
  name    = "ppg-smoke"
  sources = ["source.hcloud.smoke"]

  # Identity + a real PPG install on a FRESH boot of the candidate. Inline (no
  # external script). HCL interpolates ${var..}/${local..}, shell $(...) passes
  # through, and it runs as root so no sudo.
  provisioner "shell" {
    # Run the inline body under bash (pipefail is a bashism), the inline analog
    # of the refresh template's bash execute_command.
    inline_shebang = "/bin/bash -e"
    inline = [
      "set -euo pipefail",
      # First provisioner output on purpose: proves SSH came up and the
      # provisioner ran. scripts/smoke-run.sh gates the candidate delete on
      # this sentinel (a failure without it is transient infrastructure, never
      # a bad image).
      "echo SMOKE-PROVISIONER-STARTED",
      ". /etc/os-release; [ \"$ID\" = '${local.expected_id}' ] || { echo \"wrong distro ID $ID (want ${local.expected_id})\"; exit 1; }",
      ". /etc/os-release; [ \"$${VERSION_ID%%.*}\" = '${var.os_major}' ] || { echo \"wrong major $VERSION_ID (want ${var.os_major})\"; exit 1; }",
      "[ \"$(uname -m)\" = '${local.uname_arch}' ] || { echo \"wrong arch $(uname -m)\"; exit 1; }",
      # EL10 images ship cloud-init 24.x, which exits 2 ("degraded done") on
      # Hetzner because the IPv4 link-local metadata probe times out even on
      # the pristine official images (key injection still works, via the
      # retried datasource paths). Judge the status TEXT, not the exit code:
      # a boot that reaches done/disabled passes, anything else fails.
      "ci_status=$(cloud-init status --wait 2>/dev/null) || true; echo \"$ci_status\" | grep -qE 'done|disabled' || { echo \"cloud-init not done: $ci_status\"; exit 1; }",
      "dnf -y install dnf-plugins-core",
      # Enable CRB and ASSERT it stuck: some PPG dependencies live there, so a
      # silently missing CRB would fail later with a misleading dnf error.
      "dnf config-manager --set-enabled ${local.crb_repo}",
      "dnf repolist --enabled | grep -q '${local.crb_repo}' || { echo \"CRB repo ${local.crb_repo} did not enable (repolist --enabled has no match)\"; exit 1; }",
      "dnf -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm",
      "percona-release enable-only ppg-17 release",
      "dnf -qy module reset postgresql 2>/dev/null || true",
      "dnf -qy module disable postgresql 2>/dev/null || true",
      "dnf -y install percona-postgresql17-server || dnf -y install percona-ppg-server17",
      "echo \"PPG install ok: $(rpm -q percona-postgresql17-server 2>/dev/null || rpm -qa 'percona-ppg-server*' | head -1)\"",
      "echo SMOKE-PROVISIONER-COMPLETED",
    ]
  }
}
