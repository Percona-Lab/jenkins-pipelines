set -x
set -e
#
DIRECTORIES=""
source /tmp/args_pipeline

# Create list of packages to push:
for dir in $(printenv | grep _PATH | sed 's/^.*=//g'); do
    DIRECTORIES+=" ${dir}"
done

if [[ -z "${DIRECTORIES}" ]]; then
    echo "No PATH specified..."
    exit 1
fi
#
REPOCOMP="$(echo ${COMPONENT} | tr '[:upper:]' '[:lower:]')"
REPOPATH="${REPOSITORY}"
#
if [[ "${REPOSITORY}" == "PDPXC" ]]; then
  REPOPATH_TMP="repo-copy/pdpxc-${REPOSITORY_VERSION}/yum"
  if [[ "${REPOSITORY_VERSION_MAJOR}" == "true" ]]; then
    REPOPATH_TMP+=" repo-copy/pdpxc-$(echo ${REPOSITORY_VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}' | sed 's/\.$//')/yum"
  fi
fi
#
if [[ "${REPOSITORY}" == "PDPS" ]]; then
  REPOPATH_TMP="repo-copy/pdps-${REPOSITORY_VERSION}/yum"
  if [[ "${REPOSITORY_VERSION_MAJOR}" == "true" ]]; then
    REPOPATH_TMP+=" repo-copy/pdps-$(echo ${REPOSITORY_VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}' | sed 's/\.$//')/yum"
  fi
fi
#
for REPOPATH in $REPOPATH_TMP; do
    for dir in $DIRECTORIES; do
        #
        cd /srv/UPLOAD/${dir}
        #
        # getting the list of RH systems
        RHVERS=$(ls -1 binary/redhat)
        #
        # source processing
        if [ -d /srv/UPLOAD/$dir/source/redhat ]; then
          SRCRPM=$(find source/redhat -name '*.src.rpm')
          for rhel in ${RHVERS}; do 
              mkdir -p /srv/${REPOPATH}/${REPOCOMP}/${rhel}/SRPMS
              cp -v ${SRCRPM} /srv/${REPOPATH}/${REPOCOMP}/${rhel}/SRPMS
              createrepo --update /srv/${REPOPATH}/${REPOCOMP}/${rhel}/SRPMS
	      if [ -f /srv/${REPOPATH}/${REPOCOMP}/${rhel}/SRPMS/repodata/repomd.xml.asc ]; then
                  rm -f /srv/${REPOPATH}/${REPOCOMP}/${rhel}/SRPMS/repodata/repomd.xml.asc
              fi
	      echo ${SIGN_PASSWORD} | gpg --detach-sign --armor /srv/${REPOPATH}/${REPOCOMP}/${rhel}/SRPMS/repodata/repomd.xml
          done
        fi
        #
        # binary processing
        pushd binary
        for rhel in ${RHVERS}; do
            mkdir -p /srv/${REPOPATH}/${REPOCOMP}/${rhel}/RPMS
            for arch in $(ls -1 redhat/${rhel}); do
                mkdir -p /srv/${REPOPATH}/${REPOCOMP}/${rhel}/RPMS/${arch}
                cp -av redhat/${rhel}/${arch}/*.rpm /srv/${REPOPATH}/${REPOCOMP}/${rhel}/RPMS/${arch}/
                createrepo --update /srv/${REPOPATH}/${REPOCOMP}/${rhel}/RPMS/${arch}/
		if [ -f /srv/${REPOPATH}/${REPOCOMP}/${rhel}/RPMS/${arch}/repodata/repomd.xml.asc ]; then
                    rm -f /srv/${REPOPATH}/${REPOCOMP}/${rhel}/RPMS/${arch}/repodata/repomd.xml.asc
		fi
		echo ${SIGN_PASSWORD} | gpg --detach-sign --armor /srv/${REPOPATH}/${REPOCOMP}/${rhel}/RPMS/${arch}/repodata/repomd.xml
            done
        done
        #
    done
    #
done
