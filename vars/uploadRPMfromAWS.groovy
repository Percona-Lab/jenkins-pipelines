def call(String FOLDER_NAME, String AWS_STASH_PATH) {
    node('master') {
        deleteDir()
        popArtifactFolder(FOLDER_NAME, AWS_STASH_PATH)
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                export path_to_build=`cat uploadPath`

                cat /etc/hosts > ./hosts
                echo '10.30.6.9 repo.ci.percona.com' >> ./hosts
                sudo cp ./hosts /etc/ || true

                if [ `find . -name '*.src.rpm' | wc -l` -gt 0 ]; then
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/source/redhat
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find . -name '*.src.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/source/redhat/
                fi

                export arch_list=\$( find . -name '*.el[6-9].*.rpm' -o -name '*.amzn2023.*.rpm' -o -name '*.noarch.rpm' | awk -F'[.]' '{print \$(NF -1)}' | sort -n | uniq )

                for arch in \${arch_list}; do
                    if [ `find . -name "*.el6.\${arch}.rpm" | wc -l` -gt 0 ]; then
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            mkdir -p \${path_to_build}/binary/redhat/6/\${arch}
                        scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                            `find . -name "*.el6.\${arch}.rpm"` \
                            ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/6/\${arch}/
                    fi

                    if [ `find . -name "*.el7.\${arch}.rpm" | wc -l` -gt 0 ]; then
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            mkdir -p \${path_to_build}/binary/redhat/7/\${arch}
                        scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                            `find . -name "*.el7.\${arch}.rpm"` \
                            ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/7/\${arch}/
                    fi

                    if [ `find . -name "*.el8.\${arch}.rpm" | wc -l` -gt 0 ]; then
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            mkdir -p \${path_to_build}/binary/redhat/8/\${arch}
                        scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                            `find . -name "*.el8.\${arch}.rpm"` \
                            ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/8/\${arch}/
                    fi

                    if [ `find . -name "*.el9.\${arch}.rpm" | wc -l` -gt 0 ]; then
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            mkdir -p \${path_to_build}/binary/redhat/9/\${arch}
                        scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                            `find . -name "*.el9.\${arch}.rpm"` \
                            ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/9/\${arch}/
                    fi

                    if [ `find . -name "*.amzn2023.\${arch}.rpm" | wc -l` -gt 0 ]; then
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            mkdir -p \${path_to_build}/binary/redhat/9/\${arch}
                        scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                            `find . -name "*.el9.\${arch}.rpm"` \
                            ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/2023/\${arch}/
                    fi

                    if [ `find . -name "*.noarch.rpm" | wc -l` -gt 0 ]; then
                        Vers=("6" "7" "8" "9" "2023")
                        for osVer in "\${Vers[@]}"; do
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                                mkdir -p \${path_to_build}/binary/redhat/\${osVer}/\${arch}
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                                `find . -name "*.noarch.rpm"` \
                                ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/\${osVer}/\${arch}/
                        done
                    fi
                done
            """
        }
        deleteDir()
    }
}
