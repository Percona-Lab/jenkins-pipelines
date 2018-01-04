pipeline {
    agent {
        label 'master'
    }
    parameters {
        string(
            defaultValue: '1.X.X',
            description: 'version of result package',
            name: 'NEW_VERSION')
    }
    options {
        skipStagesAfterUnstable()
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Set default value') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        echo "/usr/bin/ssh -i "${SSHKEY}" -o StrictHostKeyChecking=no \\\"\\\$@\\\"" > github-ssh.sh
                        chmod 755 github-ssh.sh
                        export GIT_SSH=$(pwd -P)/github-ssh.sh
                        git clone git@github.com:Percona-Lab/jenkins-pipelines

                        pushd jenkins-pipelines
                            git config --global user.email "dev-services@percona.com"
                            git config --global user.name "PMM Jenkins"
                            for JOB in pmm-dashboards-package pmm-manage-package pmm-managed-package pmm-qan-api-package pmm-qan-app-package pmm-server-hotfix pmm-server-package pmm-server-packages pmm-server-release pmm-update-package rds_exporter-package; do
                                sed \
                                    -i'' \
                                    -e "s/defaultValue: '[0-9]\\.[0-9]\\.[0-9]'/defaultValue: '${NEW_VERSION}'/" \
                                    ./pmm/${JOB}.groovy
                            done
                            git pull
                            git commit -a -m "up PMM to ${NEW_VERSION}"
                            git config --global push.default matching
                            git push
                        popd
                    '''
                }
            }
        }
        stage('Run jobs') {
            steps {
                script {
                    ['pmm-dashboards-package', 'pmm-manage-package', 'pmm-managed-package', 'pmm-qan-api-package', 'pmm-qan-app-package', 'pmm-server-package', 'pmm-update-package', 'rds_exporter-package'].each { JOB2RUN ->
                        build job: JOB2RUN, parameters: [string(name: 'GIT_BRANCH', value: 'master'), string(name: 'DESTINATION', value: 'laboratory'), string(name: 'VERSION', value: NEW_VERSION)], propagate: false, wait: false
                    }
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}
