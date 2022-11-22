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

                if [ `find . -name '*.el6.noarch.rpm' -o -name '*.el6.x86_64.rpm' | wc -l` -gt 0 ]; then
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/binary/redhat/6/x86_64
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find . -name '*.el6.noarch.rpm' -o -name '*.el6.x86_64.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/6/x86_64/
                fi

                if [ `find . -name '*.el7.noarch.rpm' -o -name '*.el7.x86_64.rpm' | wc -l` -gt 0 ]; then
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/binary/redhat/7/x86_64
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find . -name '*.el7.noarch.rpm' -o -name '*.el7.x86_64.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/7/x86_64/
                fi
                if [ `find . -name '*.el8.noarch.rpm' -o -name '*.el8.x86_64.rpm' | wc -l` -gt 0 ]; then
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/binary/redhat/8/x86_64
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find . -name '*.el8.noarch.rpm' -o -name '*.el8.x86_64.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/8/x86_64/
                fi
                if [ `find . -name '*.el9.noarch.rpm' -o -name '*.el9.x86_64.rpm' | wc -l` -gt 0 ]; then
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/binary/redhat/9/x86_64
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find . -name '*.el9.noarch.rpm' -o -name '*.el9.x86_64.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/9/x86_64/
                fi
            """
        }
        deleteDir()
    }
}
