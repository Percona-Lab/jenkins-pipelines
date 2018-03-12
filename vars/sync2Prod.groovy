def call(String DESTINATION) {
    node('master') {
        unstash 'gitCommit'
        def path_to_build = sh(returnStdout: true, script: "echo UPLOAD/pmm/${JOB_NAME}/\$(cat shortCommit)-${BUILD_NUMBER}").trim()

        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                sh """
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                        pushd ${path_to_build}/binary
                            for rhel in \$(ls -1 redhat); do
                                export dest_path=/srv/repo-copy/${DESTINATION}/\${rhel}

                                # RPMS
                                mkdir -p \${dest_path}/RPMS
                                for arch in \$(ls -1 redhat/\${rhel}); do
                                    repo_path=\${dest_path}/RPMS/\${arch}
                                    mkdir -p \${repo_path}
                                    cp -av redhat/\${rhel}/\${arch}/*.rpm \${repo_path}/
                                    createrepo --update \${repo_path}
                                done

                                # SRPMS
                                src_rpm=\$(find ../source/redhat -type f -name '*.src.rpm')
                                mkdir -p \${dest_path}/SRPMS
                                cp -v \${src_rpm} \${dest_path}/SRPMS/
                                createrepo --update \${dest_path}/SRPMS
                            done

                            for dist in \$(ls -1 debian); do
                                for deb in \$(find debian/\${dist} -type f -name '*.deb'); do
                                    repopush --password ${SIGN_PASSWORD} --deb \${deb} --verbose --repo ${DESTINATION}
                                done

                                # source deb
                                dsc=\$(find ../source -type f -name '*.dsc')
                                repopush --password ${SIGN_PASSWORD} --dsc \${dsc} --verbose --repo ${DESTINATION} --distribution \${dist}
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
