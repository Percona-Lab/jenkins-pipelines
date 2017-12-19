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
                             \${path_to_build}/binary/redhat/7/x86_64

                scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                    `find result-repo -name '*.src.rpm'` \
                    ${USER}@repo.ci.percona.com:\${path_to_build}/source/redhat/

                scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                    `find result-repo -name '*.noarch.rpm' -o -name '*.x86_64.rpm'` \
                    ${USER}@repo.ci.percona.com:\${path_to_build}/binary/redhat/7/x86_64/
            """
        }
    }
}
