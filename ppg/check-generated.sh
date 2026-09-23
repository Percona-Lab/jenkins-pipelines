#!/bin/bash
# the generated ppg jobs (ppg/*.groovy + ppg/*.yml with the GENERATED header)
# come from Percona-QA/ppg-testing: */scenario.yml + templates/jenkins/
#   check-generated.sh          drift check, prints DRIFT lines, exit 1 on drift
#   check-generated.sh --write  regenerate them in place
# needs python3 with pyyaml + jinja2 installed. PPG_TESTING_DIR points at a
# checkout, otherwise PPG_TESTING_BRANCH (default main) is cloned.
set -euo pipefail
case "${1:---check}" in
  --check) ARGS=(--check) ;;
  --write) ARGS=() ;;
  *) echo "usage: $0 [--check|--write]" >&2; exit 2 ;;
esac
JENKINS_REPO="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${PPG_TESTING_DIR:-}" ]; then
  cd "$PPG_TESTING_DIR"
else
  PPG_TESTING_BRANCH="${PPG_TESTING_BRANCH:-main}"
  TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
  git clone -q --depth 1 -b "$PPG_TESTING_BRANCH" https://github.com/Percona-QA/ppg-testing.git "$TMP/ppg-testing"
  cd "$TMP/ppg-testing"
fi
python3 tools/gen_jenkins.py ${ARGS[@]+"${ARGS[@]}"} --jenkins-repo "$JENKINS_REPO"
