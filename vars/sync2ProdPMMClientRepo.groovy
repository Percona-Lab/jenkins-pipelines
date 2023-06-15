def call(String DESTINATION, String SYNC_PMM_CLIENT) {
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

                            for rhel in \$(ls -1 redhat); do
                                export dest_path=/srv/repo-copy/pmm2-client/yum/${DESTINATION}/\${rhel}

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
                                 /usr/local/reprepro5/bin/reprepro -Vb /srv/repo-copy/pmm2-client/apt -C ${DESTINATION} list \${dist} | sed -re "s|[0-9]:||" | grep \${pkg_fname} > /dev/null || EC=\$?
                                 REPOPUSH_ARGS=""
                                 if [ \${EC} -eq 0 ]; then
                                     REPOPUSH_ARGS=" --remove-package "
                                 fi
                                 env PATH=/usr/local/reprepro5/bin:${PATH} repopush \${REPOPUSH_ARGS} --gpg-pass ${SIGN_PASSWORD} --package \${deb} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/pmm2-client/apt
                                done
                            done

                        popd

                        # Update /srv/repo-copy/version
                        date +%s > /srv/repo-copy/version

                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/pmm2-client/yum/${DESTINATION}/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/pmm2-client/yum/${DESTINATION}/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/pmm2-client/apt/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/pmm2-client/apt/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/version \
                            10.10.9.209:/www/repo.percona.com/htdocs/
ENDSSH
                """
            }
        }
    }
}
