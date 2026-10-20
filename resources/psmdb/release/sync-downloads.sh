#!/bin/bash
# Publishes one UPLOAD build to downloads.percona.com: tar bundles of the packages,
# sha256sums for the source and binary tarballs, then rsync. Same layout as the
# "Sync packages to production download" stage of rel/jenkins/release-from-repository.groovy.
# Inputs, exported by the pipeline: PATH_TO_BUILD GATED RUN_ID
# GATED=true is tools and mongosh: only what push-to-repo.sh let into a repo (the
# .pushed-<RUN_ID> list) goes to downloads, so there are no trixie tools tarballs for
# a version that has no trixie server. A tarball without an OS in its name goes if
# anything was pushed at all.
# The path is <destination>/BUILDS/<product>/<release>/<tag>/<revision>/<build>.

UPLOAD_ROOT=${UPLOAD_ROOT:-/srv/UPLOAD}
DEPLOY_HOST=${DEPLOY_HOST:-jenkins-deploy.jenkins-deploy.web.r.int.percona.com}

deb_arch() {
    case $1 in
        x86_64) echo amd64 ;;
        aarch64) echo arm64 ;;
        *) echo "$1" ;;
    esac
}

allowed() {
    grep -qxF "$*" "${manifest}"
}

# drops everything from the copy in RELEASEDIR that is not listed in the manifest
prune_to_manifest() {
    local f rel dist arch os key name
    local re='-(x86_64|aarch64)\.(ol[0-9]+|[a-z]+)\.tar\.gz$'
    for f in $(find "${RELEASEDIR}/binary/tarball" -type f 2>/dev/null); do
        name=$(basename "${f}")
        [[ ${name} =~ ${re} ]] || continue
        arch=${BASH_REMATCH[1]}
        os=${BASH_REMATCH[2]}
        if [[ ${os} == ol* ]]; then
            key="rpm ${os#ol} ${arch}"
        else
            key="deb ${os} $(deb_arch "${arch}")"
        fi
        allowed "${key}" || { echo "held back: ${name} (${key})"; rm -f "${f}"; }
    done
    for f in $(find "${RELEASEDIR}/binary/redhat" -type f -name '*.rpm' 2>/dev/null); do
        rel=${f#"${RELEASEDIR}"/binary/redhat/}
        allowed "rpm ${rel%%/*} $(echo "${rel}" | cut -d/ -f2)" || { echo "held back: ${rel}"; rm -f "${f}"; }
    done
    for f in $(find "${RELEASEDIR}/binary/debian" -type f -name '*.*deb' 2>/dev/null); do
        rel=${f#"${RELEASEDIR}"/binary/debian/}
        dist=${rel%%/*}
        arch=$(basename "${f}" | sed -E 's/.*_([a-z0-9]+)\.u?deb$/\1/')
        allowed "deb ${dist} ${arch}" || { echo "held back: ${rel}"; rm -f "${f}"; }
    done
    find "${RELEASEDIR}/binary" -type d -empty -delete 2>/dev/null || true
}

main() {
    set -o errexit
    set -o nounset
    set -o xtrace

    build_dir=${UPLOAD_ROOT}/${PATH_TO_BUILD}
    PRODUCT=$(echo "${PATH_TO_BUILD}" | awk -F '/' '{print $3}')
    RELEASE=$(echo "${PATH_TO_BUILD}" | awk -F '/' '{print $4}')
    REVISION=$(echo "${PATH_TO_BUILD}" | awk -F '/' '{print $6}')
    RELEASEDIR=${build_dir}/.tmp/${PRODUCT}/${RELEASE}

    manifest=${build_dir}/.pushed-${RUN_ID}
    if [ "${GATED}" = true ] && [ ! -s "${manifest}" ]; then
        echo "nothing of ${PATH_TO_BUILD} went into a repo, so nothing goes to downloads"
        exit 0
    fi

    cd "${build_dir}"
    rm -fr "${build_dir}/.tmp"
    mkdir -p "${RELEASEDIR}"
    cp -av ./* "${RELEASEDIR}"
    [ "${GATED}" = true ] && prune_to_manifest

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

    if [ -d "${RELEASEDIR}/source/tarball" ]; then
        cd "${RELEASEDIR}/source/tarball"
        if [ -d source_tarball ]; then
            mv source_tarball/* ./
            rm -rf source_tarball
        fi
        for tarball in *tar.*; do
            [ -e "${tarball}" ] || continue
            # symlinks are skipped, like in percona-agent
            [ -h "${tarball}" ] || sha256sum "${tarball}" > "${tarball}.sha256sum"
        done
    fi
    if [ -d "${RELEASEDIR}/binary/tarball" ]; then
        cd "${RELEASEDIR}/binary/tarball"
        for tarball in *.tar.*; do
            [ -e "${tarball}" ] || continue
            [ -h "${tarball}" ] || sha256sum "${tarball}" > "${tarball}.sha256sum"
        done
    fi

    cd "${RELEASEDIR}/.."
    ln -sfn "${RELEASE}" LATEST
    cd "${build_dir}/.tmp"
    rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="*yassl*" --progress "${PRODUCT}" "${DEPLOY_HOST}:/data/downloads/"
    rm -fr "${build_dir}/.tmp" "${manifest}"
}

main < /dev/null
