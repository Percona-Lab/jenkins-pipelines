def call(String tarball_path, String product_version) {
    node('master') {
        deleteDir()
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@jenkins-deploy.jenkins-deploy.web.r.int.percona.com  \
                    mkdir -p /data/downloads/TESTING/psmdb-${product_version}

                rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="*yassl*" --progress ${tarball_path}/ jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/psmdb-${product_version}/

                curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory

            """
        }
        deleteDir()
    }
}
