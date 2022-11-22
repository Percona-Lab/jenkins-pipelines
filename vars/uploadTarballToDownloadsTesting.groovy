def call(String PRODUCT_NAME, String PRODUCT_VERSION) {
    node('master') {
        deleteDir()
        unstash 'uploadPath'
        def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                set -o xtrace

                cat /etc/hosts > hosts
                echo '10.30.6.9 repo.ci.percona.com' >> hosts
                sudo cp ./hosts /etc || true

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                    ssh -p 2222 jenkins-deploy.jenkins-deploy.web.r.int.percona.com mkdir -p /data/downloads/TESTING/${PRODUCT_NAME}-${PRODUCT_VERSION}

                    rsync -avt -e "ssh -p 2222" --bwlimit=50000 --progress ${path_to_build}/binary/tarball/* jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/${PRODUCT_NAME}-${PRODUCT_VERSION}/
                '
                curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
            """
        }
        deleteDir()
    }
}
