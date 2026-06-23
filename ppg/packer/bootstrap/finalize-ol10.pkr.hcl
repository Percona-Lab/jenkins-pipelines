# OL10 bootstrap FINALIZE - turns the import-registered raw OL10 base
# (Oracle official cloud image: default user opc, no ssm-agent) into the
# consumable lineage-root base. The import + register of the raw base is AWS CLI
# (Packer cannot import-snapshot); THIS Packer build owns the finalize + the
# AMI/snapshot TAGS, so the bootstrap is tag-managed by Packer like the refresh.
#
# Connects over Session Manager as the raw image's default user (opc) - user_data
# installs amazon-ssm-agent at launch (the raw image lacks it), same mechanism as
# the refresh template. The provisioner adds ec2-user + dnf update; the produced
# AMI is tagged role=ppg-ol10-candidate (promote via `just bootstrap-ol10-verify`).
#
#   packer init . && packer build -var raw_ami=ami-... -var arch=arm64 .   # or x86_64

packer {
  required_plugins {
    amazon = {
      source  = "github.com/hashicorp/amazon"
      version = "1.8.1" # pinned exact (supply-chain); matches the refresh template
    }
  }
}

variable "raw_ami" { type = string } # the import-registered raw OL10 base
variable "arch" { type = string }    # x86_64 | arm64
variable "os_major" {
  type    = string
  default = "10"
}
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
variable "ssh_username" {
  type    = string
  default = "opc" # Oracle official cloud image default user (90_ol.cfg)
}
variable "env" {
  type    = string
  default = "prod"
}

locals {
  instance_type  = var.arch == "arm64" ? "t4g.large" : "t3.large"
  ssm_arch       = var.arch == "arm64" ? "arm64" : "amd64"
  ts             = formatdate("YYYYMMDD-hhmmss", timestamp())
  candidate_role = var.env == "test" ? "ppg-test-candidate" : "ppg-ol10-candidate"
  src_tag        = var.env == "test" ? "factory-test" : "factory-bootstrap"
  name           = "${var.env == "test" ? "TEST-OL" : "OL"}${var.os_major}-${var.arch}-${local.ts}"
  ssm_bootstrap  = <<-EOT
    #!/bin/bash
    dnf install -y https://s3.${var.region}.amazonaws.com/amazon-ssm-${var.region}/latest/linux_${local.ssm_arch}/amazon-ssm-agent.rpm \
      || dnf install -y https://s3.amazonaws.com/ec2-downloads-windows/SSMAgent/latest/linux_${local.ssm_arch}/amazon-ssm-agent.rpm
    systemctl enable --now amazon-ssm-agent
  EOT
}

source "amazon-ebs" "finalize" {
  region                      = var.region
  instance_type               = local.instance_type
  source_ami                  = var.raw_ami
  ami_name                    = local.name
  communicator                = "ssh"
  ssh_username                = var.ssh_username
  ssh_interface               = "session_manager"
  ssh_timeout                 = "12m"
  ssh_clear_authorized_keys   = true
  iam_instance_profile        = var.builder_instance_profile
  skip_profile_validation     = true
  user_data                   = local.ssm_bootstrap
  subnet_id                   = var.subnet_id
  associate_public_ip_address = true
  deprecate_at                = timeadd(timestamp(), "840h")

  security_group_filter {
    filters = {
      "group-name" = var.builder_security_group_name
    }
  }

  run_tags = {
    Name            = "packer-ppg-ol10-finalize"
    iit-billing-tag = "ppg-ami-factory"
  }
  run_volume_tags = {
    iit-billing-tag = "ppg-ami-factory"
  }

  # Packer owns the tags: candidate role until `just bootstrap-ol10-verify` promotes.
  tags = {
    Name            = local.name
    os              = "oraclelinux"
    os_major        = var.os_major
    arch            = var.arch
    role            = local.candidate_role
    source          = local.src_tag
    factory_env     = var.env
    factory_run     = local.ts
    iit-billing-tag = "ppg-ami-factory"
    base            = "oracle-official-ol10-cloud"
  }
  snapshot_tags = {
    Name            = local.name
    role            = local.candidate_role
    os_major        = var.os_major
    arch            = var.arch
    factory_env     = var.env
    iit-billing-tag = "ppg-ami-factory"
  }
}

build {
  name    = "ppg-ol10-finalize"
  sources = ["source.amazon-ebs.finalize"]

  provisioner "shell" {
    script          = "${path.root}/../scripts/finalize-ol10.sh"
    execute_command = "sudo env {{ .Vars }} bash '{{ .Path }}'"
  }

  post-processor "manifest" {
    output     = "${path.root}/manifest.json"
    strip_path = true
  }
}
