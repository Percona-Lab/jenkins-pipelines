pipeline {
    agent {
        label 'jenkins'
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
        string(
            description: 'Must be in form $DESTINATION/*****/$releaseXXX/$revision',
            name: 'PERCONA_REPLICATION_MANAGER_PATH'
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
            description: "Skips sync-production-downloads stage",
            name: 'SKIP_PRODUCTION_DOWNLOADS'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips refresh-downloads-area stage",
            name: 'SKIP_PRODUCTION_REFRESH'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips checking CVE stage",
            name: 'SKIP_CVE_TEST'
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
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                       ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                           if [ ${COMPONENT} = RELEASE ]; then
                               curl -k https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
                           fi
ENDSSH
                    """
                }
            }
        }

        stage('Check docker images by Trivy') {
            when {
                expression { params.SKIP_CVE_TEST == false }
            }
            environment {
                TRIVY_LOG = "trivy-high-junit.xml"
            }
            steps {
                script {
                    try {
                        // 🔹 Install Trivy if not already installed
                        sh '''
                            uname -a
                            cat /etc/os-release
                            if ! command -v trivy &> /dev/null; then
                                echo "🔄 Installing Trivy..."
                                dnf install -y wget ca-certificates gnupg2

                                tee /etc/yum.repos.d/trivy.repo > /dev/null <<'EOF'
[trivy]
name=Trivy repository
baseurl=https://aquasecurity.github.io/trivy-repo/rpm/releases/$basearch/
gpgcheck=1
enabled=1
gpgkey=https://aquasecurity.github.io/trivy-repo/rpm/public.key
EOF

                                dnf install -y trivy
                                trivy --version
                            else
                                echo "✅ Trivy is installed."
                            fi
                        '''
                        // 🔹 Define the image tags
                        def imageList = [
                            "perconalab/haproxy:latest",
                            "perconalab/proxysql2:latest",
                            "perconalab/percona-toolkit:latest",
                            "perconalab/percona-xtrabackup:latest",
                            "perconalab/percona-xtradb-cluster:latest",
                            "perconalab/percona-server:latest",
                            "perconalab/percona-orchestrator:latest"
                        ]
                        // 🔹 Scan images and store logs
                            imageList.each { image ->
                                echo "🔍 Scanning ${image}..."
                                def result = sh(script: """#!/bin/bash
                                    set -e
                                    trivy image --quiet \
                                      --format table --timeout 10m0s --ignore-unfixed --exit-code 1 --scanners vuln --severity HIGH,CRITICAL ${image}
                                    echo "TRIVY_EXIT_CODE=\$?"
                                """, returnStatus: true)
                                echo "Actual Trivy exit code: ${result}"
                            // 🔴 Fail the build if vulnerabilities are found
                                if (result != 0) {
                                    sh """
                                        trivy image --quiet \
                                          --format table --timeout 10m0s --ignore-unfixed --exit-code 0 --scanners vuln \
                                          --severity HIGH,CRITICAL ${image} | tee -a ${TRIVY_LOG}
                                    """
                                    error "❌ Trivy detected vulnerabilities in ${image}. See ${TRIVY_LOG} for details."
                                } else {
                                    echo "✅ No critical vulnerabilities found in ${image}."
                                }
                            }
                } catch (Exception e) {
                    error "❌ Trivy scan failed: ${e.message}"
                }
            }
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
            script {
                currentBuild.description = "Repo: ${REPOSITORY}-${REPOSITORY_VERSION}/${COMPONENT}"
            }
            deleteDir()
        }
    }
}
