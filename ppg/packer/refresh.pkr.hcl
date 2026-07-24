# EL package-test AMI factory (refresh path): Oracle Linux + Rocky Linux.
#
# Rebakes a current package-test target AMI from the latest self-owned base of
# the same os+major+arch: launch -> dnf update -> validate (fail-closed) ->
# snapshot. Replaces the manual launch/dnf/snapshot toil and chains forward
# (each build's output is the next build's source).
#
# Usage (one combo per invocation):
#   packer init .
#   packer build -var os=oraclelinux -var os_major=9 -var arch=x86_64 .
#   packer build -var os=rocky -var os_major=9 -var arch=arm64 .
#
# Lineage roots (the first self-owned base each refresh chain grows from):
#   - OL8/9: pre-existing self-owned bases. OL10: scripts/bootstrap-ol10.sh
#     (imports Oracle's official cloud image, no OL10 AMI exists on AWS).
#   - Rocky 8/9/10: seed once per major+arch with `-var seed=true`, which
#     sources the official Rocky community AMI instead of the self-owned
#     lineage. Once the seed is promoted, Rocky refreshes here exactly like OL.

packer {
  required_plugins {
    amazon = {
      source = "github.com/hashicorp/amazon"
      # Pinned exact (supply-chain): the float ">= 1.8.1" let `packer init` pull newer
      # plugin code under AWS creds; 1.8.1 is the latest release. 1.7.0 had a
      # session_manager panic (plugin #628), fixed in 1.8.0; 1.8.1 fixed instance-
      # profile propagation races. Bump deliberately.
      version = "1.8.1"
    }
  }
}

variable "region" {
  type    = string
  default = "eu-central-1"
}

variable "subnet_id" {
  type    = string
  default = "subnet-068170595951ab3a9" # default-VPC public subnet, eu-central-1a
}

variable "os_major" {
  type    = string # "8" | "9" | "10"
  default = "9"
  validation {
    condition = contains(["8", "9", "10"], var.os_major)
    # OL10 refreshes from the bootstrapped base (scripts/bootstrap-ol10.sh) once it
    # is tagged role=ppg-package-test; OL8/9 refresh from their existing bases.
    error_message = "Value of os_major must be 8, 9, or 10."
  }
}

variable "arch" {
  type    = string # "x86_64" | "arm64"
  default = "x86_64"
  validation {
    condition     = contains(["x86_64", "arm64"], var.arch)
    error_message = "Value of arch must be x86_64 or arm64."
  }
}

variable "os" {
  type    = string # "oraclelinux" | "rocky"
  default = "oraclelinux"
  validation {
    condition     = contains(["oraclelinux", "rocky"], var.os)
    error_message = "Value of os must be oraclelinux or rocky."
  }
}

variable "seed" {
  type        = bool
  default     = false
  description = "First bake per combo (Rocky only): source the official upstream AMI instead of the self-owned lineage base. After the seed is promoted, the normal refresh takes over."
}

variable "volume_size" {
  type    = number
  default = 20
}

variable "builder_instance_profile" {
  type        = string
  default     = "ppg-ami-builder-ssm"
  description = "IAM instance profile (AmazonSSMManagedInstanceCore) for the builder, so Packer connects over Session Manager (no inbound SSH)."
}

variable "builder_security_group_name" {
  type        = string
  default     = "ppg-ami-factory-builder"
  description = "Name of the pre-created (terraform-managed) egress-only SG; supplying it disables Packer's temporary SG so the OIDC role needs no SG-create perms."
}

variable "env" {
  type        = string
  default     = "prod"
  description = "prod = real factory tags the pg.cd consumer selects; test = temporary isolated tags (role=ppg-test-*, source=factory-test) so test bakes are never consumed by production."
  validation {
    condition     = contains(["prod", "test"], var.env)
    error_message = "Value of env must be prod or test."
  }
}

locals {
  instance_type    = var.arch == "arm64" ? "t4g.large" : "t3.large"
  uname_arch       = var.arch == "arm64" ? "aarch64" : "x86_64"
  ssm_arch         = var.arch == "arm64" ? "arm64" : "amd64"
  root_volume_size = var.volume_size
  ts               = formatdate("YYYYMMDD-hhmmss", timestamp())
  # Test bakes carry isolated tags so the production pg.cd consumer (which selects
  # role=ppg-package-test, source=factory) never picks them up.
  candidate_role = var.env == "test" ? "ppg-test-candidate" : "ppg-candidate"
  src_tag        = var.env == "test" ? "factory-test" : "factory"
  os_short       = var.os == "rocky" ? "Rocky" : "OL"
  # Rocky cloud images create "rocky" as the cloud-init default user (no
  # ec2-user), and the temp Packer key lands on the default user. Keeping it
  # also keeps the baked image drop-in for the molecule scenarios, which
  # already log in as "rocky" on rocky-* platforms.
  ssh_user    = var.os == "rocky" ? "rocky" : "ec2-user"
  name_prefix = var.env == "test" ? "TEST-${local.os_short}" : local.os_short
  ami_name    = "${local.name_prefix}${var.os_major}-${var.arch}-${local.ts}"

  # Seed source: the official Rocky community AMIs (owner 792107900819) are free
  # of software fees and product codes, so they can root the lineage directly.
  # include_deprecated is required for Rocky 8: in eu-central-1 the newest
  # community-owner Rocky 8 AMI is 8.10-20240528, AWS-deprecated since
  # 2026-05-31 (newer refreshes reached other regions or only Marketplace,
  # whose product codes would propagate into derived AMIs). Launching a
  # deprecated AMI still works, and the seed's dnf update absorbs the errata.
  # Oracle Linux has no seedable upstream AMI (see scripts/bootstrap-ol10.sh).
  # The impossible owner below makes `-var seed=true -var os=oraclelinux` fail
  # loud ("no AMI found") instead of silently sourcing something wrong.
  seed_owners = var.os == "rocky" ? ["792107900819"] : ["000000000000"]

  # Filter keys shared by both source paths.
  common_source_filters = {
    architecture        = var.arch
    root-device-type    = "ebs"
    virtualization-type = "hvm"
    state               = "available"
  }

  seed_filters = merge(local.common_source_filters, {
    name = "Rocky-${var.os_major}-EC2-Base-*.${local.uname_arch}"
  })

  # Refresh source: latest self-owned base of the same os+major+arch, selected
  # by FACTORY TAGS (not a name glob): a name glob could match a foreign or
  # experimental self-owned image as AMIs accumulate, but tag selection only
  # ever picks images this factory (or the tagged lineage root) produced.
  # The role pin is env-blind by design: test bakes SOURCE the prod lineage
  # (isolation applies to what they produce, not what they read), so a lineage
  # root must be seeded with env=prod to be refreshable.
  lineage_filters = merge(local.common_source_filters, {
    "tag:os"       = var.os
    "tag:os_major" = var.os_major
    "tag:arch"     = var.arch
    "tag:role"     = "ppg-package-test"
  })

  # Bootstrap the SSM agent on the builder at boot so session_manager can
  # connect even though the Oracle Linux source AMI does not ship it (chicken-
  # and-egg: session_manager needs the agent to connect in the first place).
  # provision.sh also bakes the agent into the produced image for the smoke test
  # + future bakes. Regional RPM first, global fallback.
  ssm_bootstrap = <<-EOT
    #!/bin/bash
    dnf install -y https://s3.${var.region}.amazonaws.com/amazon-ssm-${var.region}/latest/linux_${local.ssm_arch}/amazon-ssm-agent.rpm \
      || dnf install -y https://s3.amazonaws.com/ec2-downloads-windows/SSMAgent/latest/linux_${local.ssm_arch}/amazon-ssm-agent.rpm
    systemctl enable --now amazon-ssm-agent
  EOT
}

source "amazon-ebs" "el" {
  region        = var.region
  instance_type = local.instance_type
  ami_name      = local.ami_name

  # Connect over AWS Session Manager (SSH tunneled through ssm:StartSession) -
  # no inbound SG, no public-key, works from a dynamic-egress GHA runner.
  # There is NO communicator="ssm"; this is the supported mechanism.
  communicator              = "ssh"
  ssh_username              = local.ssh_user
  ssh_interface             = "session_manager"
  ssh_timeout               = "12m"
  ssh_clear_authorized_keys = true # native: strip packer's temp key from the image
  iam_instance_profile      = var.builder_instance_profile
  # The least-privilege OIDC role can PassRole this profile but intentionally
  # lacks iam:GetInstanceProfile; skip Packer's pre-launch existence check.
  skip_profile_validation = true
  user_data               = local.ssm_bootstrap

  # Native retention: the baked AMI auto-deprecates ~5 weeks out, so superseded
  # weekly images age out without a custom deregister-old sweep.
  deprecate_at = timeadd(timestamp(), "840h")

  # Refresh: latest self-owned lineage base (see local.lineage_filters).
  # Seed (Rocky only): the official upstream AMI (see local.seed_filters).
  source_ami_filter {
    filters            = var.seed ? local.seed_filters : local.lineage_filters
    owners             = var.seed ? local.seed_owners : ["self"]
    most_recent        = true
    include_deprecated = var.seed # Rocky 8's official images are past their AWS DeprecationTime
  }

  subnet_id                   = var.subnet_id
  associate_public_ip_address = true

  # Pre-created no-ingress SG (terraform-managed, egress-only). Supplying a SG
  # disables Packer's temporary SG, so the OIDC role drops all SG-create perms.
  # session_manager needs no inbound rule; egress reaches SSM + dnf over 443.
  security_group_filter {
    filters = {
      "group-name" = var.builder_security_group_name
    }
  }

  launch_block_device_mappings {
    device_name           = "/dev/sda1"
    volume_size           = local.root_volume_size
    volume_type           = "gp3"
    delete_on_termination = true
  }

  run_tags = {
    Name            = "packer-ppg-ami-factory"
    iit-billing-tag = "ppg-ami-factory"
  }
  run_volume_tags = {
    iit-billing-tag = "ppg-ami-factory"
  }

  # role=ppg-candidate at build time; the fresh-boot smoke test promotes a
  # passing image to role=ppg-package-test (what the source filter + consumer
  # select). A non-booting image therefore never becomes selectable.
  tags = {
    Name            = local.ami_name
    os              = var.os
    os_major        = var.os_major
    arch            = var.arch
    role            = local.candidate_role
    source          = local.src_tag
    factory_env     = var.env
    factory_run     = local.ts
    iit-billing-tag = "ppg-ami-factory" # lets the ResourceTag-scoped DeregisterImage match
    base_ami        = "{{ .SourceAMI }}"
    base_name       = "{{ .SourceAMIName }}"
  }
  snapshot_tags = {
    Name            = local.ami_name
    role            = local.candidate_role
    source          = local.src_tag
    factory_env     = var.env
    iit-billing-tag = "ppg-ami-factory" # lets the ResourceTag-scoped DeleteSnapshot match
  }
}

build {
  name    = "ppg-el-refresh"
  sources = ["source.amazon-ebs.el"]

  provisioner "shell" {
    script = "${path.root}/scripts/provision.sh"
    environment_vars = [
      "REGION=${var.region}",
      "SSM_ARCH=${local.ssm_arch}",
    ]
    execute_command = "sudo env {{ .Vars }} bash '{{ .Path }}'"
  }

  # Fail-closed identity/health gates: a failure here aborts the build, so a
  # broken image is never registered as an AMI.
  provisioner "shell" {
    script = "${path.root}/scripts/validate.sh"
    environment_vars = [
      "OS=${var.os}",
      "OS_MAJOR=${var.os_major}",
      "UNAME_ARCH=${local.uname_arch}",
    ]
    # `sudo env {{ .Vars }}` passes packer's env vars through sudo regardless of
    # sudoers env_reset (env sets them as arguments, not via -E preservation).
    execute_command = "sudo env {{ .Vars }} bash '{{ .Path }}'"
  }

  post-processor "manifest" {
    output     = "${path.root}/manifest.json"
    strip_path = true
    custom_data = {
      os       = var.os
      os_major = var.os_major
      arch     = var.arch
      ami_name = local.ami_name
    }
  }
}
