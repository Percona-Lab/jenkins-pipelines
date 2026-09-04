# EL package-test snapshot factory (refresh path): Rocky Linux, AlmaLinux, and
# Oracle Linux on Hetzner Cloud. Sibling of the AWS AMI factory
# (../packer/refresh.pkr.hcl).
#
# Rebakes a current package-test target snapshot from the newest self-owned
# promoted snapshot of the same os+major+arch: create server -> dnf update ->
# validate (fail-closed) -> snapshot. Chains forward (each build's output is
# the next build's source).
#
# Usage (one combo per invocation, HCLOUD_TOKEN in env):
#   packer init .
#   packer build -var os=rocky -var os_major=9 -var arch=x86_64 .
#   packer build -var os=almalinux -var os_major=9 -var arch=arm64 .
#
# Lineage roots: every combo seeds once with `-var seed=true`, which sources
# the official Hetzner system image (rocky-8/9/10, alma-8/9/10, both arches)
# instead of the self-owned lineage. Once the seed is promoted, refreshes here
# chain from it. Oracle Linux has no Hetzner system image: an oraclelinux seed
# sources the newest role=ppg-base snapshot instead, uploaded from Oracle's
# official KVM image by bootstrap/bootstrap-base.sh (one-time per combo).

packer {
  required_plugins {
    hcloud = {
      source = "github.com/hetznercloud/hcloud"
      # Pinned exact (supply-chain): a float would let `packer init` pull newer
      # plugin code under the factory token. Bump deliberately.
      version = "1.7.2"
    }
  }
}

variable "location" {
  type    = string
  default = "fsn1"
  validation {
    # Both arches of a combo must bake somewhere CAX (arm64) exists, which is
    # only fsn1/nbg1/hel1. Pinning the x86 half to the same trio keeps every
    # lineage in one location family.
    condition     = contains(["fsn1", "nbg1", "hel1"], var.location)
    error_message = "Value of location must be fsn1, nbg1, or hel1 (CAX arm64 servers exist only there)."
  }
}

variable "os_major" {
  type    = string # "8" | "9" | "10"
  default = "9"
  validation {
    condition     = contains(["8", "9", "10"], var.os_major)
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
  type    = string # "rocky" | "almalinux" | "oraclelinux"
  default = "rocky"
  validation {
    condition     = contains(["rocky", "almalinux", "oraclelinux"], var.os)
    error_message = "Value of os must be rocky, almalinux, or oraclelinux."
  }
}

variable "seed" {
  type        = bool
  default     = false
  description = "First bake per combo: source the official Hetzner system image (rocky/almalinux) or the bootstrap-uploaded role=ppg-base snapshot (oraclelinux) instead of the self-owned lineage snapshot. After the seed is promoted, the normal refresh takes over."
}

variable "server_type_x86" {
  type        = string
  default     = "cpx32"
  description = "Builder server type for x86_64 combos (shared-vCPU AMD). The gen-1 cpx#1 types are no longer offered in fsn1/nbg1/hel1; cpx32 is the current 4c/8GB generation there."
}

variable "server_type_arm" {
  type        = string
  default     = "cax21"
  description = "Builder server type for arm64 combos (Ampere; CAX exists only in fsn1/nbg1/hel1)."
}

variable "env" {
  type        = string
  default     = "prod"
  description = "prod = real factory labels the consumers select; test = temporary isolated labels (role=ppg-test-*, source=factory-test) so test bakes are never consumed by production."
  validation {
    condition     = contains(["prod", "test"], var.env)
    error_message = "Value of env must be prod or test."
  }
}

locals {
  server_type = var.arch == "arm64" ? var.server_type_arm : var.server_type_x86
  uname_arch  = var.arch == "arm64" ? "aarch64" : "x86_64"
  # Label values allow only alnum/dash/underscore/dot, so the stamp carries no
  # colons (formatdate over timestamp() is UTC).
  ts = formatdate("YYYYMMDD-hhmmss", timestamp())
  # Test bakes carry isolated labels so the production consumers (which select
  # role=ppg-package-test, source=factory) never pick them up.
  candidate_role = var.env == "test" ? "ppg-test-candidate" : "ppg-candidate"
  src_label      = var.env == "test" ? "factory-test" : "factory"
  os_short       = var.os == "rocky" ? "Rocky" : var.os == "almalinux" ? "Alma" : "OL"
  name_prefix    = var.env == "test" ? "TEST-PPG-" : "PPG-"
  # Snapshots have no name in the Hetzner API, so snapshot_name lands in the
  # image DESCRIPTION, which is where consumers see this identity string.
  snapshot_name = "${local.name_prefix}${local.os_short}${var.os_major}-${var.arch}-${local.ts}"

  # Seed source: the official Hetzner system images, named rocky-<major> /
  # alma-<major>. One name covers both arches. The builder resolves it against
  # the server type's architecture, so cax builders get the arm64 variant.
  # "ol-<major>" is NOT an official image (none exists): for oraclelinux this
  # local only feeds the base_image provenance label, and the seed sources the
  # ppg-base snapshot below instead.
  seed_image = "${var.os == "rocky" ? "rocky" : var.os == "almalinux" ? "alma" : "ol"}-${var.os_major}"

  # Oracle Linux seed source: the newest base snapshot the bootstrap uploaded
  # (bootstrap/bootstrap-base.sh, pristine Oracle KVM image). Env-blind like
  # the lineage selector: test seeds read the same bases.
  ol_seed = var.os == "oraclelinux" && var.seed
  base_selector = [
    "role=ppg-base",
    "os=oraclelinux",
    "os_major=${var.os_major}",
    "arch=${var.arch}",
    "source=bootstrap",
  ]

  # Oracle Linux only: Hetzner's vendor-data resolves cloud-init's default
  # user to root, and Oracle's stock cloud.cfg ships `disable_root: true`,
  # which wraps the injected key in a "Please login as..." stub, leaving NO
  # ssh-able account on a pristine boot. This user-data un-stubs root for the
  # build boot. provision.sh bakes the same override into the image so smoke,
  # refresh, and consumer boots need no user-data (verified on OL9U8).
  ol_user_data = "#cloud-config\ndisable_root: false\n"

  # Refresh source: newest self-owned promoted snapshot of the same
  # os+major+arch, selected by FACTORY LABELS (the analog of the AWS factory's
  # source_ami_filter on tags): label selection only ever picks snapshots this
  # factory (or the promoted seed rooting the lineage) produced. The role pin
  # is env-blind by design: test bakes SOURCE the prod lineage (isolation
  # applies to what they produce, not what they read), so a lineage root must
  # be seeded with env=prod to be refreshable. The arch label is redundant with
  # the builder's native architecture filter but kept explicit so the selector
  # states the full combo. source=factory + smoke=passed pin the parent to a
  # factory-produced, smoke-passed promoted snapshot: a manually labeled or
  # test-leaked snapshot can never become a parent of the production lineage.
  lineage_selector = [
    "role=ppg-package-test",
    "os=${var.os}",
    "os_major=${var.os_major}",
    "arch=${var.arch}",
    "source=factory",
    "smoke=passed",
  ]

  # Hostnames cannot carry underscores, so the server name flattens the arch.
  hostname_arch = replace(var.arch, "_", "-")
}

source "hcloud" "el" {
  location    = var.location
  server_type = local.server_type
  server_name = "ppg-factory-${var.os}${var.os_major}-${local.hostname_arch}"

  # Plain SSH as root: Hetzner injects Packer's temp key at server create (via
  # cloud-init on snapshot sources), and ssh_clear_authorized_keys strips it
  # from the image natively before the snapshot is taken.
  ssh_username              = "root"
  ssh_timeout               = "10m"
  ssh_clear_authorized_keys = true

  # Seed: official system image by name (rocky/alma) or the ppg-base snapshot
  # by label selector (oraclelinux, which has no official image). Refresh:
  # newest promoted lineage snapshot by label selector. Name and filter are
  # mutually exclusive in the plugin (image XOR image_filter), hence the
  # null / dynamic-block split.
  image = var.seed && !local.ol_seed ? local.seed_image : null

  dynamic "image_filter" {
    for_each = var.seed && !local.ol_seed ? [] : [1]
    content {
      with_selector = local.ol_seed ? local.base_selector : local.lineage_selector
      most_recent   = true
    }
  }

  # OL only (harmless re-assert on lineage boots): unlock root SSH. See the
  # ol_user_data local. Null omits the attribute for rocky/alma.
  user_data = var.os == "oraclelinux" ? local.ol_user_data : null

  # Builder-server labels: identify leaked builders (a crashed run leaves the
  # server behind) without touching anything not carrying these labels.
  server_labels = {
    role        = "ppg-factory-builder"
    factory_env = var.env
    os          = var.os
    os_major    = var.os_major
    arch        = var.arch
  }

  # Label the plugin's temporary per-build SSH key so the janitor can sweep
  # keys leaked by killed runs (an unlabeled key matches no selector).
  ssh_keys_labels = {
    role        = "ppg-factory-builder"
    factory_env = var.env
    os          = var.os
    os_major    = var.os_major
    arch        = var.arch
  }

  # role=ppg-candidate at build time. The fresh-boot smoke test passes and the
  # caller promotes (role -> ppg-package-test, what the lineage selector and
  # the consumers select). A non-booting image therefore never becomes
  # selectable. base_image/base_mode replace the AWS base_ami/base_name pair:
  # the hcloud builder exposes no source-image interpolation, so the labels
  # record the official image family the lineage descends from (base_image)
  # and whether this bake sourced it directly or via the chain (base_mode).
  snapshot_name = local.snapshot_name
  snapshot_labels = {
    role        = local.candidate_role
    os          = var.os
    os_major    = var.os_major
    arch        = var.arch
    source      = local.src_label
    factory_env = var.env
    factory_run = local.ts
    base_image  = local.seed_image
    base_mode   = var.seed ? "seed" : "lineage"
  }
}

build {
  name    = "ppg-el-refresh"
  sources = ["source.hcloud.el"]

  provisioner "shell" {
    script = "${path.root}/scripts/provision.sh"
    # OS drives the script's oraclelinux-only branch (root-login override).
    environment_vars = [
      "OS=${var.os}",
    ]
    # The build runs as root (Hetzner default login), so no sudo wrapper. env
    # passes packer's vars as arguments, immune to any shell env filtering.
    execute_command = "env {{ .Vars }} bash '{{ .Path }}'"
  }

  # Fail-closed identity/health gates: a failure here aborts the build, so a
  # broken image is never snapshotted.
  provisioner "shell" {
    script = "${path.root}/scripts/validate.sh"
    environment_vars = [
      "OS=${var.os}",
      "OS_MAJOR=${var.os_major}",
      "UNAME_ARCH=${local.uname_arch}",
    ]
    execute_command = "env {{ .Vars }} bash '{{ .Path }}'"
  }

  post-processor "manifest" {
    output     = "${path.root}/manifest.json"
    strip_path = true
    custom_data = {
      os            = var.os
      os_major      = var.os_major
      arch          = var.arch
      snapshot_name = local.snapshot_name
    }
  }
}
