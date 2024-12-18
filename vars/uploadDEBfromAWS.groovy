def call(String CLOUD_NAME, String FOLDER_NAME, String AWS_STASH_PATH) {
    def nodeLabel = (CLOUD_NAME == 'Hetzner') ? 'launcher-x64' : 'micro-amazon'
    node(nodeLabel) {
        deleteDir()
        popArtifactFolder(FOLDER_NAME, AWS_STASH_PATH)
        //unstash 'debs'
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                export path_to_build=`cat uploadPath`

                cat /etc/hosts > ./hosts
                echo '10.30.6.9 repo.ci.percona.com' >> ./hosts
                sudo cp ./hosts /etc/ || true

                dsc=`find . -name '*.dsc'`
                if [ -f "\${dsc}" ]; then
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/source/debian
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `dirname \${dsc}`/* \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/source/debian/
                fi

                for deb in \$(find . -name '*.deb'); do
                    dist=`echo \${deb} | sed -re 's/.*\\.([^.]+)_(amd64|arm64|all).deb/\\1/'`
                    package=`echo \${deb} | sed -re 's/\\.\\/deb\\/(.*)_(.*)\\.([^.]+)_(amd64|arm64|all).deb/\\1/'`
                    version=`echo \${deb} | sed -re 's/\\.\\/deb\\/(.*)_(.*)\\.([^.]+)_(amd64|arm64|all).deb/\\2/'`
                    path_to_dist=\${path_to_build}/binary/debian/\${dist}/x86_64
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_dist}
                    if [ "\${package}" = "percona-release" ]; then
                        scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                            \${deb} \
                            ${USER}@repo.ci.percona.com:\${path_to_dist}/\${package}_\${version}.generic_all.deb
                    else
                        scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                            \${deb} \
                            ${USER}@repo.ci.percona.com:\${path_to_dist}/
                    fi
                done
            """
        }
        deleteDir()
    }
}
