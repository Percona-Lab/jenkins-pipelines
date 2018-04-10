pipeline {
    agent {
        label 'micro-amazon'
    }
    parameters {
        string(
            defaultValue: 'v1.X.0',
            description: 'commit id in pmm-submodules repo! which will be branch point, like: "v1.9.0"',
            name: 'BRANCH_POINT'
        )
        string(
            defaultValue: 'hotfix-1.X.x',
            description: 'Name for new branch, like: "hotfix-1.9.x"',
            name: 'NEW_BRANCH'
        )
        string(
            defaultValue: '1.X.1',
            description: 'new PMM version, like: 1.9.1',
            name: 'NEW_VERSION'
        )
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git branch: 'master', credentialsId: 'GitHub SSH Key', poll: false, url: 'git@github.com:Percona-Lab/pmm-submodules'
                sh """
                    git checkout ${BRANCH_POINT}
                    git checkout -b ${NEW_BRANCH}
    
                    git submodule update --init --jobs 10

                    git config --global user.email "dev-services@percona.com"
                    git config --global user.name "PMM Jenkins"
                    git config --global push.default upstream
                """
            }
        }
        stage('Branch') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        echo "/usr/bin/ssh -i "${SSHKEY}" -o StrictHostKeyChecking=no \\\"\\\$@\\\"" > github-ssh.sh
                        chmod 755 github-ssh.sh
                    '''
                    sh """
                        export GIT_SSH=\$(pwd -P)/github-ssh.sh
                        for NAME in \$(git submodule foreach 'echo \$name' | grep -v '^Entering' | grep -v percona-toolkit); do
                            git config -f .gitmodules submodule.\$NAME.url \$(
                                git config -f .gitmodules submodule.\$NAME.url \
                                    | sed -e 's^https://github.com/^git@github.com:^'
                            )
                        done
                        git submodule sync

                        for NAME in \$(git submodule foreach 'echo \$name' | grep -v "^Entering" | grep -v percona-toolkit); do
                            pushd \$(git config -f .gitmodules submodule.\$NAME.path)
                                git checkout -b ${NEW_BRANCH}
                                git push --set-upstream origin ${NEW_BRANCH}
                            popd
                            git config -f .gitmodules submodule.\$NAME.branch ${NEW_BRANCH}
                        done

                        export GIT_SSH=\$(pwd -P)/github-ssh.sh
                        for NAME in \$(git submodule foreach 'echo \$name' | grep -v '^Entering' | grep -v percona-toolkit); do
                            git config -f .gitmodules submodule.\$NAME.url \$(
                                git config -f .gitmodules submodule.\$NAME.url \
                                    | sed -e 's^git@github.com:^https://github.com/^'
                            )
                        done
                        git submodule sync

                        echo ${NEW_VERSION} > VERSION
                        git submodule sync
                        git submodule update --init --jobs 10
                        git commit -a -m "Switch branches to ${NEW_BRANCH}"
                        git show
                        git push --set-upstream origin ${NEW_BRANCH}
                    """
                }
            }
        }
    }
}
