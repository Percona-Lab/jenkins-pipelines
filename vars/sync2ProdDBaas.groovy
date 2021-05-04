def call(String DESTINATION) {
    node('master') {
        unstash 'uploadPath'
        def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()

        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                sh """
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                        set -o errexit
                        set -o xtrace

                        pushd ${path_to_build}/binary
                            for rhel in `ls -1 redhat`; do
                                export rpm_dest_path=/srv/repo-copy/tools/yum/${DESTINATION}/\${rhel}

                                # RPMS
                                mkdir -p \${rpm_dest_path}/RPMS
                                for arch in `ls -1 redhat/\${rhel}`; do
                                    repo_path=\${rpm_dest_path}/RPMS/\${arch}
                                    mkdir -p \${repo_path}
                                    if [ `ls redhat/\${rhel}/\${arch}/*.rpm | wc -l` -gt 0 ]; then
                                        rsync -aHv redhat/\${rhel}/\${arch}/*.rpm \${repo_path}/
                                    fi
                                    createrepo --update \${repo_path}
                                    if [ -f \${repo_path}/repodata/repomd.xml.asc ]; then
                                        rm -f \${repo_path}/repodata/repomd.xml.asc
                                    fi
                                    echo ${SIGN_PASSWORD} | gpg --detach-sign --armor \${repo_path}/repodata/repomd.xml 
                                done

                                # SRPMS
                                mkdir -p \${rpm_dest_path}/SRPMS
                                if [ `find ../source/redhat -name '*.src.rpm' | wc -l` -gt 0 ]; then
                                    cp -v `find ../source/redhat -name '*.src.rpm' \${find_exclude}` \${rpm_dest_path}/SRPMS/
                                fi
                                createrepo --update \${rpm_dest_path}/SRPMS
                                if [ -f \${rpm_dest_path}/SRPMS/repodata/repomd.xml.asc ]; then
                                    rm -f \${rpm_dest_path}/SRPMS/repodata/repomd.xml.asc
                                fi
                                echo ${SIGN_PASSWORD} | gpg --detach-sign --armor \${rpm_dest_path}/SRPMS/repodata/repomd.xml 
                            done

                            if [ "x${DESTINATION}" == "xrelease" ]; then
                                DESTINATION=main
                            fi
                            for dist in `ls -1 debian`; do
                                for deb in `find debian/\${dist} -name '*.deb'`; do
                                 env PATH=/usr/local/reprepro5/bin:${PATH} repopush --remove-package --gpg-pass ${SIGN_PASSWORD} --package \${deb} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/tools/apt
                                done

                                # source deb
                                #for dsc in `find ../source -name '*.dsc'`; do
                                # env PATH=/usr/local/reprepro5/bin:${PATH} repopush --remove-package --gpg-pass ${SIGN_PASSWORD} --package \${dsc} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/tools/apt
                                #done
                            done
                        popd

                        if [ "x${DESTINATION}" == "xmain" ]; then
                            DESTINATION=release
                        fi
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/tools/yum/${DESTINATION}/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/tools/yum/${DESTINATION}/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/tools/apt/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/tools/apt/

                        # Clean CDN cache for repo.percona.com
                        bash -xe /usr/local/bin/clear_cdn_cache.sh
                    '
                """
            }
        }
    }
}
