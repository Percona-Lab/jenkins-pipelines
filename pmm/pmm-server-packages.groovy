library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    environment {
        specName = '3rd-party'
        repo     = 'percona/pmm-server-packaging'
    }
    agent {
        label 'min-centos-7-x64'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/pmm-server-packaging repository checkout',
            name: 'GIT_BRANCH')
        choice(
            choices: 'laboratory\npmm-experimental',
            description: 'publish result package to internal or external repository',
            name: 'DESTINATION')
        string(
            defaultValue: '1.7.0',
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
            }
        }

        stage('Fetch sources') {
            steps {
                sh """
                    rm -rf  rhel/SPECS/percona-dashboards*.spec \
                            rhel/SPECS/clickhouse.spec \
                            rhel/SPECS/rds_exporter*.spec \
                            rhel/SPECS/pmm-server*.spec \
                            rhel/SPECS/pmm-manage*.spec \
                            rhel/SPECS/pmm-update*.spec \
                            rhel/SPECS/percona-qan-api*.spec \
                            rhel/SPECS/percona-qan-app*.spec
                    ls rhel/SPECS/*.spec | xargs -n 1 spectool -g -C rhel/SOURCES
                """
            }
        }

        stage('Build SRPMs') {
            steps {
                sh """
                    sed -i -e 's/.\\/run.bash/#.\\/run.bash/' rhel/SPECS/golang.spec
                    rpmbuild --define "_topdir rhel" -bs rhel/SPECS/*.spec
                """
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
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo -a http://mirror.centos.org/centos/7/sclo/x86_64/rh/ -a http://mirror.centos.org/centos/7/cr/x86_64/ rhel/SRPMS/*.src.rpm'
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
                    archiveArtifacts "result-repo/results/epel-7-x86_64/*/*.log"
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
