def call(String specName, String repo, String branch = 'master') {
pipeline {
    agent {
        label 'min-centos-7-x64'
    }
    parameters {
        string(
            defaultValue: branch,
            description: 'Tag/Branch for repository checkout',
            name: 'GIT_BRANCH')
        choice(
            choices: 'laboratory\npmm-experimental',
            description: 'publish result package to internal or external repository',
            name: 'DESTINATION')
        string(
            defaultValue: '1.6.0',
            description: 'version of result package',
            name: 'VERSION')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
        skipDefaultCheckout()
        skipStagesAfterUnstable()
    }
    triggers {
        pollSCM '* * * * *'
    }

    stages {
        stage('Fetch spec files') {
            steps {
                // echo input
                sh '''
                    echo "
                        VERSION:     ${VERSION}
                        GIT_BRANCH:  ${GIT_BRANCH}
                        DESTINATION: ${DESTINATION}
                    "
                '''

                // install build tools
                sh '''
                    sudo yum -y install rpm-build mock git rpmdevtools
                    sudo usermod -aG mock `id -u -n`
                    sudo sed -i "1 i\\config_opts['plugin_conf']['tmpfs_enable'] = True" /etc/mock/epel-7-x86_64.cfg
                '''

                // get commit ID
                script {
                    try {
                        git poll: true, branch: GIT_BRANCH, url: "https://github.com/${repo}.git"
                    } catch (error) {
                        deleteDir()
                        sh """
                            git clone https://github.com/${repo}.git ./
                            git checkout ${GIT_BRANCH}
                        """
                    }
                }
                sh '''
                    git rev-parse HEAD         > gitCommit
                    git rev-parse --short HEAD > shortCommit
                '''
                stash includes: 'gitCommit,shortCommit', name: 'gitCommit'

                // prepare spec file
                deleteDir()
                git poll: true, url: 'https://github.com/percona/pmm-server-packaging.git'
                unstash 'gitCommit'
                sh """
                    git rev-parse HEAD
                    sed -i -e "s/global commit.*/global commit \$(cat gitCommit)/" rhel/SPECS/${specName}.spec
                    sed -i -e "s/Version:.*/Version: ${VERSION}/" rhel/SPECS/${specName}.spec
                """
            }
        }

        stage('Fetch sources') {
            steps {
                sh """
                    ls rhel/SPECS/${specName}.spec \
                       rhel/SPECS/golang.spec \
                        | xargs -n 1 spectool -g -C rhel/SOURCES
                """
            }
        }

        stage('Build SRPMs') {
            steps {
                sh """
                    sed -i -e 's/.\\/run.bash/#.\\/run.bash/' rhel/SPECS/golang.spec
                    rpmbuild --define "_topdir rhel" -bs rhel/SPECS/${specName}.spec
                    rpmbuild --define "_topdir rhel" -bs rhel/SPECS/go-srpm-macros.spec
                    rpmbuild --define "_topdir rhel" -bs rhel/SPECS/golang.spec
                """
                script {
                    RPM_NAME = sh(
                        script: """
                            ls rhel/SRPMS/${specName}-*.src.rpm \
                                | sed -r " \
                                    s|^rhel/SRPMS/||; \
                                    s|[0-9]{10}|*|; \
                                    s|[.]el7[.]centos[.]src[.]rpm\$|.*|; \
                                "
                        """,
                        returnStdout: true
                    ).trim()
                    checkRPM(DESTINATION, RPM_NAME)
                }
            }
        }

        stage('Build Golang') {
            steps {
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/golang-1.*.src.rpm'
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/go-srpm-macros-*.src.rpm'
            }
        }

        stage('Build RPMs') {
            steps {
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/*.src.rpm'
                stash includes: 'result-repo/results/epel-7-x86_64/*/*.rpm', name: 'rpms'
            }
        }

        stage('Push to internal repository') {
            steps {
                uploadRPM()
            }
        }

        stage('Sign RPMs') {
            steps {
                signRPM()
            }
        }

        stage('Push to public repository') {
            steps {
                sync2Prod(DESTINATION)
            }
        }
    }

    post {
        always {
            script {
                if (currentBuild.result == 'FAILURE') {
                    archiveArtifacts "result-repo/results/epel-7-x86_64/${specName}-*/*.log"
                }
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished"
                } else if (currentBuild.result == 'UNSTABLE') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build skipped"
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build ${currentBuild.result}"
                }
            }
            deleteDir()
        }
    }
}
}
