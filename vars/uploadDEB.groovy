def call() {
    node('master') {
        deleteDir()
        unstash 'debs'
        unstash 'uploadPath'
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                export path_to_build=`cat uploadPath`

                dsc=`find . -name '*.dsc'`
                if [ -f "\${dsc}" ]; then
                    ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                        mkdir -p \${path_to_build}/source/debian
                    scp -o StrictHostKeyChecking=no -i ${KEY_PATH} \
                        `dirname \${dsc}`/* \
                        ${USER}@repo.ci.percona.com:\${path_to_build}/source/debian/
                fi

                for deb in \$(find . -name '*.deb'); do
                    arch=`echo \${deb} | sed -re 's/.*_(amd64|arm64).deb/\\1/'`
                    if [ "\${arch}" = "amd64" ]; then
                        dist=`echo \${deb} | sed -re 's/.*\\.([^.]+)_amd64\\.deb/\\1/'`
                        path_to_dist=\${path_to_build}/binary/debian/\${dist}/x86_64
                    elif [ "\${arch}" = "arm64" ]; then
                        dist=`echo \${deb} | sed -re 's/.*\\.([^.]+)_arm64\\.deb/\\1/'`
                        path_to_dist=\${path_to_build}/binary/debian/\${dist}/aarch64
                    fi
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
