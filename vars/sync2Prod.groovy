def call(String DESTINATION) {
    node('master') {
        unstash 'gitCommit'
        def path_to_build = sh(returnStdout: true, script: "echo UPLOAD/pmm/${JOB_NAME}/\$(cat shortCommit)-${BUILD_NUMBER}").trim()

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
                            src_rpm=\$(find ../source/redhat -name '*.src.rpm')
                            mkdir -p \${dest_path}/SRPMS
                            cp -v \${src_rpm} \${dest_path}/SRPMS/
                            createrepo --update \${dest_path}/SRPMS
                        done
                    popd

                    rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                        /srv/repo-copy/${DESTINATION}/ \
                        10.10.9.209:/www/repo.percona.com/htdocs/${DESTINATION}/
                '
            """
        }
    }
}
