def call(String SERVER_IP, String CLIENT_VERSION, String PMM_VERSION, String ENABLE_PULL_MODE, String ENABLE_TESTING_REPO, String CLIENT_INSTANCE, String SETUP_TYPE, String ADMIN_PASSWORD = 'admin', String ENABLE_EXPERIMENTAL_REPO = 'yes') {
   def clientPackageRelease = pmmClientPackageRelease(CLIENT_VERSION) ?: ''
   withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        withEnv([
            "SERVER_IP=${SERVER_IP}",
            "CLIENT_VERSION=${CLIENT_VERSION}",
            "PMM_VERSION=${PMM_VERSION}",
            "ENABLE_PULL_MODE=${ENABLE_PULL_MODE}",
            "ENABLE_TESTING_REPO=${ENABLE_TESTING_REPO}",
            "CLIENT_INSTANCE=${CLIENT_INSTANCE}",
            "SETUP_TYPE=${SETUP_TYPE}",
            "ADMIN_PASSWORD=${ADMIN_PASSWORD}",
            "ENABLE_EXPERIMENTAL_REPO=${ENABLE_EXPERIMENTAL_REPO}",
            "PMM_CLIENT_PACKAGE_RELEASE=${clientPackageRelease}",
        ]) {
        sh '''
            set -o errexit
            set -o xtrace
            export PATH="$PATH:/usr/sbin:/sbin"
            test -f /usr/lib64/libsasl2.so.2 || sudo ln -s /usr/lib64/libsasl2.so.3.0.0 /usr/lib64/libsasl2.so.2
            export IP=$(curl -4 -s ifconfig.me)
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

            # Percona's CDN occasionally serves an RPM whose Content-Length
            # disagrees with the repodata-advertised size, which makes dnf abort
            # with "Inconsistent server data ... please report to repository maintainer".
            # The mismatch usually clears within a minute, so retry a few times.
            retry_dnf_install() {
                local n=3
                local i
                for i in $(seq 1 $n); do
                    if sudo dnf -y install "$@"; then
                        return 0
                    fi
                    if [ "$i" -lt "$n" ]; then
                        echo "dnf install failed (attempt $i/$n); retrying in 30s..."
                        sleep 30
                    fi
                done
                echo "dnf install failed after $n attempts"
                return 1
            }

            sudo dnf clean expire-cache
            if ! command -v percona-release > /dev/null; then
                curl -O https://repo.percona.com/yum/percona-release-latest.noarch.rpm
                sudo dnf -y install ./percona-release-latest.noarch.rpm
                rm -f percona-release-latest.noarch.rpm
            fi

            if [[ "${CLIENT_VERSION}" == "latest-tarball" ]]; then
                CLIENT_VERSION="https://pmm-build-cache.s3.us-east-2.amazonaws.com/PR-BUILDS/pmm-client/pmm-client-latest.tar.gz"
            fi

            if [ "${CLIENT_VERSION}" = 3-dev-latest ]; then
                sudo percona-release enable-only pmm3-client experimental
                retry_dnf_install pmm-client
            elif [ "${CLIENT_VERSION}" = pmm3-rc ]; then
                sudo percona-release enable-only pmm3-client testing
                retry_dnf_install pmm-client
            elif [ "${CLIENT_VERSION}" = pmm3-latest ]; then
                sudo percona-release enable-only pmm3-client experimental
                retry_dnf_install pmm-client
            elif [[ "${CLIENT_VERSION}" = 3* ]]; then
                if [ "${ENABLE_TESTING_REPO}" = yes ]; then
                    sudo percona-release enable-only pmm3-client testing
                elif [ "${ENABLE_EXPERIMENTAL_REPO}" = yes ]; then
                    sudo percona-release enable-only pmm3-client experimental
                else
                    sudo percona-release enable-only pmm3-client release
                fi

                if [[ -n "${PMM_CLIENT_PACKAGE_RELEASE}" ]]; then
                    retry_dnf_install "pmm-client-${CLIENT_VERSION}-${PMM_CLIENT_PACKAGE_RELEASE}.el9.x86_64"
                else
                    export FULL_CLIENT_VERSION=$(dnf list pmm-client --showduplicates | grep -w "${CLIENT_VERSION}" | awk '{print $2}')
                    retry_dnf_install "pmm-client-${FULL_CLIENT_VERSION}"
                fi
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
}
