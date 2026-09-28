def call(String DESTINATION, String UPLOAD_PATH, String PMM_CLIENT_SUBPATH = 'pmm2-client') {
    node('master') {
        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                sh """
                    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                        set -o errexit
                        set -o xtrace

                        pushd ${UPLOAD_PATH}/binary

                            for rhel in \$(ls -1 redhat); do
                                export dest_path=/srv/repo-copy/${PMM_CLIENT_SUBPATH}/yum/${DESTINATION}/\${rhel}

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
                            for dist in `ls -1 debian`; do
                                for deb in `find debian/\${dist} -name '*.deb'`; do
                                 pkg_fname=\$(basename \${deb} | awk -F'_' '{print \$2}' )
                                 EC=0
                                 /usr/local/reprepro5/bin/reprepro -Vb /srv/repo-copy/${PMM_CLIENT_SUBPATH}/apt -C ${DESTINATION} list \${dist} | sed -re "s|[0-9]:||" | grep \${pkg_fname} > /dev/null || EC=\$?
                                 REPOPUSH_ARGS=""
                                 if [ \${EC} -eq 0 ]; then
                                     REPOPUSH_ARGS=" --remove-package "
                                 fi
                                 env PATH=/usr/local/reprepro5/bin:${PATH} repopush \${REPOPUSH_ARGS} --gpg-pass ${SIGN_PASSWORD} --package \${deb} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/${PMM_CLIENT_SUBPATH}/apt
                                done
                            done

                        popd

                        # Update /srv/repo-copy/version
                        date +%s > /srv/repo-copy/version

                        rsync -avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${PMM_CLIENT_SUBPATH}/yum/${DESTINATION}/ \
                            10.30.9.32:/www/repo.percona.com/htdocs/${PMM_CLIENT_SUBPATH}/yum/${DESTINATION}/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${PMM_CLIENT_SUBPATH}/apt/ \
                            10.30.9.32:/www/repo.percona.com/htdocs/${PMM_CLIENT_SUBPATH}/apt/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=.nfs* --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/version \
                            10.30.9.32:/www/repo.percona.com/htdocs/
ENDSSH
                """
            }
        }
    }
}
