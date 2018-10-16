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
                                export dest_path=/srv/repo-copy/${DESTINATION}/\${rhel}

                                # RPMS
                                mkdir -p \${dest_path}/RPMS
                                for arch in `ls -1 redhat/\${rhel}`; do
                                    repo_path=\${dest_path}/RPMS/\${arch}
                                    mkdir -p \${repo_path}
                                    if [ `ls redhat/\${rhel}/\${arch}/*.rpm | wc -l` -gt 0 ]; then
                                        cp -av redhat/\${rhel}/\${arch}/*.rpm \${repo_path}/
                                    fi
                                    createrepo --update \${repo_path}
                                done

                                # SRPMS
                                mkdir -p \${dest_path}/SRPMS
                                if [ `find ../source/redhat -name '*.src.rpm' | wc -l` -gt 0 ]; then
                                    cp -v `find ../source/redhat -name '*.src.rpm'` \${dest_path}/SRPMS/
                                fi
                                createrepo --update \${dest_path}/SRPMS
                            done

                            for dist in `ls -1 debian`; do
                                for deb in `find debian/\${dist} -name '*.deb'`; do
                                    repopush --gpg-pass ${SIGN_PASSWORD} --package \${deb} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/apt
                                done

                                # source deb
                                for dsc in `find ../source -name '*.dsc'`; do
                                    repopush --gpg-pass ${SIGN_PASSWORD} --package \${dsc} --verbose --component ${DESTINATION} --codename \${dist} --repo-path /srv/repo-copy/apt
                                done
                            done
                        popd

                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/${DESTINATION}/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/${DESTINATION}/
                        rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                            /srv/repo-copy/apt/ \
                            10.10.9.209:/www/repo.percona.com/htdocs/apt/
                    '
                """
            }
        }
    }
}
