// Hetzner Cloud twin of moleculeDistributionJenkinsCreds: binds the QA project
// API token (Jenkins string credential) to HCLOUD_TOKEN, which the
// hetzner.hcloud Ansible modules and the hcloud CLI read from the environment.
// No SSH key binding on purpose: create-hetzner.yml generates a per-run
// ed25519 keypair instead of a shared key.
def call() {
  return [string(credentialsId: 'hcloud-ppg-qa-token', variable: 'HCLOUD_TOKEN')]
}
