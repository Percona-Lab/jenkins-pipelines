def call(String specName, String repo) {
pipeline {
    agent {
        label 'min-centos-7-x64'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/pmm-server repository checkout',
            name: 'GIT_BRANCH')
        choice(
            choices: 'laboratory\npmm-experimental',
            description: 'publish result package to internal or external repository',
            name: 'DESTINATION')
        string(
            defaultValue: '1.5.3',
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

                // prepare spec file
                deleteDir()
                sh '''
                    git clone https://github.com/percona/pmm-server-packaging.git ./
                    git rev-parse HEAD
                '''
                unstash 'gitCommit'
                sh """
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
                """
                script {
                    RPM_NAME = sh(
                        script: '''
                            ls rhel/SRPMS/${specName}-*.src.rpm \
                                | sed -r " \
                                    s|^rhel/SRPMS/||; \
                                    s|[0-9]{10}|*|; \
                                    s|[.]el7[.]centos[.]src[.]rpm\$|.*|; \
                                "
                        ''',
                        returnStdout: true
                    ).trim()
                    checkRPM(DESTINATION, RPM_NAME)
                }
            }
        }

        stage('Build RPMs') {
            steps {
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/*.src.rpm'
                stash includes: 'result-repo/results/epel-7-x86_64/*/*.rpm', name: 'rpms'
            }
        }

        stage('Push to internal repository') {
            agent { label 'master' }
            steps {
                uploadRPM()
            }
        }

        stage('Sign RPMs') {
            agent { label 'master' }
            steps {
                signRPM()
            }
        }

        stage('Push to public repository') {
            agent any
            steps {
                sync2Prod(DESTINATION)
            }
        }
    }

    post {
        success {
            slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished"
            deleteDir()
        }
        unstable {
            slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build skipped"
            deleteDir()
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
            archiveArtifacts "result-repo/results/epel-7-x86_64/${specName}-*/*.log"
            deleteDir()
        }
    }
}
}
