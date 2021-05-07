def call(String SERVER_IP, String CLIENT_VERSION, String PMM_VERSION, String ENABLE_PUSH_MODE, String ENABLE_TESTING_REPO, String CLIENT_INSTANCE) {
   withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            set -o errexit
            set -o xtrace
            export PATH=\$PATH:/usr/sbin
            test -f /usr/lib64/libsasl2.so.2 || sudo ln -s /usr/lib64/libsasl2.so.3.0.0 /usr/lib64/libsasl2.so.2
            export IP=\$(curl ifconfig.me)
            export SERVER_IP=${SERVER_IP}
            export CLIENT_VERSION=${CLIENT_VERSION}
            export PMM_VERSION=${PMM_VERSION}
            export ENABLE_PUSH_MODE=${ENABLE_PUSH_MODE}
            export ENABLE_TESTING_REPO=${ENABLE_TESTING_REPO}
            export CLIENT_INSTANCE=${CLIENT_INSTANCE}
            sudo yum -y install https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
            sudo yum clean all
            sudo yum makecache
            if [[ \$CLIENT_VERSION = dev-latest ]]; then
                sudo percona-release enable-only original experimental
                sudo yum -y install pmm2-client
            elif [[ \$CLIENT_VERSION = pmm2-rc ]]; then
                sudo percona-release enable-only original testing
                sudo yum -y install pmm2-client
            elif [[ \$CLIENT_VERSION = pmm2-latest ]]; then
                sudo yum -y install pmm2-client
                sudo yum -y update
                sudo percona-release enable-only original experimental
            elif [[ \$CLIENT_VERSION = 2* ]]; then
                sudo yum -y install pmm2-client-\$CLIENT_VERSION-6.el7.x86_64
                if [[ \$ENABLE_TESTING_REPO = yes ]]; then
                    sudo percona-release enable-only original testing
                    sleep 15
                else
                    sudo percona-release enable-only original experimental
                    sleep 15
                fi
            elif [[ \$CLIENT_VERSION = pmm1-dev-latest ]]; then
                sudo percona-release enable-only original testing
                sudo yum -y install pmm-client
                sudo yum -y update
            else
                if [[ \$PMM_VERSION == pmm1 ]]; then
                        if [[ \$CLIENT_VERSION == http* ]]; then
                        wget -O pmm-client.tar.gz --progress=dot:giga "\${CLIENT_VERSION}"
                    else
                        wget -O pmm-client.tar.gz --progress=dot:giga "https://www.percona.com/downloads/pmm-client/pmm-client-\${CLIENT_VERSION}/binary/tarball/pmm-client-\${CLIENT_VERSION}.tar.gz"
                    fi
                    tar -zxpf pmm-client.tar.gz
                    pushd pmm-client-*
                        sudo ./install
                    popd
                else
                    if [[ \$CLIENT_VERSION == http* ]]; then
                        wget -O pmm2-client.tar.gz --progress=dot:giga "\${CLIENT_VERSION}"
                    else
                        wget -O pmm2-client.tar.gz --progress=dot:giga "https://www.percona.com/downloads/pmm2/\${CLIENT_VERSION}/binary/tarball/pmm2-client-\${CLIENT_VERSION}.tar.gz"
                    fi
                    export BUILD_ID=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_NODE_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_SERVER_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                    tar -zxpf pmm2-client.tar.gz
                    rm -r pmm2-client.tar.gz
                    mv pmm2-client-* pmm2-client
                    cd pmm2-client
                    sudo bash -x ./install_tarball
                    pwd
                    cd ../
                    export PMM_CLIENT_BASEDIR=`ls -1td pmm2-client 2>/dev/null | grep -v ".tar" | head -n1`
                    export PATH="`pwd`/pmm2-client/bin:$PATH"
                    echo "export PATH=`pwd`/pmm2-client/bin:$PATH" >> ~/.bash_profile
                    source ~/.bash_profile
                    pmm-admin --version
                    if [[ \$CLIENT_INSTANCE == yes ]]; then
                        if [[ \$ENABLE_PUSH_MODE == yes ]]; then
                            pmm-agent setup --config-file=`pwd`/pmm2-client/config/pmm-agent.yaml --server-address=\$SERVER_IP:443 --server-insecure-tls --server-username=admin --server-password=admin --metrics-mode=push \$IP
                        else
                            pmm-agent setup --config-file=`pwd`/pmm2-client/config/pmm-agent.yaml --server-address=\$SERVER_IP:443 --server-insecure-tls --server-username=admin --server-password=admin \$IP
                        fi
                    else
                        pmm-agent setup --config-file=`pwd`/pmm2-client/config/pmm-agent.yaml --server-address=\$IP:443 --server-insecure-tls --server-username=admin --server-password=admin \$IP
                    fi
                    sleep 10
                    JENKINS_NODE_COOKIE=dontKillMe nohup bash -c 'pmm-agent --config-file=`pwd`/pmm2-client/config/pmm-agent.yaml > pmm-agent.log 2>&1 &'
                    sleep 10
                    cat pmm-agent.log
                    pmm-admin status
                fi
            fi
            export PATH=\$PATH:/usr/sbin:/sbin
            if [[ \$PMM_VERSION == pmm2 ]]; then
                if [[ \$CLIENT_VERSION == dev-latest ]] || [[ \$CLIENT_VERSION == pmm2-latest ]] || [[ \$CLIENT_VERSION == pmm2-rc ]] || [[ \$CLIENT_VERSION == 2* ]]; then
                    pmm-admin --version
                    if [[ \$CLIENT_INSTANCE == yes ]]; then
                        if [[ \$ENABLE_PUSH_MODE == yes ]]; then
                            sudo pmm-agent setup --server-address=\$SERVER_IP:443 --server-insecure-tls --server-username=admin --server-password=admin --metrics-mode=push \$IP
                        else
                            sudo pmm-agent setup --server-address=\$SERVER_IP:443 --server-insecure-tls --server-username=admin --server-password=admin \$IP
                        fi
                    else
                        sudo pmm-agent setup --server-address=\$IP:443 --server-insecure-tls --server-username=admin --server-password=admin \$IP
                    fi
                    sleep 10
                    pmm-admin list
                fi
            else
                sudo pmm-admin config --client-name pmm-client-hostname --server `ip addr show eth0 | grep 'inet ' | awk '{print\\\$2}' | cut -d '/' -f 1`
            fi
        """
    }
}
