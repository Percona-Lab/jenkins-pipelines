def call() {
    node('master') {
        deleteDir()
        unstash 'rpms'
        unstash 'gitCommit'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                export path_to_build="UPLOAD/pmm/${JOB_NAME}/\$(cat shortCommit)-${BUILD_NUMBER}"

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    mkdir -p \${path_to_build}/source/redhat \
                             \${path_to_build}/binary/redhat/6/x86_64 \
                             \${path_to_build}/binary/redhat/7/x86_64

                if [ `find result-repo results -name '*.src.rpm' | wc -l` -gt 0 ]; then
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find result-repo results -name '*.src.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/source/redhat/
                fi

                if [ `find result-repo results -name '*.el6.noarch.rpm' -o -name '*.el6.x86_64.rpm' | wc -l` -gt 0 ]; then
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find result-repo results -name '*.el6.noarch.rpm' -o -name '*.el6.x86_64.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/6/x86_64/
                fi

                if [ `find result-repo results -name '*.el7.noarch.rpm' -o -name '*.el7.x86_64.rpm' | wc -l` -gt 0 ]; then
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `find result-repo results -name '*.el7.noarch.rpm' -o -name '*.el7.x86_64.rpm'` \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/7/x86_64/
                fi
            """
        }
        deleteDir()
    }
}
