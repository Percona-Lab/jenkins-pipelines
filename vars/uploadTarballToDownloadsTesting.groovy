def call(String CLOUD_NAME, String PRODUCT_NAME, String PRODUCT_VERSION) {
    def nodeLabel = (CLOUD_NAME == 'Hetzner') ? 'launcher-x64' : 'master'
    node(nodeLabel) {
        deleteDir()
        unstash 'uploadPath'
        def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                #!/bin/bash
                set -o xtrace

                cat /etc/hosts > hosts
                echo '10.30.6.9 repo.ci.percona.com' >> hosts
                sudo cp ./hosts /etc || true

                # Cut prefix if it's provided
                cutProductVersion=\$(echo ${PRODUCT_VERSION} | sed 's/release-//g');

                # Get PXC version from a file
                if [ \"x${PRODUCT_NAME}\" = \"xpxc\" ]; then
                    curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${PRODUCT_VERSION}/MYSQL_VERSION
                    MYSQL_VERSION_MAJOR=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_MAJOR | awk -F= '{print \$2}')
                    MYSQL_VERSION_MINOR=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print \$2}')
                    MYSQL_VERSION_PATCH=\$(cat MYSQL_VERSION | grep MYSQL_VERSION_PATCH | awk -F= '{print \$2}')
                    cutProductVersion=\${MYSQL_VERSION_MAJOR}.\${MYSQL_VERSION_MINOR}.\${MYSQL_VERSION_PATCH}
                fi
                echo \${cutProductVersion}
                if expr \"${PRODUCT_NAME}\" : \".*gated.*\" > /dev/null; then
                    echo "Processing gated product"
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p /srv/repo-copy/private/qa-test/${PRODUCT_NAME}-\${cutProductVersion}

                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        cp ${path_to_build}/binary/tarball/* /srv/repo-copy/private/qa-test/${PRODUCT_NAME}-\${cutProductVersion}

                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        rsync -avt --delete --delete-excluded --delete-after --progress --exclude=*.sh --exclude=*.bak /srv/repo-copy/private/qa-test/* 10.30.9.32:/www/repo.percona.com/htdocs/private/qa-test/
                else
                    echo "Processing non-gated product"
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        ssh -p 2222 jenkins-deploy.jenkins-deploy.web.r.int.percona.com mkdir -p /data/downloads/TESTING/${PRODUCT_NAME}-\${cutProductVersion}

                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        rsync -avt -e '"ssh -p 2222"' --bwlimit=50000 --progress ${path_to_build}/binary/tarball/* jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/${PRODUCT_NAME}-\${cutProductVersion}/
                fi

                curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
            """
        }
        deleteDir()
    }
}
