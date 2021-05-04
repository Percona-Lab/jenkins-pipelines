pipeline {
    agent {
        label 'source-builder'
    }
    parameters {
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'JEMALLOC_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERL_DBD_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'QPRESS_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_HAPROXY_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_PROXYSQL_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_TOOLKIT_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_XTRABACKUP_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_PXC_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_PS_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_MYSQL_SHELL_PATH'
        )
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_ORCHESTRATOR_PATH'
        )
        choice(
            choices: 'PDPS\nPDPXC',
            description: 'Repository push to',
            name: 'REPOSITORY'
        )
        string(
            description: 'Version of repository push to',
            name: 'REPOSITORY_VERSION'
        )
        booleanParam(
            defaultValue: false,
            description: 'if true, will push to both repos',
            name: 'REPOSITORY_VERSION_MAJOR'
        )
        choice(
            choices: 'TESTING\nRELEASE\nEXPERIMENTAL\nLABORATORY',
            description: 'Separate repository to push to',
            name: 'COMPONENT'
        )
        booleanParam(
            defaultValue: false,
            description: "Remove lockfile after unsuccessful push for DEB",
            name: 'REMOVE_LOCKFILE'
        )
        booleanParam(
            defaultValue: false,
            description: "Check to remove sources and binary version if equals pushing",
            name: 'REMOVE_BEFORE_PUSH'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips push-to-rpm-repository stage",
            name: 'SKIP_RPM'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips push-to-apt-repository stage",
            name: 'SKIP_APT'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips sync-repos-to-production stage",
            name: 'SKIP_SYNC'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips clear-cdn-cache stage",
            name: 'SKIP_CDN'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips sync-production-downloads stage",
            name: 'SKIP_PRODUCTION_DOWNLOADS'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips refresh-downloads-area stage",
            name: 'SKIP_PRODUCTION_REFRESH'
        )
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('push-to-rpm-repository') {
            when {
                expression { params.SKIP_RPM == false }
            }
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            for var in "\$(printenv | grep _PATH | sed 's/KEY_PATH.*//')"; do
                                echo "\$var" >> args_pipeline
                            done
                            echo "REPOSITORY=\${REPOSITORY}" >> args_pipeline
                            echo "REPOSITORY_VERSION=\${REPOSITORY_VERSION}" >> args_pipeline
                            echo "REPOSITORY_VERSION_MAJOR=\${REPOSITORY_VERSION_MAJOR}" >> args_pipeline
                            echo "COMPONENT=\${COMPONENT}" >> args_pipeline
                            echo "REMOVE_LOCKFILE=\${REMOVE_LOCKFILE}" >> args_pipeline
                            echo "REMOVE_BEFORE_PUSH=\${REMOVE_BEFORE_PUSH}" >> args_pipeline
                            echo "\$(awk '{\$1="export" OFS \$1} 1' args_pipeline)" > args_pipeline
                            rsync -aHv --delete -e "ssh -o StrictHostKeyChecking=no -i \$KEY_PATH" args_pipeline \$USER@repo.ci.percona.com:/tmp/args_pipeline
                            ssh -o StrictHostKeyChecking=no -i \$KEY_PATH \$USER@repo.ci.percona.com " \
                                export SIGN_PASSWORD=\${SIGN_PASSWORD}
                                bash -x /tmp/args_pipeline
                                wget https://raw.githubusercontent.com/Percona-Lab/jenkins-pipelines/master/ps-pxc-dist/rpm_release.sh -O rpm_release.sh
                                bash -xe rpm_release.sh
                            "
                        """
                    }
                }
            }
        }
        stage('push-to-deb-repository') {
            when {
                expression { params.SKIP_APT == false }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD'),
                    sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')
                    ]){
                        sh """
                            for var in "\$(printenv | grep _PATH | sed 's/KEY_PATH.*//')"; do
                                echo "\$var" >> args_pipeline
                            done
                            echo "REPOSITORY=\${REPOSITORY}" >> args_pipeline
                            echo "REPOSITORY_VERSION=\${REPOSITORY_VERSION}" >> args_pipeline
                            echo "REPOSITORY_VERSION_MAJOR=\${REPOSITORY_VERSION_MAJOR}" >> args_pipeline
                            echo "COMPONENT=\${COMPONENT}" >> args_pipeline
                            echo "REMOVE_LOCKFILE=\${REMOVE_LOCKFILE}" >> args_pipeline
                            echo "REMOVE_BEFORE_PUSH=\${REMOVE_BEFORE_PUSH}" >> args_pipeline
                            echo "\$(awk '{\$1="export" OFS \$1} 1' args_pipeline)" > args_pipeline
                            rsync -aHv --delete -e "ssh -o StrictHostKeyChecking=no -i \$KEY_PATH" args_pipeline \$USER@repo.ci.percona.com:/tmp/args_pipeline
                            ssh -o StrictHostKeyChecking=no -i \$KEY_PATH \$USER@repo.ci.percona.com " \
                                export SIGN_PASSWORD=\${SIGN_PASSWORD}
                                bash -x /tmp/args_pipeline
                                wget https://raw.githubusercontent.com/Percona-Lab/jenkins-pipelines/master/ps-pxc-dist/apt_release.sh -O apt_release.sh
                                bash -xe apt_release.sh
                            "
                        """
                    }
            }
        }
        stage('sync-repos-to-production') {
            when {
                expression { params.SKIP_SYNC == false }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                            echo "REPOSITORY=\${REPOSITORY}" >> args_pipeline
                            echo "REPOSITORY_VERSION=\${REPOSITORY_VERSION}" >> args_pipeline
                            echo "REPOSITORY_VERSION_MAJOR=\${REPOSITORY_VERSION_MAJOR}" >> args_pipeline
                            echo "\$(awk '{\$1="export" OFS \$1} 1' args_pipeline)" > args_pipeline
                            rsync -aHv --delete -e "ssh -o StrictHostKeyChecking=no -i \$KEY_PATH" args_pipeline \$USER@repo.ci.percona.com:/tmp/args_pipeline
                            ssh -o StrictHostKeyChecking=no -i \$KEY_PATH \$USER@repo.ci.percona.com " \
                                bash -x /tmp/args_pipeline
                                wget https://raw.githubusercontent.com/Percona-Lab/jenkins-pipelines/master/ps-pxc-dist/sync_repos_prod.sh -O sync_repos_prod.sh
                                bash -xe sync_repos_prod.sh
                        "
                """
                }
            }
        }
        stage('clear-cdn-cache') {
            when {
                expression { params.SKIP_CDN == false }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i \$KEY_PATH \$USER@repo.ci.percona.com " \
                            bash -x /usr/local/bin/clear_cdn_cache.sh
                        "  
                    """
                }
            }
        }
        stage('sync-production-downloads') {
            when {
                expression { params.SKIP_PRODUCTION_DOWNLOADS == false }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        if [ "\${COMPONENT}" == "RELEASE" ]; then
                           for var in "\$(printenv | grep _PATH | sed 's/KEY_PATH.*//')"; do
                                echo "\$var" >> args_pipeline
                            done
                            echo "REPOSITORY=\${REPOSITORY}" >> args_pipeline
                            echo "REPOSITORY_VERSION=\${REPOSITORY_VERSION}" >> args_pipeline
                            echo "COMPONENT=\${COMPONENT}" >> args_pipeline
                            echo "\$(awk '{\$1="export" OFS \$1} 1' args_pipeline)" > args_pipeline
                            rsync -aHv --delete -e "ssh -o StrictHostKeyChecking=no -i \$KEY_PATH" args_pipeline \$USER@repo.ci.percona.com:/tmp/args_pipeline
                            ssh -o StrictHostKeyChecking=no -i \$KEY_PATH \$USER@repo.ci.percona.com " \
                                bash -x /tmp/args_pipeline
                                wget https://raw.githubusercontent.com/Percona-Lab/jenkins-pipelines/master/ps-pxc-dist/downloads_release.sh -O downloads_release.sh
                                bash -xe downloads_release.sh
                            "
                        fi
                    """
                }
            }
        }
        stage('refresh-downloads-area') {
            when {
                expression { params.SKIP_PRODUCTION_REFRESH == false }
            }
            steps {
                sh """
                    if [ "\${COMPONENT}" == "RELEASE" ]; then
                        sudo apt-get update
                        sudo apt-get -y install curl
                        if [[ "\${COMPONENT}" == "RELEASE" ]]; then
                            wget https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory -O crawl_directory
                        fi
                    fi
                """
            }
        }
    }

    post {
        success {
            slackSend channel: '#releases-ci', color: '#00FF00', message: "${REPOSITORY} distribution: job finished"
        }
        failure {
            slackSend channel: '#releases-ci', color: '#FF0000', message: "${REPOSITORY} distribution: job failed"
        }
        always {
            deleteDir()
        }
    }
}
