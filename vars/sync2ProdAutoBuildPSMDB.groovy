// Same as sync2ProdAutoBuild, plus a gate: an artifact is published only for the OS/arch
// combinations that already carry the PSMDB metapackage in the same repo and component.
// Used by the PSMDB family that shares the psmdb-* repos -- server, mongosh and the
// database tools -- whose versions no longer track each other (PSMDB-1944).
//
// It only ever declines to push. Nothing is removed, overwritten or otherwise changed in
// an existing repository.
//
// Consequence: the operator pushes the server first and the rest second. Wrong order just
// skips everything, and re-running the push is cheap.
//
// Returns 'full', 'partial' (the gate held something back) or 'none' (nothing pushed).
// An OS/arch with no metapackage at all is not something this repo ships, so it is
// logged as n/a and does not make the push partial.
def call(String CLOUD_NAME, String REPO_NAME, String DESTINATION) {
    String REFERENCE_PACKAGE = 'percona-server-mongodb'
    // exit codes the remote script uses to report back; anything else is a real failure
    int RC_PARTIAL = 42
    int RC_NONE = 43
    def result = null
    def nodeLabel = (CLOUD_NAME == 'Hetzner') ? 'launcher-x64' : 'micro-amazon'
    node(nodeLabel) {
        unstash 'uploadPath'
        def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()

        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                def rc = sh(returnStatus: true, script: """
                    cat /etc/hosts > ./hosts
                    echo '10.30.6.9 repo.ci.percona.com' >> ./hosts
                    sudo cp ./hosts /etc || true

                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                        set -o errexit
                        set -o xtrace

                        pushd ${path_to_build}/binary
                            # the gate skips rather than fails, so count what actually moved:
                            # a run that pushes nothing means the operator got ahead of the
                            # PSMDB build, and that must not look like success.
                            gate_pushed=0
                            gate_skipped=0
                            gate_na=0
                            if [ "x${REPO_NAME}" == "xpsmdb-50" -o "x${REPO_NAME}" == "xpsmdb-60" ]; then
                                createrepo_opts=" --no-database "
                            fi

                            for rhel in `ls -1 redhat`; do
                                export rpm_dest_path=/srv/repo-copy/${REPO_NAME}/yum/${DESTINATION}/\${rhel}
                                rhel_pushed=0
                                if [ ! -d \${rpm_dest_path}/RPMS ]; then
                                    echo "n/a \${rhel}: ${REPO_NAME}/${DESTINATION} does not ship it"
                                    gate_na=\$((gate_na+\$(ls -1 redhat/\${rhel} | wc -l)))
                                    continue
                                fi

                                # RPMS
                                mkdir -p \${rpm_dest_path}/RPMS
                                for arch in `ls -1 redhat/\${rhel}`; do
                                    # gate 1: the metapackage must already be here
                                    meta_rpm=\$(ls -1 \${rpm_dest_path}/RPMS/\${arch}/${REFERENCE_PACKAGE}-[0-9]*.rpm 2>/dev/null | sort -V | tail -1)
                                    if [ -z "\${meta_rpm}" ]; then
                                        echo "n/a \${rhel}/\${arch}: no ${REFERENCE_PACKAGE} in ${REPO_NAME}/${DESTINATION}"
                                        gate_na=\$((gate_na+1))
                                        continue
                                    fi
                                    # gate 2: and it must no longer pin the tools version. pushing into a
                                    # component whose newest metapackage still says
                                    # "Requires: ...-tools = <psmdb ver>" breaks `yum update` outright for
                                    # everyone who has PSMDB installed -- the whole transaction aborts.
                                    if rpm -qp --requires "\${meta_rpm}" 2>/dev/null | grep -qE "^${REFERENCE_PACKAGE}-tools[[:space:]]*="; then
                                        echo "SKIP \${rhel}/\${arch}: \$(basename \${meta_rpm}) still pins the tools version -- publish the PSMDB build with the unversioned Requires first"
                                        gate_skipped=\$((gate_skipped+1))
                                        continue
                                    fi
                                    repo_path=\${rpm_dest_path}/RPMS/\${arch}
                                    mkdir -p \${repo_path}
                                    if [ `ls redhat/\${rhel}/\${arch}/*.rpm | wc -l` -gt 0 ]; then
                                        rsync -aHv redhat/\${rhel}/\${arch}/*.rpm \${repo_path}/
                                    fi
                                    gate_pushed=\$((gate_pushed+1)); rhel_pushed=1
                                    createrepo --update \${createrepo_opts} \${repo_path}
                                    if [ -f \${repo_path}/repodata/repomd.xml.asc ]; then
                                        rm -f \${repo_path}/repodata/repomd.xml.asc
                                    fi
                                    gpg --detach-sign --armor --passphrase ${SIGN_PASSWORD} \${repo_path}/repodata/repomd.xml 
                                done

                                # SRPMS follow the binaries: publishing a source rpm into a
                                # component that rejected every binary leaves the repo in a
                                # state no build produced.
                                if [ \${rhel_pushed} -eq 0 ]; then
                                    echo "SKIP \${rhel}/SRPMS: nothing binary was published for this rhel"
                                    continue
                                fi
                                # SRPMS
                                mkdir -p \${rpm_dest_path}/SRPMS
                                if [ `find ../source/redhat -name '*.src.rpm' | wc -l` -gt 0 ]; then
                                    cp -v `find ../source/redhat -name '*.src.rpm' \${find_exclude}` \${rpm_dest_path}/SRPMS/
                                fi
                                createrepo --update \${createrepo_opts} \${rpm_dest_path}/SRPMS
                                if [ -f \${rpm_dest_path}/SRPMS/repodata/repomd.xml.asc ]; then
                                    rm -f \${rpm_dest_path}/SRPMS/repodata/repomd.xml.asc
                                fi
                                gpg --detach-sign --armor --passphrase ${SIGN_PASSWORD} \${rpm_dest_path}/SRPMS/repodata/repomd.xml 
                            done

                            if [ "x${DESTINATION}" == "xrelease" ]; then
                                DESTINATION=main
                            fi
                            for dist in `ls -1 debian`; do
                                for deb in `find debian/\${dist} -name '*.deb'`; do
                                 pkg_fname=\$(basename \${deb})
                                 # gate: arch comes from the filename (_amd64.deb / _arm64.deb)
                                 deb_arch=\$(echo \${pkg_fname} | sed -E '"'"'s/.*_([a-z0-9]+)\\.deb\$/\\1/'"'"')
                                 if ! /usr/local/reprepro5/bin/reprepro --list-format '"'"'\${package}_\${version}_\${architecture}.deb\\n'"'"' -Vb /srv/repo-copy/${REPO_NAME}/apt -C ${DESTINATION} list \${dist} | grep -q "^${REFERENCE_PACKAGE}_.*_\${deb_arch}\\.deb"; then
                                     echo "n/a \${pkg_fname}: no ${REFERENCE_PACKAGE} for \${dist}/\${deb_arch} in ${REPO_NAME}/${DESTINATION}"
                                     gate_na=\$((gate_na+1))
                                     continue
                                 fi
                                 # gate 2: the metapackage in this component must no longer pin the tools
                                 # version. apt only holds the package back rather than erroring, so the
                                 # symptom here is silent: the fix never reaches anyone.
                                 meta_ver=\$(/usr/local/reprepro5/bin/reprepro --list-format '"'"'\${package}_\${version}_\${architecture}.deb\\n'"'"' -Vb /srv/repo-copy/${REPO_NAME}/apt -C ${DESTINATION} list \${dist} | grep "^${REFERENCE_PACKAGE}_" | grep "_\${deb_arch}\\.deb" | sed -E '"'"'s/^[^_]+_([^_]+)_.*/\\1/'"'"' | sort -V | tail -1)
                                 meta_deb=\$(find /srv/repo-copy/${REPO_NAME}/apt/pool -name "${REFERENCE_PACKAGE}_\${meta_ver}_\${deb_arch}.deb" 2>/dev/null | head -1)
                                 if [ -z "\${meta_deb}" ] || ! command -v dpkg-deb >/dev/null 2>&1; then
                                     echo "SKIP \${pkg_fname}: cannot inspect ${REFERENCE_PACKAGE} \${meta_ver} for \${dist}/\${deb_arch}"
                                     gate_skipped=\$((gate_skipped+1))
                                     continue
                                 fi
                                 if dpkg-deb -f "\${meta_deb}" Depends | tr '"'"','"'"' '"'"'\\n'"'"' | grep -qE "${REFERENCE_PACKAGE}-tools[[:space:]]*\\("; then
                                     echo "SKIP \${pkg_fname}: \$(basename \${meta_deb}) still pins the tools version -- publish the PSMDB build with the unversioned Depends first"
                                     gate_skipped=\$((gate_skipped+1))
                                     continue
                                 fi
                                 EC=0
                                 /usr/local/reprepro5/bin/reprepro --list-format '"'"'\${package}_\${version}_\${architecture}.deb\\n'"'"' -Vb /srv/repo-copy/${REPO_NAME}/apt -C ${DESTINATION} list \${dist} | sed -re "s|[0-9]:||" | grep ^\${pkg_fname} > /dev/null || EC=\$?
                                 REPOPUSH_ARGS=""
                                 if [ \${EC} -eq 0 ]; then
                                     REPOPUSH_ARGS=" --remove-package "
                                 fi
                                 gate_pushed=\$((gate_pushed+1))
                                 env PATH=/usr/local/reprepro5/bin:${PATH} repopush \${REPOPUSH_ARGS} --gpg-pass ${SIGN_PASSWORD} --package \${deb} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/${REPO_NAME}/apt
                                done
                            done
                            echo "GATE ${REPO_NAME}/${DESTINATION}: pushed=\${gate_pushed} skipped=\${gate_skipped} n/a=\${gate_na}"
                            # nothing changed, so there is nothing to sync either
                            if [ \${gate_pushed} -eq 0 ]; then
                                echo "nothing pushed to ${REPO_NAME}/${DESTINATION}: publish the PSMDB build with the unversioned tools dependency first"
                                exit ${RC_NONE}
                            fi
                        popd

                        if [ "x${DESTINATION}" == "xmain" ]; then
                            DESTINATION=release
                        fi

                        # Update /srv/repo-copy/version
                        date +%s > /srv/repo-copy/version

                        rsync -avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${REPO_NAME}/yum/${DESTINATION}/ \
                            10.30.9.32:/www/repo.percona.com/htdocs/${REPO_NAME}/yum/${DESTINATION}/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${REPO_NAME}/apt/ \
                            10.30.9.32:/www/repo.percona.com/htdocs/${REPO_NAME}/apt/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/version \
                            10.30.9.32:/www/repo.percona.com/htdocs/

                        # after the sync: what did get through has to reach the mirror
                        if [ \${gate_skipped} -gt 0 ]; then
                            exit ${RC_PARTIAL}
                        fi
                    '
                """)
                if (rc == 0) {
                    result = 'full'
                } else if (rc == RC_PARTIAL) {
                    result = 'partial'
                } else if (rc == RC_NONE) {
                    result = 'none'
                } else {
                    error("push to ${REPO_NAME}/${DESTINATION} failed, rc=${rc}")
                }
            }
        }
    }
    return result
}
