export GPG_TTY=$(tty)
echo GPG_TTY is ${GPG_TTY}
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
REPOPUSH_ARGS=""

if [[ "${REMOVE_BEFORE_PUSH}" == "true" ]] && [[ "${COMPONENT}" != "RELEASE" ]]; then
    REPOPUSH_ARGS+=" --remove-package"
fi
#
#
if [[ "${REPOSITORY}" == "PDPXC" ]]; then
  REPOPATH_TMP="/srv/repo-copy/pdpxc-${REPOSITORY_VERSION}/apt"
  if [[ "${REPOSITORY_VERSION_MAJOR}" == "true" ]]; then
    REPOPATH_TMP+=" /srv/repo-copy/pdpxc-$(echo ${REPOSITORY_VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}' | sed 's/\.$//')/apt"
  fi
  export PATH="/usr/local/reprepro5/bin:${PATH}"
fi

if [[ "${REPOSITORY}" == "PDPS" ]]; then
  REPOPATH_TMP="/srv/repo-copy/pdps-${REPOSITORY_VERSION}/apt"
  if [[ "${REPOSITORY_VERSION_MAJOR}" == "true" ]]; then
    REPOPATH_TMP+=" /srv/repo-copy/pdps-$(echo ${REPOSITORY_VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}' | sed 's/\.$//')/apt"
  fi
  export PATH="/usr/local/reprepro5/bin:${PATH}"
fi
#
for REPOPATH in $REPOPATH_TMP; do
    for dir in $DIRECTORIES; do
        #
        set -e
        echo "<*> reprepro binary is $(which reprepro)"
        pushd /srv/UPLOAD/$dir/binary/debian
        echo "Looking for Debian build directories..."
        CODENAMES=$(ls -1)

        echo Distributions are: "${CODENAMES}"
        popd
        #
        #######################################
        # source pushing, it's a bit specific #
        #######################################
        #
        if [[ "${REMOVE_LOCKFILE}" == "true" ]]; then
            echo "Removing lock file as requested..."
            sudo rm -vf ${REPOPATH}/db/lockfile
        fi
        #
        if [[ "${REPOCOMP}" == "release" ]]; then
            export REPOCOMP=main
        # pushing sources
            if [ -d /srv/UPLOAD/$dir/source/debian ]; then
                cd /srv/UPLOAD/$dir/source/debian
                DSC=$(find . -type f -name '*.dsc')
                for DSC_FILE in ${DSC}; do
                    echo "DSC file is ${DSC_FILE}"
                    for _codename in ${CODENAMES}; do
                        echo "===>DSC $DSC_FILE"
                        repopush --gpg-pass=${SIGN_PASSWORD} --package=${DSC_FILE} --repo-path=${REPOPATH} --component=${REPOCOMP}  --codename=${_codename} --verbose ${REPOPUSH_ARGS}
                        sleep 1
                    done
                done
            fi
        fi

        #
        #######################################
        # binary pushing                      #
        #######################################
        cd /srv/UPLOAD/$dir/binary/debian

        for _codename in ${CODENAMES}; do
            pushd ${_codename}
                DEBS=$(find . -type f -name '*.*deb' )
                for _deb in ${DEBS}; do
                    repopush --gpg-pass=${SIGN_PASSWORD} --package=${_deb} --repo-path=${REPOPATH} --component=${REPOCOMP} --codename=${_codename} --verbose ${REPOPUSH_ARGS}
                done
            #
            popd
        done
        #
    done
done

echo "Quickly checking pool integrity..."
#reprepro -b ${REPOPATH} checkpool fast || exit $?
