def call(String SERVER_IP, String CLIENT_VERSION, String PMM_VERSION, String ENABLE_PULL_MODE, String ENABLE_TESTING_REPO, String CLIENT_INSTANCE, String SETUP_TYPE, String ADMIN_PASSWORD, String ENABLE_EXPERIMENTAL_REPO = 'yes') {
   withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh '''
            set -o errexit
            set -o xtrace
            export PATH="$PATH:/usr/sbin:/sbin"
            test -f /usr/lib64/libsasl2.so.2 || sudo ln -s /usr/lib64/libsasl2.so.3.0.0 /usr/lib64/libsasl2.so.2
            export IP=$(curl -s ifconfig.me)
            export SERVER_IP=${SERVER_IP}
            export CLIENT_VERSION=${CLIENT_VERSION}
            export ENABLE_PULL_MODE=${ENABLE_PULL_MODE}
            export ENABLE_TESTING_REPO=${ENABLE_TESTING_REPO}
            export ENABLE_EXPERIMENTAL_REPO=${ENABLE_EXPERIMENTAL_REPO}
            export CLIENT_INSTANCE=${CLIENT_INSTANCE}
            export SETUP_TYPE=${SETUP_TYPE}
            export ADMIN_PASSWORD=${ADMIN_PASSWORD}
            export PMM_DIR=${WORKSPACE}/${PMM_VERSION}
            export PMM_BINARY=${WORKSPACE}/${PMM_VERSION}-client

            if [ "$SETUP_TYPE" = compose_setup ]; then
                export IP=192.168.0.1
            fi
            if [ -z "$ADMIN_PASSWORD" ]; then
                export ADMIN_PASSWORD=admin
            fi

            if [ "${PMM_VERSION}" = pmm2 ]; then
              echo exclude=mirror.es.its.nyu.edu | sudo tee -a /etc/yum/pluginconf.d/fastestmirror.conf
            fi
            if ! command -v percona-release > /dev/null; then
                sudo yum -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
                sudo yum clean all
                sudo yum makecache
            fi

            if [[ "$CLIENT_VERSION" =~ dev-latest|3-dev-latest ]]; then
                sudo percona-release enable-only pmm2-client experimental
                sudo yum -y install pmm2-client
            elif [[ "$CLIENT_VERSION" = pmm2-rc ]]; then
                sudo percona-release enable-only pmm2-client testing
                sudo yum -y install pmm2-client
            elif [[ "$CLIENT_VERSION" = pmm2-latest ]]; then
                sudo yum -y install pmm2-client
                sudo yum -y update
                sudo percona-release enable-only pmm2-client experimental
            elif [[ "$CLIENT_VERSION" = 2* ]]; then
                sudo yum -y install "pmm2-client-$CLIENT_VERSION-6.el9.x86_64"
                if [[ "$ENABLE_TESTING_REPO" = yes ]]; then
                    sudo percona-release enable-only pmm2-client testing
                elif [[ "$ENABLE_TESTING_REPO" = no ]] && [[ "$ENABLE_EXPERIMENTAL_REPO" = yes ]]; then
                    sudo percona-release enable-only pmm2-client experimental
                else
                    sudo percona-release enable-only pmm2-client release
                fi
                sleep 10
            else
                if [[ "$CLIENT_VERSION" = http* ]]; then
                    curl -o pmm-client.tar.gz "${CLIENT_VERSION}"
                else
                    curl -o pmm-client.tar.gz "https://www.percona.com/downloads/pmm2/${CLIENT_VERSION}/binary/tarball/pmm2-client-${CLIENT_VERSION}.tar.gz"
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

                if [[ "$CLIENT_INSTANCE" = yes ]]; then
                    if [[ "$ENABLE_PULL_MODE" = yes ]]; then
                        pmm-agent setup --config-file="$PMM_DIR/config/pmm-agent.yaml" --server-address="$SERVER_IP:443" --server-insecure-tls --server-username=admin --server-password="$ADMIN_PASSWORD" --paths-base="$PMM_DIR" --metrics-mode=pull "$IP"
                    else
                        pmm-agent setup --config-file="$PMM_DIR/config/pmm-agent.yaml" --server-address="$SERVER_IP:443" --server-insecure-tls --server-username=admin --server-password="$ADMIN_PASSWORD" --paths-base="$PMM_DIR" "$IP"
                    fi
                else
                    pmm-agent setup --config-file="$PMM_DIR/config/pmm-agent.yaml" --server-address="$IP:443" --server-insecure-tls --server-username=admin --server-password="$ADMIN_PASSWORD" --paths-base="$PMM_DIR" "$IP"
                fi

                # launch pmm-agent
                nohup bash -c 'pmm-agent --config-file="$PMM_DIR/config/pmm-agent.yaml" > pmm-agent.log 2>&1 &'
                sleep 10
                cat pmm-agent.log
                pmm-admin status
            fi

            pmm-admin --version
            if [[ "$CLIENT_VERSION" =~ dev-latest|pmm2-latest|pmm2-rc|^2.* ]]; then
                if [[ "$CLIENT_INSTANCE" = yes ]] && [[ "$ENABLE_PULL_MODE" = yes ]]; then
                    sudo pmm-admin config --server-url="https://admin:$ADMIN_PASSWORD@$SERVER_IP:443" --server-insecure-tls --metrics-mode=pull "$IP"
                else
                    sudo pmm-admin config --server-url="https://admin:$ADMIN_PASSWORD@$SERVER_IP:443" --server-insecure-tls "$IP"
                fi
                sleep 10
            fi
            pmm-admin list
        '''
    }
}
