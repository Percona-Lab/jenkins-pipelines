def call(String DESTINATION, String SYNC_PMM_CLIENT, String OS_VERSION) {
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
                            rsync_exclude=" --exclude pmm2-client-*"
                            find_exclude="! -name pmm2-client-*"

                            for rhel in \$(ls -1 redhat); do
                                # skip synchronization of el8/el6/{el7|el9} repos based on OS_VERSION in case of pmm server rpms sync
                                if [ "${SYNC_PMM_CLIENT}" == 'no' ] && [ "${OS_VERSION}" != "\${rhel}" ]; then
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

                        popd

                        # Update /srv/repo-copy/version
                        date +%s > /srv/repo-copy/version

                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${DESTINATION}/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/${DESTINATION}/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/apt/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/apt/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/version \
                            10.10.9.209:/www/repo.percona.com/htdocs/
ENDSSH
                """
            }
        }
    }
}
