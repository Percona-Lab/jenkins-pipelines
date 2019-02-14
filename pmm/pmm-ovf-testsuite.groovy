void runStaging(String OVA_VERSION, CLIENT_VERSION, CLIENTS) {
    stagingJob = build job: 'vbox-staging-start', parameters: [
        string(name: 'OVA_VERSION', value: OVA_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'true')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://${VM_IP}"
}

void runSauceLabs(String PMM_URL)
{
    stagingJob = build job: 'pk-saucelabs-manual', parameters: [
        string(name: 'PMM_URL', value: PMM_URL)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://${VM_IP}"
}

void destroyStaging(IP) {
    build job: 'vbox-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

pipeline {
    agent {
        label 'virtualbox'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'PMM-Server-dev-latest.ova',
            description: 'PMM OVA Image version from',
            name: 'OVA_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm-ovf', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Start staging') {
            steps {
                deleteDir()
                runStaging(OVA_VERSION, CLIENT_VERSION, '--addclient=mo,2 --with-replica  --addclient=pgsql,1')

                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Prepare'){
            steps{
                sh """
                    set +x
                    ssh -i "/tmp/${env.VM_NAME}/sshkey" -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null admin@${env.VM_IP} "
                        set -o errexit
                        set -o xtrace
                        git clone https://github.com/percona/pmm-qa.git --branch ${env.GIT_BRANCH}
                        curl --silent --location https://rpm.nodesource.com/setup_8.x | sudo bash -
                        sudo yum -y install nodejs svn > /dev/null
                        export PATH=$PATH:/usr/local/node/bin > /dev/null
                        cd pmm-qa
                        npm install > /dev/null
                    "
                    set -x                
                """
            }
        }
        stage('Run Grafana Test') {
            steps {
                sauce('SauceLabsKey') {
                    sauceconnect(options: '', sauceConnectPath: '') {
                        sh """
                            set +x
                            ssh -i "/tmp/${env.VM_NAME}/sshkey" -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null admin@${env.VM_IP} "
                                set -o errexit
                                set -o xtrace
                                cd pmm-qa
                                echo $PATH
                                node --version
                                touch TEST.log
                                ls -la
                                sed -i 's/{SAUCE_USER_KEY}/${SAUCE_ACCESS_KEY}/g' codecept.json
                                sed -i "s~{PMM_URL_HERE}~${env.PMM_URL}~" codecept.json
                                cat codecept.json
                                ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi --grep '(?=.*)^(?!.*@visual-test)'
                                
                            "
                            set -x
                        """
                    }
                }
            }
        }
    }
    post {
        always {
            // stop staging
            destroyStaging(VM_IP)
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished"
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}"
                }
            }
            deleteDir()
        }
    }
}