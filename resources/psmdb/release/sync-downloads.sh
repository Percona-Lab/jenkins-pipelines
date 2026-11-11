#!/bin/bash
# Publishes one UPLOAD build to downloads.percona.com.
# Inputs, exported by the pipeline: PATH_TO_BUILD MODE COPY_TELEMETRY TARGETS
# The path is <destination>/BUILDS/<product>/<release>/<tag>/<revision>/<build>.
#
# MODE=server  the build gets its own /data/downloads/<product>/<release>: tar bundles of the
#              packages, sha256sums for the tarballs, then rsync. Same layout as the
#              "Sync packages to production download" stage of release-from-repository.groovy.
# MODE=merge   tools and mongosh. They go into the release directory of the server, one
#              <product>/<release> per word in TARGETS. Only what the server directory has is
#              copied: an OS and arch that the server does not have there gets no tools and no
#              mongosh packages or tarballs. A release that is not the one <product>/LATEST
#              points to is refused, what has been released already does not change.
# MODE=check   only the checks of merge, nothing is copied. Run before the repos are touched.
# exit: 0 all done, 42 some targets have no server directory, 43 nothing was published,
#       44 a target is not the latest release

UPLOAD_ROOT=${UPLOAD_ROOT:-/srv/UPLOAD}
DEPLOY_HOST=${DEPLOY_HOST:-jenkins-deploy.jenkins-deploy.web.r.int.percona.com}
DOWNLOADS_ROOT=${DOWNLOADS_ROOT:-/data/downloads}
RC_PARTIAL=42
RC_NONE=43
RC_REFUSED=44

dssh() {
    ssh -p 2222 -o StrictHostKeyChecking=no "${DEPLOY_HOST}" "$@"
}

tarball_sums() {
    local dir=$1
    [ -d "${dir}" ] || return 0
    # subshell, so the caller stays in its own directory
    (
        cd "${dir}"
        for tarball in *tar.*; do
            [ -e "${tarball}" ] || continue
            # symlinks are skipped, like in percona-agent
            [ -h "${tarball}" ] || sha256sum "${tarball}" > "${tarball}.sha256sum"
        done
    )
}

# drops what the server directory (listing in $1) does not have from the copy in $2
prune_to_server() {
    local listing=$1 root=$2 d f name arch os
    local re_os='-(x86_64|aarch64)\.(ol[0-9]+|[a-z]+)\.tar\.gz$'
    local re_arch='-(x86_64|aarch64)\.tar\.gz$'
    for d in $(cd "${root}/binary" && find redhat debian -mindepth 2 -maxdepth 2 -type d 2>/dev/null); do
        grep -qxF "${d}" "${listing}" || { echo "held back: ${d}"; rm -rf "${root}/binary/${d}"; }
    done
    for f in $(find "${root}/binary/tarball" -type f 2>/dev/null); do
        name=$(basename "${f}")
        if [[ ${name} =~ ${re_os} ]]; then
            arch=${BASH_REMATCH[1]}
            os=${BASH_REMATCH[2]}
            grep -qE -- "-${arch}\.${os}(-minimal)?\.tar\.gz$" "${listing}" \
                || { echo "held back: ${name}"; rm -f "${f}"; }
        elif [[ ${name} =~ ${re_arch} ]]; then
            arch=${BASH_REMATCH[1]}
            grep -qE -- "-${arch}[.-].*tar\.gz$" "${listing}" \
                || { echo "held back: ${name}"; rm -f "${f}"; }
        fi
    done
    find "${root}/binary" -type d -empty -delete 2>/dev/null || true
}

# every target must be the latest release of its product, otherwise nothing is done at all
refuse_old_releases() {
    local target product release latest
    for target in ${TARGETS}; do
        product=${target%%/*}
        release=${target#*/}
        latest=$(dssh readlink "${DOWNLOADS_ROOT}/${product}/LATEST" || true)
        # a release that is not there at all is reported later as n/a
        dssh test -d "${DOWNLOADS_ROOT}/${product}/${release}/binary" || continue
        if [ "${latest}" != "${release}" ]; then
            echo "REFUSED ${target}: ${product}/LATEST is ${latest:-missing}, what has been released is not changed"
            exit ${RC_REFUSED}
        fi
    done
}

publish_server() {
    local build_dir=$1 PRODUCT RELEASE REVISION RELEASEDIR pkg os ext dist arch name
    PRODUCT=$(echo "${PATH_TO_BUILD}" | awk -F '/' '{print $3}')
    RELEASE=$(echo "${PATH_TO_BUILD}" | awk -F '/' '{print $4}')
    REVISION=$(echo "${PATH_TO_BUILD}" | awk -F '/' '{print $6}')
    RELEASEDIR=${build_dir}/.tmp/${PRODUCT}/${RELEASE}

    cd "${build_dir}"
    rm -fr "${build_dir}/.tmp"
    mkdir -p "${RELEASEDIR}"
    cp -av ./* "${RELEASEDIR}"
    [ "${COPY_TELEMETRY}" = YES ] && touch "${RELEASEDIR}/binary/telemetry-enhanced.json"

    # a bundle only when there is more than one package in the directory
    for pkg in redhat:rpm debian:deb; do
        os=${pkg%%:*}
        ext=${pkg#*:}
        [ -d "${RELEASEDIR}/binary/${os}" ] || continue
        for dist in $(ls -1 "${RELEASEDIR}/binary/${os}"); do
            for arch in $(ls -1 "${RELEASEDIR}/binary/${os}/${dist}"); do
                cd "${RELEASEDIR}/binary/${os}/${dist}/${arch}"
                if [ "$(find . -maxdepth 1 -type f -name "*.${ext}" | wc -l)" -gt 1 ]; then
                    if [ "${os}" = redhat ]; then
                        name=${RELEASE}-r${REVISION}-el${dist}-${arch}-bundle.tar
                    else
                        name=${RELEASE}-r${REVISION}-${dist}-${arch}-bundle.tar
                    fi
                    tar --owner=0 --group=0 -cf "${name}" ./*."${ext}"
                fi
            done
        done
    done

    if [ -d "${RELEASEDIR}/source/tarball" ] && [ -d "${RELEASEDIR}/source/tarball/source_tarball" ]; then
        mv "${RELEASEDIR}"/source/tarball/source_tarball/* "${RELEASEDIR}/source/tarball/"
        rm -rf "${RELEASEDIR}/source/tarball/source_tarball"
    fi
    tarball_sums "${RELEASEDIR}/source/tarball"
    tarball_sums "${RELEASEDIR}/binary/tarball"

    cd "${RELEASEDIR}/.."
    ln -sfn "${RELEASE}" LATEST
    cd "${build_dir}/.tmp"
    rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="*yassl*" --progress "${PRODUCT}" "${DEPLOY_HOST}:${DOWNLOADS_ROOT}/"
    rm -fr "${build_dir}/.tmp"
}

publish_merge() {
    local build_dir=$1 target product release dest work listing published=0 missing=0
    refuse_old_releases
    listing=$(mktemp)
    cd "${build_dir}"
    for target in ${TARGETS}; do
        product=${target%%/*}
        release=${target#*/}
        dest=${DOWNLOADS_ROOT}/${product}/${release}
        if ! dssh test -d "${dest}/binary"; then
            echo "n/a ${target}: no server release directory on downloads"
            missing=$((missing + 1))
            continue
        fi
        # what the server has: redhat/<el>/<arch> and debian/<dist>/<arch> directories, tarball names
        dssh "cd '${dest}/binary' && find redhat debian -mindepth 2 -maxdepth 2 -type d 2>/dev/null; ls -1 tarball 2>/dev/null" > "${listing}"

        work=${build_dir}/.tmp/${product}_${release}
        rm -fr "${work}"
        mkdir -p "${work}"
        # hard links, so pruning and the checksums do not touch the build in UPLOAD
        cp -al ./* "${work}/"
        prune_to_server "${listing}" "${work}"
        tarball_sums "${work}/binary/tarball"
        tarball_sums "${work}/source/tarball"
        rsync -avt -e "ssh -p 2222" --bwlimit=50000 --progress "${work}/" "${DEPLOY_HOST}:${dest}/"
        published=$((published + 1))
    done
    rm -fr "${build_dir}/.tmp" "${listing}"

    echo "DOWNLOADS ${PATH_TO_BUILD}: published=${published} no-server-dir=${missing}"
    [ "${published}" -gt 0 ] || exit ${RC_NONE}
    [ "${missing}" -eq 0 ] || exit ${RC_PARTIAL}
    exit 0
}

main() {
    set -o errexit
    set -o nounset
    set -o xtrace

    if [ "${MODE}" = check ]; then
        refuse_old_releases
        for target in ${TARGETS}; do
            dssh test -d "${DOWNLOADS_ROOT}/${target}/binary" || { echo "n/a ${target}: no such server release directory on downloads"; exit ${RC_NONE}; }
        done
        exit 0
    fi
    local build_dir=${UPLOAD_ROOT}/${PATH_TO_BUILD}
    if [ "${MODE}" = merge ]; then
        publish_merge "${build_dir}"
    else
        publish_server "${build_dir}"
    fi
}

main < /dev/null
