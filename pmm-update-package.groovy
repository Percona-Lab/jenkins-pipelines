def app         = 'pmm-update'
def specName    = "${app}"
def repo        = "Percona-Lab/${app}"
def rpmArch     = 'noarch'

def product     = 'pmm-server'
def arch        = 'x86_64'
def os          = 'redhat'
def osVersion   = '7'

echo """
    DESTINATION: ${DESTINATION}
    GIT_BRANCH:  ${GIT_BRANCH}
    VERSION:     ${VERSION}
"""

node('centos7-64') {
    timestamps {
        stage("Fetch spec files") {
            slackSend channel: '@mykola', message: "${app} rpm: build started"
            git poll: true, branch: GIT_BRANCH, url: "https://github.com/${repo}.git"
            gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            shortCommit = gitCommit.take(6)
            deleteDir()
            git poll: false, url: 'https://github.com/Percona-Lab/pmm-server-packaging.git'
            sh """
                sed -i -e "s/global commit.*/global commit $gitCommit/" rhel/SPECS/${specName}.spec
                sed -i -e "s/Version:.*/Version: $VERSION/" rhel/SPECS/${specName}.spec
                head -15 rhel/SPECS/${specName}.spec
            """
        }

        stage("Fetch sources") {
            sh """
                ls rhel/SPECS/${specName}.spec \
                   rhel/SPECS/golang.spec \
                    | xargs -n 1 spectool -g -C rhel/SOURCES
            """
        }

        stage("Build SRPMs") {
            sh """
                sed -i -e 's/.\\/run.bash/#.\\/run.bash/' rhel/SPECS/golang.spec
                rpmbuild --define "_topdir rhel" -bs rhel/SPECS/${specName}.spec
            """
        }

        stage("Build RPMs") {
            sh 'mockchain -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/*.src.rpm'
            stash includes: 'result-repo/results/epel-7-x86_64/*/*.rpm', name: 'rpms'
        }

        stage("Build Tarball") {
            sh """
                cp result-repo/results/epel-7-x86_64/*/${specName}-1*.${rpmArch}.rpm .
                TAR_NAME=`ls *.rpm | sed -e 's/.el.*//'`
                rpm2cpio *.rpm | cpio -id
                mkdir -p \$TAR_NAME
                mv usr/bin \$TAR_NAME/bin
                tar -zcpf \$TAR_NAME.tar.gz \$TAR_NAME
            """
            stash includes: '*.tar.gz', name: 'tars'
            slackSend channel: '@mykola', message: "${app} rpm: build finished"
        }
    }
}

node {
    stage("Upload to www.percona.com") {
        deleteDir()
        unstash 'tars'
        if ( DESTINATION == 'pmm' ) {
            sh """
                scp -i ~/.ssh/id_rsa_downloads \
                    `find . -name '*.tar.gz'` \
                    jenkins@10.10.9.216:/data/downloads/TESTING/pmm/
            """
        }
    }
    stage("Upload to repo.ci.percona.com") {
        deleteDir()
        unstash 'rpms'
        def path_to_build = "${DESTINATION}/BUILDS/${product}/${product}-${VERSION}/${GIT_BRANCH}/${shortCommit}/${env.BUILD_NUMBER}"
        sh """
            ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com \
                mkdir -p UPLOAD/${path_to_build}/source/${os} \
                         UPLOAD/${path_to_build}/binary/${os}/${osVersion}/${arch}

            scp -i ~/.ssh/percona-jenkins-slave-access \
                `find result-repo -name '*.src.rpm'` \
                uploader@repo.ci.percona.com:UPLOAD/${path_to_build}/source/${os}/

            scp -i ~/.ssh/percona-jenkins-slave-access \
                `find result-repo -name '*.noarch.rpm' -o -name '*.x86_64.rpm'` \
                uploader@repo.ci.percona.com:UPLOAD/${path_to_build}/binary/${os}/${osVersion}/${arch}/
        """
    }

    stage('Sign RPMs') {
        def path_to_build = "${DESTINATION}/BUILDS/${product}/${product}-${VERSION}/${GIT_BRANCH}/${shortCommit}/${env.BUILD_NUMBER}"
        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            sh """
                ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com " \
                    /bin/bash -xc ' \
                        ls UPLOAD/${path_to_build}/binary/${os}/${osVersion}/${arch}/*.rpm \
                            | xargs -n 1 signpackage --verbose --password ${SIGN_PASSWORD} --rpm \
                    '"
            """
        }
    }
}

stage('Push to RPM repository') {
    def path_to_build = "${DESTINATION}/BUILDS/${product}/${product}-${VERSION}/${GIT_BRANCH}/${shortCommit}/${env.BUILD_NUMBER}"
    build job: 'push-to-rpm-repository', parameters: [string(name: 'PATH_TO_BUILD', value: "${path_to_build}"), string(name: 'DESTINATION', value: "${DESTINATION}")]
    build job: 'sync-repos-to-production', parameters: [booleanParam(name: 'REVERSE', value: false)]
}
