def call(String DESTINATION, String SYNC_PMM_CLIENT, boolean ONLY_LATEST=false) {
    node('master') {
        unstash 'uploadPath'
        def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()

        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                sh """
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                        set -o errexit
                        set -o xtrace

                        pushd ${path_to_build}/binary
                            if [ "${SYNC_PMM_CLIENT}" == 'no' ]; then
                                rsync_exclude=" --exclude pmm2-client-*"
                                find_exclude="! -name pmm2-client-*"
                            fi

                            for rhel in \$(ls -1 redhat); do
                                # skip synchronization of el8/el6 repos in case of pmm server rpms sync
                                if [ "${SYNC_PMM_CLIENT}" == 'no' ] && [ "\${rhel}" -eq '8' -o "\${rhel}" -eq '6' ]; then
                                    continue
                                fi

                                export dest_path=/srv/repo-copy/${DESTINATION}/\${rhel}

                                # RPMS
                                mkdir -p \${dest_path}/RPMS
                                for arch in \$(ls -1 redhat/\${rhel}); do
                                    repo_path=\${dest_path}/RPMS/\${arch}
                                    mkdir -p \${repo_path}
                                    if [ \$(ls redhat/\${rhel}/\${arch}/*.rpm | wc -l) -gt 0 ]; then
                                        rsync -aHv redhat/\${rhel}/\${arch}/*.rpm \${rsync_exclude} \${repo_path}/
                                    fi
                                    createrepo --update \${repo_path}
                                    if [ -f \${repo_path}/repodata/repomd.xml.asc ]; then
                                        rm -f \${repo_path}/repodata/repomd.xml.asc
                                    fi
                                    gpg --detach-sign --armor --passphrase ${SIGN_PASSWORD} \${repo_path}/repodata/repomd.xml
                                done

                                # SRPMS
                                mkdir -p \${dest_path}/SRPMS
                                if [ \$(find ../source/redhat -name '*.src.rpm' \${find_exclude}  | wc -l) -gt 0 ]; then
                                    cp -v \$(find ../source/redhat -name '*.src.rpm' \${find_exclude}) \${dest_path}/SRPMS/
                                fi
                                createrepo --update \${dest_path}/SRPMS
                                if [ -f \${dest_path}/SRPMS/repodata/repomd.xml.asc ]; then
                                    rm -f \${dest_path}/SRPMS/repodata/repomd.xml.asc
                                fi
                                gpg --detach-sign --armor --passphrase ${SIGN_PASSWORD} \${dest_path}/SRPMS/repodata/repomd.xml
                            done

                            for dist in \$(ls -1 debian); do
                                for deb in \$(find debian/\${dist} -name '*.deb'); do
                                    repopush ${repopush_args} --gpg-pass ${SIGN_PASSWORD} --package \${deb} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/apt
                                done
                            done
                        popd

                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${DESTINATION}/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/${DESTINATION}/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/apt/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/apt/

                        # Clean CDN cache for repo.percona.com
                        bash -xe /usr/local/bin/clear_cdn_cache.sh
ENDSSH
                """
            }
        }
    }
}
