pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: '1.X.X',
            description: 'version of result package',
            name: 'NEW_VERSION')
    }
    stages {
        stage('Set default value') {
            steps {
                deleteDir()
                withCredentials([sshUserPrivateKey(credentialsId: 'a4f74dde-80c7-462f-9bd6-d96b5c1b1409', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        echo "#!/bin/sh
exec /usr/bin/ssh -i "${SSHKEY}" -o StrictHostKeyChecking=no \\\"\\\$@\\\"" > github-ssh.sh
                        chmod 755 github-ssh.sh
                        export GIT_SSH=./github-ssh.sh
                        git clone git@github.com:Percona-Lab/jenkins-pipelines

                        pushd jenkins-pipelines
                            for JOB in pmm-dashboards-package pmm-manage-package pmm-managed-package pmm-qan-api-package pmm-qan-app-package pmm-server-hotfix pmm-server-package pmm-server-packages pmm-server-release pmm-update-package; do
                                sed \
                                    -i'' \
                                    -e "s/defaultValue: '[0-9]\\.[0-9]\\.[0-9]'/defaultValue: '${NEW_VERSION}'/" \
                                    ./pmm/${JOB}.groovy
                            done
                            git push
                        popd
                    '''
                }
            }
        }
        stage('Run jobs') {
            steps {
                script {
                    ['pmm-dashboards-package', 'pmm-manage-package', 'pmm-managed-package', 'pmm-qan-api-package', 'pmm-qan-app-package', 'pmm-server-package', 'pmm-update-package'].each { JOB2RUN ->
                        build job: JOB2RUN, parameters: [string(name: 'GIT_BRANCH', value: 'master'), string(name: 'DESTINATION', value: 'laboratory'), string(name: 'VERSION', value: NEW_VERSION)], propagate: false, wait: false
                    }
                }
            }
        }
    }
}
