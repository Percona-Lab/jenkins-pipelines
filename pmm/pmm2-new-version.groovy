pipeline {
    agent {
        label 'master'
    }
    parameters {
        string(
            defaultValue: '',
            description: 'New PMM2 version',
            name: 'NEW_VERSION')
    }
    options {
        skipStagesAfterUnstable()
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Commit pmm-update') {
            steps {
                deleteDir()
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        if [ -z "${NEW_VERSION}" ]; then
                            echo 'New PMM2 version was not set'
                            exit 1
                        fi

                        echo "/usr/bin/ssh -i "${SSHKEY}" -o StrictHostKeyChecking=no \\\"\\\$@\\\"" > github-ssh.sh
                        chmod 755 github-ssh.sh
                        export GIT_SSH=$(pwd -P)/github-ssh.sh
                        git clone git@github.com:percona/pmm-update --branch PMM-2.0

                        pushd pmm-update
                            git config --global user.email "dev-services@percona.com"
                            git config --global user.name "PMM2 Jenkins"

                            LATEST_PLAYBOOK_DIR=\$(
                                find ansible \
                                    -mindepth 1 \
                                    -maxdepth 1 \
                                    -type d \
                                    | sort \
                                    | tail -1
                            )
                            OLD_VERSION=\$(head -1 \$LATEST_PLAYBOOK_DIR/main.yml | cut -dv -f 2)
                            MAJOR=\$(echo ${NEW_VERSION} | cut -d. -f 1)
                            MINOR=\$(echo ${NEW_VERSION} | cut -d. -f 2)
                            PATCH=\$(echo ${NEW_VERSION} | cut -d. -f 3 | awk -F'-' '{print $1}')
                            PRE_RELEASE=\$(echo ${NEW_VERSION}  | awk -F'-' '{print $2}')
                            NEW_PLAYBOOK_DIR=ansible/\$(printf "v%02i%02i%02i%s" "\$MAJOR" "\$MINOR" "\$PATCH" "\$PRE_RELEASE")

                            mkdir -p \$NEW_PLAYBOOK_DIR
                            cp \$LATEST_PLAYBOOK_DIR/main.yml \$NEW_PLAYBOOK_DIR/main.yml

                            sed \
                                -i'' \
                                -r "s/- (pmm-\\S+|percona-\\S+|rds_exporter)-\$OLD_VERSION\\\$/- \\1-${NEW_VERSION}/" \
                                \$NEW_PLAYBOOK_DIR/main.yml
                            sed \
                                -i'' \
                                -e "s/# v\$OLD_VERSION\\\$/# v${NEW_VERSION}/" \
                                \$NEW_PLAYBOOK_DIR/main.yml
                            diff \$LATEST_PLAYBOOK_DIR/main.yml \$NEW_PLAYBOOK_DIR/main.yml || :

                            git pull
                            git add \$NEW_PLAYBOOK_DIR/main.yml
                            git commit -a -m "up PMM2 to ${NEW_VERSION}"
                            git config --global push.default matching
                            git push
                        popd
                    '''
                }
            }
        }
        stage('Set version') {
            steps {
                build job: 'pmm2-submodules-rewind', parameters: [
                    string(name: 'GIT_BRANCH', value: 'PMM-2.0'),
                    string(name: 'VERSION', value: NEW_VERSION)
                ]
            }
        }
        stage('Build PMM2') {
            parallel {
                stage('Build PMM2 Server') {
                    steps {
                        build job: 'pmm2-server-autobuild', parameters: [
                            string(name: 'GIT_BRANCH', value: 'PMM-2.0')
                        ]
                    }
                }
                stage('Build PMM2 Client') {
                    steps {
                        build job: 'pmm2-client-autobuilds', parameters: [
                            string(name: 'GIT_BRANCH', value: 'PMM-2.0')
                        ]
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
