source /tmp/args_pipeline

REPOSITORY_TMP="${REPOSITORY}-${REPOSITORY_VERSION}"
if [[ "${REPOSITORY_VERSION_MAJOR}" == "true" ]]; then
    REPOSITORY_TMP+=" ${REPOSITORY}-$(echo ${REPOSITORY_VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}' | sed 's/\.$//')"
fi

for REPO_TMP in $REPOSITORY_TMP; do
    cd /srv/repo-copy
    export REPO=$(echo ${REPO_TMP} | tr '[:upper:]' '[:lower:]' )
    export RSYNC_TRANSFER_OPTS="-avt  --delete --delete-excluded --delete-after --progress"
    rsync ${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/${REPO_TMP}/* 10.10.9.209:/www/repo.percona.com/htdocs/${REPO_TMP}/
done