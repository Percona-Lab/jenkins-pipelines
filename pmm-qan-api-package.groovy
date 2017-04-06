pipeline {
    environment {
        specName = 'percona-qan-api'
        repo     = 'percona/qan-api'
    }
    agent {
        label 'rpm-mock'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: '',
            name: 'GIT_BRANCH')
        choice(
            choices: 'laboratory\npmm',
            description: '',
            name: 'DESTINATION')
        string(
            defaultValue: '1.1.3',
            description: '',
            name: 'VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        pollSCM '* * * * *'
    }

    stages {
        stage('Fetch spec files') {
            steps {
                slackSend channel: '#pmm-jenkins', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"
                git poll: true, branch: GIT_BRANCH, url: "https://github.com/${repo}.git"
                sh '''
                    git rev-parse HEAD         > gitCommit
                    git rev-parse --short HEAD > shortCommit
                '''
                stash includes: 'gitCommit,shortCommit', name: 'gitCommit'
                deleteDir()
                sh '''
                    git clone https://github.com/Percona-Lab/pmm-server-packaging.git ./
                    git show --stat
                '''
                unstash 'gitCommit'
                sh """
                    sed -i -e "s/global commit.*/global commit \$(cat gitCommit)/" rhel/SPECS/${specName}.spec
                    sed -i -e "s/Version:.*/Version: $VERSION/" rhel/SPECS/${specName}.spec
                    head -15 rhel/SPECS/${specName}.spec
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

        stage('Upload to repo.ci.percona.com') {
            agent {
                label 'master'
            }
            steps {
                deleteDir()
                unstash 'rpms'
                unstash 'gitCommit'
                sh """
                    export path_to_build="${DESTINATION}/BUILDS/pmm-server/pmm-server-${VERSION}/${GIT_BRANCH}/\$(cat shortCommit)/${env.BUILD_NUMBER}"

                    ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com \
                    mkdir -p UPLOAD/\${path_to_build}/source/redhat \
                             UPLOAD/\${path_to_build}/binary/redhat/7/x86_64

                    scp -i ~/.ssh/percona-jenkins-slave-access \
                        `find result-repo -name '*.src.rpm'` \
                        uploader@repo.ci.percona.com:UPLOAD/\${path_to_build}/source/redhat/

                    scp -i ~/.ssh/percona-jenkins-slave-access \
                        `find result-repo -name '*.noarch.rpm' -o -name '*.x86_64.rpm'` \
                        uploader@repo.ci.percona.com:UPLOAD/\${path_to_build}/binary/redhat/7/x86_64/
                """
            }
        }

        stage('Sign RPMs') {
            agent {
                label 'master'
            }
            steps {
                unstash 'gitCommit'
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    sh """
                        export path_to_build="${DESTINATION}/BUILDS/pmm-server/pmm-server-${VERSION}/${GIT_BRANCH}/\$(cat shortCommit)/${env.BUILD_NUMBER}"

                        ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com " \
                            /bin/bash -xc ' \
                                ls UPLOAD/\${path_to_build}/binary/redhat/7/x86_64/*.rpm \
                                    | xargs -n 1 signpackage --verbose --password ${SIGN_PASSWORD} --rpm \
                            '"
                    """
                }
            }
        }

        stage('Push to RPM repository') {
            agent any
            steps {
                unstash 'gitCommit'
                script {
                    def path_to_build = sh(returnStdout: true, script: "echo ${DESTINATION}/BUILDS/pmm-server/pmm-server-${VERSION}/${GIT_BRANCH}/\$(cat shortCommit)/${env.BUILD_NUMBER}").trim()
                    build job: 'push-to-rpm-repository', parameters: [string(name: 'PATH_TO_BUILD', value: "${path_to_build}"), string(name: 'DESTINATION', value: "${DESTINATION}")]
                    build job: 'sync-repos-to-production', parameters: [booleanParam(name: 'REVERSE', value: false)]
                }
            }
        }
    }

    post {
        success {
            slackSend channel: '#pmm-jenkins', color: '#00FF00', message: "[${specName}]: build finished"
        }
        failure {
            slackSend channel: '#pmm-jenkins', color: '#FF0000', message: "[${specName}]: build failed"
            archiveArtifacts "result-repo/results/epel-7-x86_64/${specName}-*/*.log"
        }
        always {
            deleteDir()
        }
    }
}
