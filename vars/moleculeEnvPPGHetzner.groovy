// Hetzner Cloud twin of moleculeEnvPPG: environment for the molecule delegated
// driver (playbooks/create-hetzner.yml + destroy-hetzner.yml in ppg-testing).
// Unlike the AWS var there are no image IDs here: snapshots are resolved AT
// RUNTIME by the create playbook via hetzner.hcloud.image_info from the label
// contract (ppg/packer-hetzner/README.md), so nothing is pinned or pre-resolved.
def call() {
    return """
        # Molecule driver: create/destroy are delegated to the hetzner playbooks.
        # Scenarios interpolate \${driver} exactly like the AWS ones (moleculeEnvPPG
        # exports driver=ec2)
        export driver=delegated
        # Placement fallback order for create-hetzner.yml (CAX arm types exist only in these three DCs)
        export HETZNER_LOCATIONS="fsn1 nbg1 hel1"
        # Default x86_64 server type, the hcloud analog of the t3.large tier the AWS scenarios use
        export htz_server_type_x86_64=cpx32
        # Default arm64 server type (Ampere CAX line)
        export htz_server_type_arm64=cax21
        # Snapshot lookup base: exported ONLY when the caller pre-set it, else empty
        # ON PURPOSE (fail-closed). Every scenario must declare its own image_selector
        # (e.g. role=ppg-package-test,source=factory,os=rocky,os_major=9) or the
        # create playbook aborts naming the platform: a baked-in OS fallback would let
        # a scenario silently test the wrong OS, and wrong-OS green is worse than
        # missing-var red. create-hetzner.yml appends ,arch=<platform arch> and picks
        # the newest matching promoted snapshot at runtime
        export PPG_IMAGE_SELECTOR_BASE="\${PPG_IMAGE_SELECTOR_BASE:-}"
    """
}
