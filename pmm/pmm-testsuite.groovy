void runStaging(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: ''),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'PMM_VERSION', value: 'pmm1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://${VM_IP}"
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

void runTAP(String TYPE, String PRODUCT, String COUNT, String VERSION) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                set -o errexit
                set -o xtrace

                test -f /usr/lib64/libsasl2.so.2 || sudo ln -s /usr/lib64/libsasl2.so.3.0.0 /usr/lib64/libsasl2.so.2
                export PATH=\$PATH:/usr/sbin
                export instance_t="${TYPE}"
                export instance_c="${COUNT}"
                export version="${VERSION}"
                export stress="1"
                export table_c="100"
                export tap="1"

                sudo chmod 755 /srv/pmm-qa/pmm-tests/pmm-framework.sh
                export PRODUCT=${PRODUCT}
                if [[ \$PRODUCT != postgresql ]]; then
                    wget --progress=dot:giga \$(bash /srv/pmm-qa/get_download_link.sh --product=${PRODUCT} --distribution=centos --version=${VERSION})
                fi
                wget --progress=dot:giga \$(bash /srv/pmm-qa/get_download_link.sh --product=${PRODUCT} --distribution=centos --version=${VERSION})
                bash /srv/pmm-qa/pmm-tests/pmm-testsuite.sh \
                    | tee /tmp/result.output

                perl -ane "
                    if (m/ok \\d+/) {
                        \\\$i++;
                        s/(.*ok) \\d+ (.*)/\\\$1 \\\$i \\\$2/;
                        print;
                    }
                    END { print \\"1..\\\$i\\n\\" }
                " /tmp/result.output \
                    | sed "/^not ok/a \\\\  ---\\\\n\\\\    operator: fail\\\\n\\\\  ..." \
                    | tee /tmp/result.tap
            '
            scp -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                ${USER}@${VM_IP}:/tmp/result.tap \
                ${TYPE}.tap
            cat ${TYPE}.tap \
                | ./node_modules/tap-junit/bin/tap-junit \
                    --name ${TYPE} \
                    --output ./ \
                    || :
        """
    }
}

pipeline {
    agent {
        label 'micro-amazon'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:pmm1-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'pmm1-dev-latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                sh '''
                    curl --silent --location https://rpm.nodesource.com/setup_7.x | sudo bash -
                    sudo yum -y install nodejs
                    npm install tap-junit
                '''
            }
        }
        stage('Start staging') {
            steps {
                runStaging(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1')
            }
        }
        stage('Sanity check') {
            steps {
                sh "curl --silent --insecure '${PMM_URL}/prometheus/targets' | grep localhost:9090"
            }
        }
        stage('Test: PS57') {
            steps {
                runTAP("ps", "ps", "2", "5.7")
            }
        }
        stage('Test: PS80') {
            steps {
                runTAP("ps", "ps", "2", "8.0")
            }
        }
        stage('Test: MS57') {
            steps {
                runTAP("ms", "mysql", "2", "5.7")
            }
        }
        stage('Test: MS80') {
            steps {
                runTAP("ms", "mysql", "2", "8.0")
            }
        }
        stage('Test: PXC') {
            steps {
                runTAP("pxc", "pxc", "3", "5.7")
            }
        }
        stage('Test: PSMDB') {
            steps {
                runTAP("mo", "psmdb", "3", "4.0")
            }
        }
        stage('Test: MariaDB') {
            steps {
                runTAP("md", "mariadb", "2", "10.3")
            }
        }
    }
    post {
        always {
            destroyStaging(VM_NAME)
        }
        success {
            junit '*.xml'
            script {
                OK = sh (
                    script: 'grep "^ok" *.tap | grep -v "# skip" | wc -l',
                    returnStdout: true
                ).trim()
                SKIP = sh (
                    script: 'grep "# skip" *.tap | wc -l',
                    returnStdout: true
                ).trim()
                FAIL = sh (
                    script: 'grep "^not ok" *.tap | wc -l',
                    returnStdout: true
                ).trim()
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished\nok - ${OK}, skip - ${SKIP}, fail - ${FAIL}"
            }
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
        }
    }
}
