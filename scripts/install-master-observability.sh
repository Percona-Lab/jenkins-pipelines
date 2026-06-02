#!/bin/bash
# install-master-observability.sh
#
# PS-10997 / ADR 0013: master-side push pipeline.
# Installs amazon-ssm-agent (fleet ops fan-out) and Grafana Alloy
# (scrapes /hetzner-prometheus + tails Jenkins log; pushes to the
# in-cluster alloy-gateway ALB). Bearer token is fetched from AWS
# Secrets Manager at every Alloy restart via systemd ExecStartPre.
#
# Caller must export JENKINS_HOST (FQDN, e.g. ps3.cd.percona.com).
# Loaded at master boot from the CFN userData install_observability()
# bootstrap on all 10 Percona Jenkins masters.
#
# Idempotent: dnf install, file writes, systemctl enable --now all
# re-run safely.

set -euo pipefail

if [[ -z "${JENKINS_HOST:-}" ]]; then
    echo "FATAL: JENKINS_HOST not set" >&2
    exit 1
fi

MASTER_LABEL="${JENKINS_HOST%.percona.com}"

# 1. SSM agent (used for fleet-wide Run Command fan-out, not for the push itself).
dnf -y install amazon-ssm-agent || yum -y install amazon-ssm-agent
systemctl enable --now amazon-ssm-agent

# 2. Grafana RPM repo + Alloy.
cat > /etc/yum.repos.d/grafana.repo <<'REPO'
[grafana]
name=grafana
baseurl=https://rpm.grafana.com
repo_gpgcheck=1
enabled=1
gpgcheck=1
gpgkey=https://rpm.grafana.com/gpg.key
sslverify=1
sslcacert=/etc/pki/tls/certs/ca-bundle.crt
REPO
dnf -y install alloy

# 3. Boot-time bearer fetch from Secrets Manager (us-east-1, account-local).
# The secret value is JSON (`{"bearer_token":"..."}`); extract the field with
# python3 (always present on AL2023). Atomic write: stage to .tmp then mv,
# so a failed Secrets Manager call does NOT leave an empty token file that
# would 401 every push.
cat > /usr/local/bin/alloy-fetch-token <<'SCRIPT'
#!/bin/bash
set -euo pipefail
umask 077
raw=$(aws secretsmanager get-secret-value \
    --region us-east-1 \
    --secret-id percona-ci-platform/alloy-gateway/bearer \
    --query SecretString --output text)
tok=$(printf '%s' "$raw" | python3 -c '
import json, sys
d = json.loads(sys.stdin.read())
for k in ("bearer_token", "bearer", "token"):
    if isinstance(d, dict) and k in d:
        print(d[k], end=""); break
else:
    print(d, end="")
')
[[ -n "$tok" ]] || { echo "alloy-fetch-token: empty bearer" >&2; exit 1; }
tmp=/etc/alloy/gateway-token.tmp
printf '%s' "$tok" > "$tmp"
chown root:alloy "$tmp"
chmod 0440 "$tmp"
mv -f "$tmp" /etc/alloy/gateway-token
SCRIPT
chmod 0755 /usr/local/bin/alloy-fetch-token

install -d -m 0755 /etc/systemd/system/alloy.service.d
# `+` prefix on ExecStartPre runs the fetcher as root regardless of `User=alloy`
# on the unit, so it can write /etc/alloy/gateway-token (0440 root:alloy).
cat > /etc/systemd/system/alloy.service.d/fetch-token.conf <<'DROPIN'
[Service]
ExecStartPre=+/usr/local/bin/alloy-fetch-token
Restart=on-failure
RestartSec=30s
DROPIN

# 4. Alloy config. master external_label is DNS-shaped (gotcha 5 in
# percona-observability skill); the receiver path is
# /api/v1/metrics/write (gotcha 4), not /api/v1/push.
install -d -o root -g alloy -m 0750 /etc/alloy
cat > /etc/alloy/config.alloy <<CONFIG
prometheus.scrape "hetzner_local" {
  targets         = [{__address__ = "localhost:8080", __metrics_path__ = "/hetzner-prometheus/"}]
  forward_to      = [prometheus.remote_write.mimir.receiver]
  scrape_interval = "60s"
  scrape_timeout  = "15s"
}

prometheus.remote_write "mimir" {
  endpoint {
    url               = "https://mimir-push.cd.percona.com/api/v1/metrics/write"
    bearer_token_file = "/etc/alloy/gateway-token"
    headers           = { "X-Scope-OrgID" = "percona-ci" }
  }
  external_labels = {
    master = "$MASTER_LABEL",
    fleet  = "percona-jenkins",
    role   = "master",
  }
}

loki.source.file "jenkins" {
  targets       = [{__path__ = "/var/log/jenkins/jenkins.log", master = "$MASTER_LABEL", fleet = "percona-jenkins", role = "master", component = "jenkins"}]
  forward_to    = [loki.write.gateway.receiver]
  tail_from_end = false
}

loki.write "gateway" {
  endpoint {
    url               = "https://loki-push.cd.percona.com/loki/api/v1/push"
    bearer_token_file = "/etc/alloy/gateway-token"
  }
}
CONFIG
chown root:alloy /etc/alloy/config.alloy
chmod 0640 /etc/alloy/config.alloy

# 5. Enable + start (ExecStartPre fetches the bearer at every restart).
systemctl daemon-reload
systemctl enable --now alloy
