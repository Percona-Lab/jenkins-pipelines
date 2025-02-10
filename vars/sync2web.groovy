def call(String CLOUD_NAME, String REPO_NAME, String DESTINATION) {
    def nodeLabel = (CLOUD_NAME == 'Hetzner') ? 'launcher-x64' : 'micro-amazon'
    node(nodeLabel) {
        deleteDir()
        unstash 'uploadPath'
        def path_to_build = "/srv/UPLOAD/POSTGRESQL_SYNC/${repo_version}"
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                set -o xtrace

                cat /etc/hosts > hosts
                echo '10.30.6.9 repo.ci.percona.com' >> hosts
                sudo cp ./hosts /etc || true
                # Cut prefix if it's provided
                PRODUCT_VERSION=\$(echo ${PRODUCT_VERSION} | sed 's/release-//g');

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                    if [ "x${DESTINATION}" == "xrelease" ]; then
                            PRODUCT=$(echo ${repo_version} | awk -F '-' '{print $1}')
                            RELEASE=$(echo ${path_to_build} | awk -F '/' '{print $2}')
                            if [[ $PRODUCT == "15" ]]; then
                                dir_name="postgresql-distribution-15"
                            elif [[ $PRODUCT == "14" ]]; then
                                dir_name="postgresql-distribution-14"
                            elif [[ $PRODUCT == "13" ]]; then
                                dir_name="postgresql-distribution-13"
                            elif [[ $PRODUCT == "12" ]]; then
                                dir_name="postgresql-distribution-12"
                            elif [[ $PRODUCT == "11" ]]; then
                                dir_name="postgresql-distribution"
                            fi
                            cd /srv/UPLOAD/${path_to_build}/
                            cd ../
                            mkdir -p ${dir_name}
                            mv ${repo_version} ${dir_name}/
                            cd ${dir_name}/..
                                mv ${repo_version}  ${RELEASE}
                                ln -s ${RELEASE} LATEST
                            cd ../

                            rsync -avt -e "ssh -p 2222" --bwlimit=50000 --progress /srv/UPLOAD/POSTGRESQL_SYNC/${dir_name} jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/
                            cd /tmp/
                            rm -rf /srv/UPLOAD/POSTGRESQL_SYNC
                    fi
                '
                curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
            """
        }
        deleteDir()
    }
}
