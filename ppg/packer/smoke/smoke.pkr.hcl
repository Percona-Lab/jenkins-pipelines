# Fresh-boot smoke test - NATIVE Packer (replaces the custom
# smoke-boot.sh run-instances/ssm-send-command/poll orchestration).
#
# Boots the just-built candidate AMI with `skip_create_ami` (pure boot-test, no
# new image), connects over Session Manager (no SSH/SG), and runs the identity +
# real PPG-install checks as a Packer provisioner. This template ONLY validates;
# it does NOT promote. The caller (justfile / workflow) deregisters the candidate
# if this build fails, then promotes it (role -> ppg-package-test) in a SEPARATE
# retried step ONLY after a pass, so a transient promote-tag failure can never
# delete a smoke-passed AMI.
#
#   packer init . && packer build -var candidate_ami=ami-... -var os=rocky -var os_major=9 -var arch=x86_64 .

packer {
  required_plugins {
    amazon = {
      source  = "github.com/hashicorp/amazon"
      version = "1.8.1" # pinned exact (supply-chain); matches the refresh template
    }
  }
}

variable "candidate_ami" { type = string }
variable "os" {
  type    = string # "oraclelinux" | "rocky"
  default = "oraclelinux"
  validation {
    condition     = contains(["oraclelinux", "rocky"], var.os)
    error_message = "Value of os must be oraclelinux or rocky."
  }
}
variable "os_major" { type = string }
variable "arch" { type = string } # x86_64 | arm64
variable "region" {
  type    = string
  default = "eu-central-1"
}
variable "subnet_id" {
  type    = string
  default = "subnet-068170595951ab3a9"
}
variable "builder_instance_profile" {
  type    = string
  default = "ppg-ami-builder-ssm"
}
variable "builder_security_group_name" {
  type    = string
  default = "ppg-ami-factory-builder"
}

locals {
  instance_type = var.arch == "arm64" ? "t4g.large" : "t3.large"
  uname_arch    = var.arch == "arm64" ? "aarch64" : "x86_64"
  # Identity via /etc/os-release ID (release-file names cannot tell EL distros
  # apart once more are added: every clone ships /etc/redhat-release).
  expected_id = var.os == "rocky" ? "rocky" : "ol"
  # Rocky images use "rocky" as the cloud-init default user, OL uses ec2-user.
  ssh_user = var.os == "rocky" ? "rocky" : "ec2-user"
  # CRB naming differs per distro: OL "ol<major>_codeready_builder", Rocky "powertools" (8) / "crb" (9/10).
  crb_repo = var.os == "rocky" ? (var.os_major == "8" ? "powertools" : "crb") : "ol${var.os_major}_codeready_builder"
}

source "amazon-ebs" "smoke" {
  region        = var.region
  instance_type = local.instance_type
  source_ami    = var.candidate_ami
  # ami_name is required by the builder even though we create nothing.
  ami_name                    = "ppg-smoke-noop-${var.os}-${var.os_major}-${var.arch}"
  skip_create_ami             = true # boot-test only; produce no image
  communicator                = "ssh"
  ssh_username                = local.ssh_user
  ssh_interface               = "session_manager"
  ssh_timeout                 = "10m"
  iam_instance_profile        = var.builder_instance_profile
  skip_profile_validation     = true # OIDC role has PassRole only, not iam:GetInstanceProfile
  subnet_id                   = var.subnet_id
  associate_public_ip_address = true
  # Same pre-created no-ingress SG as the builder (disables Packer's temp SG).
  security_group_filter {
    filters = {
      "group-name" = var.builder_security_group_name
    }
  }
  run_tags = {
    Name            = "ppg-smoke"
    iit-billing-tag = "ppg-ami-factory"
  }
}

build {
  name    = "ppg-smoke"
  sources = ["source.amazon-ebs.smoke"]

  # Identity + a real PPG install on a FRESH boot of the candidate. Inline (no
  # external script). HCL interpolates ${var..}/${local..}; shell $(...) passes through.
  provisioner "shell" {
    inline = [
      "set -euo pipefail",
      ". /etc/os-release; [ \"$ID\" = '${local.expected_id}' ] || { echo \"wrong distro ID $ID (want ${local.expected_id})\"; exit 1; }",
      ". /etc/os-release; [ \"$${VERSION_ID%%.*}\" = '${var.os_major}' ] || { echo \"wrong major $VERSION_ID (want ${var.os_major})\"; exit 1; }",
      "[ \"$(uname -m)\" = '${local.uname_arch}' ] || { echo \"wrong arch $(uname -m)\"; exit 1; }",
      "sudo cloud-init status --wait 2>/dev/null | grep -qE 'done|disabled' || { echo 'cloud-init not done'; exit 1; }",
      "rpm -q amazon-ssm-agent >/dev/null || { echo 'ssm-agent not baked'; exit 1; }",
      "sudo dnf -y install dnf-plugins-core || true",
      "sudo dnf config-manager --set-enabled ${local.crb_repo} 2>/dev/null || true",
      "sudo dnf -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm",
      "sudo percona-release enable-only ppg-17 release",
      "sudo dnf -qy module reset postgresql 2>/dev/null || true",
      "sudo dnf -qy module disable postgresql 2>/dev/null || true",
      "sudo dnf -y install percona-postgresql17-server || sudo dnf -y install percona-ppg-server17",
      "echo \"PPG install ok: $(rpm -q percona-postgresql17-server 2>/dev/null || rpm -qa 'percona-ppg-server*' | head -1)\"",
    ]
  }
}
