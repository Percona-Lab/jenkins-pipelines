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
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/binary/redhat/\${rhel}/x86_64
                    if [ `find . -name "*.el\${rhel}.noarch.rpm" -o -name "*.el\${rhel}.x86_64.rpm" | wc -l` -gt 0 ]; then
                        scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                            `find . -name "*.el\${rhel}.noarch.rpm" -o -name "*.el\${rhel}.x86_64.rpm"` \
                            ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/\${rhel}/x86_64/
                    fi
                done
            """
        }
        deleteDir()
    }
}
