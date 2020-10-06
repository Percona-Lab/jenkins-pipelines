def call(String FOLDER_NAME, String AWS_STASH_PATH) {
    node('master') {
        deleteDir()
        popArtifactFolder(FOLDER_NAME, AWS_STASH_PATH)
        //unstash 'debs'
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                export path_to_build=`cat uploadPath`

                cat /etc/hosts > ./hosts
                echo '10.30.6.9 repo.ci.percona.com' >> ./hosts
                sudo cp ./hosts /etc/

                dsc=`find . -name '*.dsc'`
                if [ -f "\${dsc}" ]; then
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/source/debian
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `dirname \${dsc}`/* \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/source/debian/
                fi

                for deb in \$(find . -name '*.deb'); do
                    dist=`echo \${deb} | sed -re 's/.*\\.([^.]+)_amd64.deb/\\1/'`
                    path_to_dist=\${path_to_build}/binary/debian/\${dist}/x86_64
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_dist}
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        \${deb} \
                        ${USER}@repo.ci.percona.com:\${path_to_dist}/
                done
            """
        }
        deleteDir()
    }
}
