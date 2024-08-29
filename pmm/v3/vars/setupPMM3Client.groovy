def call(String SERVER_IP, String CLIENT_VERSION, String PMM_VERSION, String ENABLE_PULL_MODE, String ENABLE_TESTING_REPO, String CLIENT_INSTANCE, String SETUP_TYPE, String ADMIN_PASSWORD = 'admin', String ENABLE_EXPERIMENTAL_REPO = 'yes') {
   withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh '''
            set -o errexit
            set -o xtrace
            export PATH="$PATH:/usr/sbin:/sbin"
            test -f /usr/lib64/libsasl2.so.2 || sudo ln -s /usr/lib64/libsasl2.so.3.0.0 /usr/lib64/libsasl2.so.2
            export IP=$(curl -s ifconfig.me)
            export PMM_DIR=${WORKSPACE}/${PMM_VERSION}
            export PMM_BINARY=${WORKSPACE}/${PMM_VERSION}-client

            if [ "${ENABLE_TESTING_REPO}" = yes ] && [ "${ENABLE_EXPERIMENTAL_REPO}" = yes ]; then
                echo "Fatal: cannot enable both testing and exprimental repos. Please choose one."
                echo "Exiting..."
                exit 1
            fi
            
            if [ "${SETUP_TYPE}" = compose_setup ]; then
                export IP=192.168.0.1
            fi

            if ! command -v percona-release > /dev/null; then
                sudo yum -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
                sudo yum clean all
                sudo yum makecache
            fi

            if [ "${CLIENT_VERSION}" = 3-dev-latest ]; then
                sudo percona-release enable-only pmm3-client experimental
                RHEL=$(rpm --eval '%{rhel}')
                if [ "$RHEL" -eq 9 ]; then
                  sudo yum -y install https://repo.percona.com/pmm3-client/yum/experimental/9/RPMS/x86_64/pmm-client-3.0.0-6.el9.x86_64.rpm
                else
                  echo "Fatal: pmm3-client has no compatible RPM version to install. Exiting..."
                  exit 1
                fi
            elif [ "${CLIENT_VERSION}" = pmm3-rc ]; then
                sudo percona-release enable-only pmm3-client testing
                sudo yum -y install pmm-client
            elif [ "${CLIENT_VERSION}" = pmm3-latest ]; then
                sudo percona-release enable-only pmm3-client experimental
                sudo yum -y install pmm-client
                sudo yum -y update
            elif [[ "${CLIENT_VERSION}" = 3* ]]; then
                if [ "${ENABLE_TESTING_REPO}" = yes ]; then
                    sudo percona-release enable-only pmm3-client testing
                elif [ "${ENABLE_EXPERIMENTAL_REPO}" = yes ]; then
                    sudo percona-release enable-only pmm3-client experimental
                else
                    sudo percona-release enable-only pmm3-client release
                fi
                sudo yum -y install "pmm-client-${CLIENT_VERSION}-1.el9.x86_64"
                sleep 10
            else
                if [[ "${CLIENT_VERSION}" = http* ]]; then
                    curl -o pmm-client.tar.gz -fSL "${CLIENT_VERSION}"
                else
                    curl -o pmm-client.tar.gz -fSL "https://www.percona.com/downloads/pmm3/${CLIENT_VERSION}/binary/tarball/pmm3-client-${CLIENT_VERSION}.tar.gz"
                fi

                export BUILD_ID=dont-kill-the-process
                export JENKINS_NODE_COOKIE=dont-kill-the-process
                mkdir -p "$PMM_BINARY"
                tar -xzpf pmm-client.tar.gz --strip-components=1 -C "$PMM_BINARY"
                rm -f pmm-client.tar.gz

                # Install the client to PMM_DIR
                mkdir -p "$PMM_DIR"
                # PMM_DIR is passed to 'install_tarball' via -E option, it's owned by 'ec2-user'
                bash -E "$PMM_BINARY/install_tarball"
                rm -rf "$PMM_BINARY"

                # Create symlinks for pmm-admin and pmm-agent
                sudo ln -s $PMM_DIR/bin/pmm-admin /usr/local/bin || :
                sudo ln -s $PMM_DIR/bin/pmm-agent /usr/local/bin || :
                pmm-admin --version

                if [ "${CLIENT_INSTANCE}" = yes ]; then
                    if [ "${ENABLE_PULL_MODE}" = yes ]; then
                        pmm-agent setup --config-file="$PMM_DIR/config/pmm-agent.yaml" --server-address="${SERVER_IP}:443" --server-insecure-tls --server-username=admin --server-password="${ADMIN_PASSWORD}" --paths-base="$PMM_DIR" --metrics-mode=pull "$IP"
                    else
                        pmm-agent setup --config-file="$PMM_DIR/config/pmm-agent.yaml" --server-address="${SERVER_IP}:443" --server-insecure-tls --server-username=admin --server-password="${ADMIN_PASSWORD}" --paths-base="$PMM_DIR" "$IP"
                    fi
                else
                    set +e
                    if ! pmm-agent setup --config-file="$PMM_DIR/config/pmm-agent.yaml" --server-address="$IP:443" --server-insecure-tls --server-username=admin --server-password="${ADMIN_PASSWORD}" --paths-base="$PMM_DIR" "$IP"; then
                        echo "--- DEBUG sctl status ---"
                        docker exec -t pmm-server supervisorctl status
                        echo "--- DEBUG pmm-managed.log ---"
                        docker exec -t pmm-server tail -n 200 /srv/logs/pmm-managed.log
                        echo "--- DEBUG pmm-agent.log ---"
                        docker exec -t pmm-server tail -n 150 /srv/logs/pmm-agent.log
                        echo "--- DEBUG nginx.log ---"
                        docker exec -t pmm-server tail -n 150 /srv/logs/nginx.log
                    fi
                    set -e
                fi

                # launch pmm-agent
                nohup bash -c 'pmm-agent --config-file="$PMM_DIR/config/pmm-agent.yaml" > pmm-agent.log 2>&1 &'
                sleep 10

                if ! pmm-admin status; then
                  cat pmm-agent.log
                  exit 1
                fi
            fi

            pmm-admin --version
            if [[ "${CLIENT_VERSION}" =~ 3-dev-latest|pmm3-latest|pmm3-rc|^3.* ]]; then
                if [ "${CLIENT_INSTANCE}" = yes ] && [ "${ENABLE_PULL_MODE}" = yes ]; then
                    sudo pmm-admin config --server-url="https://admin:${ADMIN_PASSWORD}@${SERVER_IP}:443" --server-insecure-tls --metrics-mode=pull "$IP"
                else
                    sudo pmm-admin config --server-url="https://admin:${ADMIN_PASSWORD}@${SERVER_IP}:443" --server-insecure-tls "$IP"
                fi
                sleep 10
            fi
            set +e
            if ! pmm-admin list; then
                echo "--- DEBUG pmm-managed.log ---"
                docker exec -t pmm-server tail -n 200 /srv/logs/pmm-managed.log
            fi
            set -e
        '''
    }
}
