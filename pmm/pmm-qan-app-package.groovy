void checkRPM(String RPM_NAME) {
    node('master') {
        script {
            EXISTS = sh(
                    script: """
                        ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com \
                            ls "/srv/repo-copy/${DESTINATION}/7/RPMS/x86_64/${RPM_NAME}" \
                            | wc -l || :
                    """,
                returnStdout: true
            ).trim()
            echo "EXISTS: ${EXISTS}"
            if (EXISTS != "0") {
                echo "WARNING: RPM package is already exists, skip building."
                currentBuild.result = 'UNSTABLE'
            }
        }
    }
}

pipeline {
    environment {
        specName = 'percona-qan-app'
        repo     = 'percona/qan-app'
    }
    agent {
        label 'centos7-64'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/qan-app repository checkout',
            name: 'GIT_BRANCH')
        choice(
            choices: 'laboratory\npmm',
            description: 'publish result package to internal or external repository',
            name: 'DESTINATION')
        string(
            defaultValue: '1.1.6',
            description: 'version of result package',
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
                git poll: true, branch: GIT_BRANCH, url: "https://github.com/${repo}.git"
                sh '''
                    git rev-parse HEAD         > gitCommit
                    git rev-parse --short HEAD > shortCommit
                '''
                stash includes: 'gitCommit,shortCommit', name: 'gitCommit'
                deleteDir()
                sh '''
                    git clone https://github.com/percona/pmm-server-packaging.git ./
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
                    checkRPM(RPM_NAME)
                }
            }
        }

        stage('Build RPMs') {
            when { expression { return currentBuild.result != 'UNSTABLE' } }
            steps {
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"
                sh 'mockchain -m --define="dist .el7" -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/*.src.rpm'
                stash includes: 'result-repo/results/epel-7-x86_64/*/*.rpm', name: 'rpms'
            }
        }

        stage('Upload to repo.ci.percona.com') {
            when { expression { return currentBuild.result != 'UNSTABLE' } }
            agent { label 'master' }
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
            when { expression { return currentBuild.result != 'UNSTABLE' } }
            agent { label 'master' }
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
            when { expression { return currentBuild.result != 'UNSTABLE' } }
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
