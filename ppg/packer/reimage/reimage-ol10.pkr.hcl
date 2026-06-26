# OL10 re-image (amazon-ebssurrogate): shrink the booted prebase onto a var.volume_size surrogate and
# register the AMI from it (EBS can't restore below a snapshot, XFS can't shrink in place). Sourced from
# role=ppg-ol10-prebase, which bootstrap-ol10-verify already booted, so /boot is complete. docs/reimage.md.
#
#   packer init . && packer build -var arch=x86_64 .   # or arm64; -var env=test for an isolated candidate

packer {
  required_plugins {
    amazon = {
      source  = "github.com/hashicorp/amazon"
      version = "1.8.1" # pinned exact (supply-chain); parity across all templates enforced by `just check`
    }
  }
}

variable "arch" { type = string } # x86_64 | arm64
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
variable "env" {
  type    = string
  default = "prod"
}
variable "volume_size" {
  type    = number
  default = 20 # surrogate (AMI) root GiB; MUST equal the refresh template's var.volume_size (drift-guarded in `just check`)
}

locals {
  instance_type  = var.arch == "arm64" ? "t4g.large" : "t3.large"
  boot_mode      = var.arch == "arm64" ? "uefi" : "legacy-bios"
  ts             = formatdate("YYYYMMDD-hhmmss", timestamp())
  candidate_role = var.env == "test" ? "ppg-reimage-test-candidate" : "ppg-reimage-candidate"
  src_tag        = var.env == "test" ? "factory-test" : "factory"
  name           = "${var.env == "test" ? "TEST-OL" : "OL"}${var.os_major}-${var.arch}-reimage-${local.ts}"
}

source "amazon-ebssurrogate" "reimage" {
  region                      = var.region
  instance_type               = local.instance_type
  ami_name                    = local.name
  ami_architecture            = var.arch
  ami_virtualization_type     = "hvm"
  boot_mode                   = local.boot_mode
  ena_support                 = true
  communicator                = "ssh"
  ssh_username                = "ec2-user" # the prebase is finalized (ec2-user + amazon-ssm-agent baked)
  ssh_interface               = "session_manager"
  ssh_timeout                 = "12m"
  ssh_clear_authorized_keys   = true
  iam_instance_profile        = var.builder_instance_profile
  skip_profile_validation     = true
  subnet_id                   = var.subnet_id
  associate_public_ip_address = true
  deprecate_at                = timeadd(timestamp(), "168h") # a candidate; verify promotes it within hours

  # Source = the booted, verified full-size prebase (role=ppg-ol10-prebase) for THIS arch.
  source_ami_filter {
    filters = {
      "tag:os"            = "oraclelinux"
      "tag:os_major"      = var.os_major
      "tag:arch"          = var.arch
      "tag:role"          = "ppg-ol10-prebase"
      architecture        = var.arch
      root-device-type    = "ebs"
      virtualization-type = "hvm"
      state               = "available"
    }
    owners      = ["self"]
    most_recent = true
  }

  security_group_filter {
    filters = {
      "group-name" = var.builder_security_group_name
    }
  }

  # Blank surrogate volume: reimage-surgery.sh partitions + copies the prebase root onto it at
  # var.volume_size, then Packer snapshots IT (not the builder's larger root) and registers the AMI.
  launch_block_device_mappings {
    device_name           = "/dev/sdf"
    volume_size           = var.volume_size
    volume_type           = "gp3"
    delete_on_termination = true
  }

  ami_root_device {
    source_device_name    = "/dev/sdf"
    device_name           = "/dev/sda1"
    volume_size           = var.volume_size
    volume_type           = "gp3"
    delete_on_termination = true
  }

  run_tags = {
    Name            = "packer-ppg-ol10-reimage"
    iit-billing-tag = "ppg-ami-factory"
  }
  run_volume_tags = {
    iit-billing-tag = "ppg-ami-factory"
  }

  # Packer owns the tags: a non-consumed candidate role until `just reimage-ol10-verify` promotes.
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
    base            = "ppg-ol10-prebase"
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
  name    = "ppg-ol10-reimage"
  sources = ["source.amazon-ebssurrogate.reimage"]

  provisioner "shell" {
    script = "${path.root}/../scripts/reimage-surgery.sh"
    environment_vars = [
      "ARCH=${var.arch}",
      "ROOT_GIB=${var.volume_size}",
    ]
    execute_command = "sudo env {{ .Vars }} bash '{{ .Path }}'"
  }

  post-processor "manifest" {
    output     = "${path.root}/manifest.json"
    strip_path = true
  }
}
