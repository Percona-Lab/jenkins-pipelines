def call(String tarball_path, String product_version, String TarballType) {
    node('master') {
        deleteDir()
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            unstash 'uploadPath'
            sh """
                export path_to_build=`cat uploadPath`
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com  \
                    rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="*yassl*" --progress \${path_to_build}/${TarballType}/${tarball_path} jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/psmdb-${product_version}/
                curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory

            """
        }
        deleteDir()
    }
}
