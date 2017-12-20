def call(String DESTINATION) {
    sh """
        export path_to_build="UPLOAD/pmm/${JOB_NAME}/\$(cat shortCommit)-${BUILD_NUMBER}"

        pushd ${PATH_TO_BUILD}/binary
            for rhel in $(ls -1 redhat); do
                # RPMS
                mkdir -p /srv/repo-copy/${DESTINATION}/\${rhel}/RPMS
                for arch in $(ls -1 redhat/\${rhel}); do
                    REPO_PATH=/srv/repo-copy/${DESTINATION}/\${rhel}/RPMS/\${arch}
                    mkdir -p \${REPO_PATH}
                    cp -av redhat/\${rhel}/\${arch}/*.rpm \${REPO_PATH}/
                    createrepo --update \${REPO_PATH}
                done

                # SRPMS
                SRC_RPM=\$(find ../source/redhat -name '*.src.rpm')
                REPO_PATH=/srv/repo-copy/${DESTINATION}/\${rhel}/SRPMS
                mkdir -p \${REPO_PATH}
                cp -v \${SRC_RPM} \${REPO_PATH}/
                createrepo --update \${REPO_PATH}
            done
        popd
    """
}
