#!/bin/bash
# Mirrors one repo/component from repo.ci to the production repo server.
# Inputs, exported by the pipeline: REPO COMPONENT

REPO_ROOT=${REPO_ROOT:-/srv/repo-copy}
PROD_HOST=${PROD_HOST:-10.30.9.32}

main() {
    set -o errexit
    set -o nounset
    set -o xtrace

    comp=$(echo "${COMPONENT}" | tr '[:upper:]' '[:lower:]')
    opts="-avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak"

    mkdir -p "${REPO_ROOT}/.publish-locks"
    exec 9>> "${REPO_ROOT}/.publish-locks/${REPO//\//_}.lock"
    if ! flock -w 5400 9; then
        echo "ERROR: could not get the publish lock for ${REPO}"
        exit 1
    fi

    date +%s > "${REPO_ROOT}/version"

    rsync ${opts} "${REPO_ROOT}/${REPO}/yum/${comp}/" "${PROD_HOST}:/www/repo.percona.com/htdocs/${REPO}/yum/${comp}/"
    rsync ${opts} "${REPO_ROOT}/${REPO}/apt/" "${PROD_HOST}:/www/repo.percona.com/htdocs/${REPO}/apt/"
    rsync ${opts} "${REPO_ROOT}/version" "${PROD_HOST}:/www/repo.percona.com/htdocs/"
}

main < /dev/null
