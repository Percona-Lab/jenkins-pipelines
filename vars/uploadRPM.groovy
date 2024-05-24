def call() {
    node('master') {
        deleteDir()
        unstash 'rpms'
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                export path_to_build=`cat uploadPath`

                # Upload source packages
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    mkdir -p \${path_to_build}/source/redhat

                if [ `find . -name '*.src.rpm' | wc -l` -gt 0 ]; then
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find . -name '*.src.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/source/redhat/
                fi

                # Upload binary packages
                RHEL=("6" "7" "8" "9")
                for rhel in \${RHEL[*]}; do
                    for rpm in \$(find . -name "*.el\${rhel}.*.rpm"); do
                        arch=\$(echo \${rpm} | sed -re "s/.*\\.el\${rhel}\\.(noarch|x86_64|aarch64)\\.rpm/\\1/")
                        if [ "\${arch}" = "x86_64" ] || [ "\${arch}" = "aarch64" ]; then
                            path_to_dist=\${path_to_build}/binary/redhat/\${rhel}/\${arch}
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                                mkdir -p \${path_to_dist}
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \${rpm} ${USER}@repo.ci.percona.com:\${path_to_dist}/
                        elif [ "\${arch}" = "noarch" ]; then
                            path_to_dist_x86=\${path_to_build}/binary/redhat/\${rhel}/x86_64
                            path_to_dist_aarch=\${path_to_build}/binary/redhat/\${rhel}/aarch64
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                                mkdir -p \${path_to_dist_x86}
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                                mkdir -p \${path_to_dist_aarch}
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \${rpm} ${USER}@repo.ci.percona.com:\${path_to_dist_x86}/
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \${rpm} ${USER}@repo.ci.percona.com:\${path_to_dist_aarch}/
                        fi
                    done
                done
            """
        }
        deleteDir()
    }
}
