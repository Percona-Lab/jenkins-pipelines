source /tmp/args_pipeline
if [[ "${COMPONENT}" == RELEASE ]]; then

# Create list of packages to push:
for dir in $(printenv | grep _PATH | sed 's/^.*=//g'); do
    DIRECTORIES+=" ${dir}"
done

if [[ -z "${DIRECTORIES}" ]]; then
    echo "No PATH specified..."
    exit 1
fi

if [[ "${REPOSITORY}" == "PDPXC" ]]; then
  REPOPATH=percona-distribution-mysql-pxc
  REPOPATH_W_V=percona-distribution-mysql-pxc-$(echo ${REPOSITORY_VERSION} | sed "s/-/./g"  |awk -F"." 'BEGIN { OFS = "." }{ print $1, $2, $3}' | sed 's/\.$//')
fi
#
if [[ "${REPOSITORY}" == "PDPS" ]]; then
  REPOPATH=percona-distribution-mysql-ps
  REPOPATH_W_V=percona-distribution-mysql-ps-$(echo ${REPOSITORY_VERSION} | sed "s/-/./g"  |awk -F"." 'BEGIN { OFS = "." }{ print $1, $2, $3}' | sed 's/\.$//')

fi

RELEASEDIR="/srv/UPLOAD/${REPOPATH}/${REPOPATH_W_V}/.tmp/${REPOPATH}/${REPOPATH_W_V}"
rm -fr /srv/UPLOAD/${REPOPATH}/${REPOPATH_W_V}/.tmp/${REPOPATH}/${REPOPATH_W_V}
mkdir -p ${RELEASEDIR}

for dir in $DIRECTORIES; do
#
    set -e
    cd /srv/UPLOAD/${dir}/
#
    PRODUCT="$(echo ${dir} | awk -F '/' '{print $3}')"
    RELEASE="$(echo ${dir} | awk -F '/' '{print $4}')"
    REVISION="$(echo ${dir} | awk -F '/' '{print $6}')"
    #
    cp -av ./* ${RELEASEDIR}
done

cd /srv/UPLOAD/${REPOPATH}/${REPOPATH_W_V}/.tmp/
ln -s ${REPOPATH} ${REPOPATH}-LATEST
#
cd /srv/UPLOAD/${REPOPATH}/${REPOPATH_W_V}/.tmp/${REPOPATH}
ln -s ${REPOPATH_W_V} LATEST
#
cd /srv/UPLOAD/${REPOPATH}/${REPOPATH_W_V}/.tmp
#
# rsync -avt --bwlimit=50000 --exclude="*yassl*" --progress ${PRODUCT} 10.10.9.211:/data/downloads/
#rsync -avt --bwlimit=50000 --exclude="*yassl*" --progress ${PRODUCT} 10.10.9.216:/data/downloads/
#JEN-1082
rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="*yassl*" --progress ${REPOPATH} jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/
#
rm -fr /srv/UPLOAD/${REPOPATH}/${REPOPATH_W_V}/.tmp
#
fi
#