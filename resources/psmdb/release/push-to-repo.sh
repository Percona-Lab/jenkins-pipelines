#!/bin/bash
# Pushes one UPLOAD build (server, tools or mongosh) into one PSMDB repo/component.
# Runs on repo.ci; the pipeline exports the inputs and feeds this file to `bash -s`:
#   REPO COMPONENT PATH_TO_BUILD GATE SIGN_PASSWORD
#   PUSHRHEL10 PUSHFOCAL PUSHTRIXIE PUSHRESOLUTE
#   REMOVE_BEFORE_PUSH REMOVE_LOCKFILE SKIP_RPM_PUSH SKIP_DEB_PUSH
#
# GATE=none   push as is, this is the server itself
# GATE=meta   push only where percona-server-mongodb is already in this repo/component
# GATE=tools  same, and that percona-server-mongodb must not pin the tools version,
#             otherwise yum update fails on every machine that has PSMDB installed
# Nothing is ever removed from the repo.
#
# exit: 0 everything pushed, 42 something held back, 43 nothing pushed

UPLOAD_ROOT=${UPLOAD_ROOT:-/srv/UPLOAD}
REPO_ROOT=${REPO_ROOT:-/srv/repo-copy}
REPREPRO_DIR=${REPREPRO_DIR:-/usr/local/reprepro5/bin}
REF=percona-server-mongodb
RC_PARTIAL=42
RC_NONE=43

pushed=0
skipped=0
na=0

sign_repo() {
    createrepo --update --no-database "$1"
    rm -f "$1/repodata/repomd.xml.asc"
    gpg --detach-sign --armor --passphrase "${SIGN_PASSWORD}" "$1/repodata/repomd.xml"
}

rpm_allowed() {
    local rhel=$1 arch=$2 dir meta reqs
    [ "${GATE}" = none ] && return 0
    dir=${yum_root}/${rhel}/RPMS/${arch}
    if [ ! -d "${yum_root}/${rhel}/RPMS" ]; then
        echo "n/a el${rhel}/${arch}: ${REPO}/${comp} does not ship it"
        na=$((na + 1))
        return 1
    fi
    meta=$(ls -1 "${dir}/${REF}"-[0-9]*.rpm 2>/dev/null | sort -V | tail -1)
    if [ -z "${meta}" ]; then
        echo "n/a el${rhel}/${arch}: no ${REF} in ${REPO}/${comp}"
        na=$((na + 1))
        return 1
    fi
    if [ "${GATE}" = tools ]; then
        if ! reqs=$(rpm -qp --requires "${meta}" 2>/dev/null); then
            echo "SKIP el${rhel}/${arch}: cannot read the requires of $(basename "${meta}")"
            skipped=$((skipped + 1))
            return 1
        fi
        if echo "${reqs}" | grep -qE "^${REF}-tools[[:space:]]*="; then
            echo "SKIP el${rhel}/${arch}: $(basename "${meta}") still pins the tools version, publish the PSMDB build with the unversioned Requires first"
            skipped=$((skipped + 1))
            return 1
        fi
    fi
    return 0
}

push_rpms() {
    local rhel arch src dir rhel_pushed
    [ -d "${build_dir}/binary/redhat" ] || return 0
    for rhel in $(ls -1 "${build_dir}/binary/redhat"); do
        case ${rhel} in
            6) continue ;;
            10) [ "${PUSHRHEL10}" = YES ] || continue ;;
        esac
        rhel_pushed=0
        for arch in $(ls -1 "${build_dir}/binary/redhat/${rhel}"); do
            src=${build_dir}/binary/redhat/${rhel}/${arch}
            [ "$(ls -1 "${src}"/*.rpm 2>/dev/null | wc -l)" -gt 0 ] || continue
            rpm_allowed "${rhel}" "${arch}" || continue
            dir=${yum_root}/${rhel}/RPMS/${arch}
            mkdir -p "${dir}"
            rsync -aHv "${src}"/*.rpm "${dir}/"
            sign_repo "${dir}"
            pushed=$((pushed + 1))
            rhel_pushed=1
        done
        # no SRPM where no binary went in
        [ "${rhel_pushed}" = 1 ] || continue
        if [ "$(find "${build_dir}/source/redhat" -name '*.src.rpm' 2>/dev/null | wc -l)" -gt 0 ]; then
            mkdir -p "${yum_root}/${rhel}/SRPMS"
            find "${build_dir}/source/redhat" -name '*.src.rpm' -exec cp -v {} "${yum_root}/${rhel}/SRPMS/" \;
            sign_repo "${yum_root}/${rhel}/SRPMS"
        fi
    done
}

deb_list() {
    "${REPREPRO_DIR}/reprepro" --list-format '${package}_${version}_${architecture}.deb\n' \
        -b "${apt_root}" -C "${deb_comp}" list "$1" 2>/dev/null
}

deb_allowed() {
    local dist=$1 name=$2 arch pat listing line ver march meta_deb deps
    [ "${GATE}" = none ] && return 0
    arch=$(echo "${name}" | sed -E 's/.*_([a-z0-9]+)\.u?deb$/\1/')
    pat=${arch}
    [ "${arch}" = all ] && pat='[a-z0-9]+'
    listing=$(deb_list "${dist}" | grep -E "^${REF}_.*_${pat}\.deb$" || true)
    if [ -z "${listing}" ]; then
        echo "n/a ${name}: no ${REF} for ${dist}/${arch} in ${REPO}/${deb_comp}"
        na=$((na + 1))
        return 1
    fi
    [ "${GATE}" = tools ] || return 0
    line=$(echo "${listing}" | sort -V -t_ -k2,2 | tail -1)
    ver=$(echo "${line}" | cut -d_ -f2 | sed -E 's/^[0-9]+://')
    march=$(echo "${line}" | cut -d_ -f3 | sed 's/\.deb$//')
    meta_deb=$(find "${apt_root}/pool" -name "${REF}_${ver}_${march}.deb" 2>/dev/null | head -1)
    if [ -z "${meta_deb}" ] || ! command -v dpkg-deb >/dev/null 2>&1 || ! deps=$(dpkg-deb -f "${meta_deb}" Depends 2>/dev/null); then
        echo "SKIP ${name}: cannot inspect ${REF} ${ver} for ${dist}/${march}"
        skipped=$((skipped + 1))
        return 1
    fi
    if echo "${deps}" | tr ',' '\n' | grep -qE "(^|[[:space:]])${REF}-tools[[:space:]]*\("; then
        echo "SKIP ${name}: $(basename "${meta_deb}") still pins the tools version, publish the PSMDB build with the unversioned Depends first"
        skipped=$((skipped + 1))
        return 1
    fi
    return 0
}

# prints the first file of a source package (.dsc) that is in the pool already under the same
# name with other contents, and returns 0. That is a rebuild of the same upstream version whose
# orig tarball is not the same: reprepro refuses it, and a failed repopush leaves its lock behind.
dsc_conflict() {
    local dsc=$1 sum name existing files
    files=$(awk '/^Checksums-Sha256:/ {f=1; next} /^[^ ]/ {f=0} f {print $1, $3}' "${dsc}")
    [ -n "${files}" ] || return 1
    while read -r sum name; do
        existing=$(find "${apt_root}/pool" -name "${name}" 2>/dev/null | head -1)
        [ -n "${existing}" ] || continue
        if [ "$(sha256sum "${existing}" | cut -d' ' -f1)" != "${sum}" ]; then
            echo "${name}"
            return 0
        fi
    done <<< "${files}"
    return 1
}

push_debs() {
    local dist dists deb dsc line list conflict args=""
    [ -d "${build_dir}/binary/debian" ] || return 0
    list=$(mktemp)
    [ "${REMOVE_BEFORE_PUSH}" = true ] && args="--remove-package"

    for dist in $(ls -1 "${build_dir}/binary/debian"); do
        case ${dist} in
            focal) [ "${PUSHFOCAL}" = YES ] || continue ;;
            trixie) [ "${PUSHTRIXIE}" = YES ] || continue ;;
            resolute) [ "${PUSHRESOLUTE}" = YES ] || continue ;;
        esac
        for deb in $(find "${build_dir}/binary/debian/${dist}" -type f -name '*.*deb'); do
            if deb_allowed "${dist}" "$(basename "${deb}")"; then
                echo "${dist} ${deb}" >> "${list}"
            fi
        done
    done

    # sources go into the release repo only, and only for the dists that get binaries
    if [ "${comp}" = release ] && [ -d "${build_dir}/source/debian" ]; then
        dists=$(awk '{print $1}' "${list}" | sort -u)
        for dsc in $(find "${build_dir}/source/debian" -type f -name '*.dsc'); do
            # the pool is shared by all dists, so a conflict holds for all of them
            if conflict=$(dsc_conflict "${dsc}"); then
                echo "SKIP source $(basename "${dsc}"): ${conflict} is in ${REPO} with other contents (same upstream version rebuilt), the source stays on downloads and in the SRPM"
                skipped=$((skipped + 1))
                continue
            fi
            for dist in ${dists}; do
                echo "dsc ${dsc} -> ${dist}"
                repopush --gpg-pass="${SIGN_PASSWORD}" --package="${dsc}" --repo-path="${apt_root}" \
                    --component="${deb_comp}" --codename="${dist}" --verbose ${args} || {
                    # a failed push that left the reprepro lock would break every push after it
                    if [ -e "${apt_root}/db/lockfile" ]; then
                        echo "ERROR: ${dsc} failed and left ${apt_root}/db/lockfile, run again with REMOVE_LOCKFILE"
                        exit 1
                    fi
                    echo "WARN: ${dsc} was not pushed to ${dist}, already there?"
                }
            done
        done
    fi

    # bash before 4.4 fails on an empty array under nounset
    [ -s "${list}" ] || { rm -f "${list}"; return 0; }
    mapfile -t lines < "${list}"
    for line in "${lines[@]}"; do
        dist=${line%% *}
        deb=${line#* }
        repopush --gpg-pass="${SIGN_PASSWORD}" --package="${deb}" --repo-path="${apt_root}" \
            --component="${deb_comp}" --codename="${dist}" --verbose ${args}
        pushed=$((pushed + 1))
    done
    rm -f "${list}"
}

main() {
    set -o errexit
    set -o nounset
    set -o xtrace

    export PATH=${REPREPRO_DIR}:${PATH}
    build_dir=${UPLOAD_ROOT}/${PATH_TO_BUILD}
    comp=$(echo "${COMPONENT}" | tr '[:upper:]' '[:lower:]')
    deb_comp=${comp}
    [ "${comp}" = release ] && deb_comp=main
    yum_root=${REPO_ROOT}/${REPO}/yum/${comp}
    apt_root=${REPO_ROOT}/${REPO}/apt

    # a typo in REPO must not create a new directory under repo-copy
    if [ ! -d "${REPO_ROOT}/${REPO}" ]; then
        echo "ERROR: no such repo: ${REPO_ROOT}/${REPO}"
        exit 1
    fi
    if [ ! -d "${build_dir}/binary" ]; then
        echo "ERROR: ${build_dir}/binary does not exist"
        exit 1
    fi

    # same lock file as vars/sync2ProdAutoBuild*.groovy, so autobuilds and this job queue up
    mkdir -p "${REPO_ROOT}/.publish-locks"
    exec 9>> "${REPO_ROOT}/.publish-locks/${REPO//\//_}.lock"
    if ! flock -w 5400 9; then
        echo "ERROR: could not get the publish lock for ${REPO}"
        exit 1
    fi

    if [ "${REMOVE_LOCKFILE}" = true ]; then
        rm -vf "${apt_root}/db/lockfile"
    fi

    [ "${SKIP_RPM_PUSH}" = true ] || push_rpms
    [ "${SKIP_DEB_PUSH}" = true ] || push_debs

    echo "GATE ${REPO}/${comp} ${GATE}: pushed=${pushed} skipped=${skipped} n/a=${na}"
    if [ "${pushed}" -eq 0 ]; then
        echo "nothing pushed to ${REPO}/${comp}"
        [ "${GATE}" = none ] && exit 1
        exit ${RC_NONE}
    fi
    [ "${skipped}" -eq 0 ] || exit ${RC_PARTIAL}
    exit 0
}

# stdin is /dev/null for everything inside, so no command can eat the rest of the script
main < /dev/null
