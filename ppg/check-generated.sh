#!/bin/bash
# drift check: the generated ppg jobs come from Percona-QA/ppg-testing (*/scenario.yml + templates/jenkins/)
# needs python3 with pyyaml + jinja2 installed
set -euo pipefail
JENKINS_REPO="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${PPG_TESTING_DIR:-}" ]; then
  cd "$PPG_TESTING_DIR"
else
  PPG_TESTING_BRANCH="${PPG_TESTING_BRANCH:-main}"
  TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
  git clone -q --depth 1 -b "$PPG_TESTING_BRANCH" https://github.com/Percona-QA/ppg-testing.git "$TMP/ppg-testing"
  cd "$TMP/ppg-testing"
fi
python3 tools/gen_jenkins.py --check --jenkins-repo "$JENKINS_REPO"
