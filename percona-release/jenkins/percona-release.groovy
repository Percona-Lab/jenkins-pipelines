library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        mkdir test
    """
    switch("${STAGE_PARAM}"){
        case "SOURCE" :
            sh """
                cd test
                export build_dir=\$(pwd -P)
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -x -c "
                    yum -y install git
                    cd \${build_dir}
                    ls -la
                    git clone \${BUILD_REPO}
                    cd percona-repositories
                    git clean -fd
                    git reset --hard
                    git checkout \${BUILD_BRANCH}
                    echo \${VERSION} \${RELEASE}
                    sed -i 's:@@VERSION@@:\"${VERSION}\":g' \${build_dir}/percona-repositories/rpm/percona-release.template 
                    sed -i 's:@@RELEASE@@:\"${RELEASE}\":g' \${build_dir}/percona-repositories/rpm/percona-release.template 
                    cp \${build_dir}/percona-repositories/rpm/percona-release.template \${build_dir}/percona-repositories/rpm/percona-release.spec
                    cd ..
                    tar --owner=0 --group=0 -czf percona-release.tar.gz percona-repositories
                    echo \"UPLOAD=UPLOAD/experimental/BUILDS/percona-release/${BUILD_BRANCH}/${BUILD_ID}\" >> percona-release.properties

                    mkdir -p source_tarball
                    cp percona-release.tar.gz source_tarball
                "
            """
            break
        case "RPM" :
            sh """
                set -o xtrace
                cd test
                cp ../source_tarball/percona-release.tar.gz .
                ls -la
                export build_dir=\$(pwd -P)
                export ARCH=\$(arch)
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -x -c "
                    cd \${build_dir}
                    yum -y install rpm-build
                    tar xvf percona-release.tar.gz
                    rm -fr \${build_dir}/rpmbuild
                    mkdir -p \${build_dir}/rpmbuild/{RPMS/noarch,SOURCES,SRPMS,SPECS,BUILD}
                    cp -av \${build_dir}/percona-repositories/rpm/* \${build_dir}/rpmbuild/SOURCES
                    cp -av \${build_dir}/percona-repositories/rpm/percona-release.spec \${build_dir}/rpmbuild/SPECS
                    cp -av \${build_dir}/percona-repositories/scripts/* \${build_dir}/rpmbuild/SOURCES

                    rpmbuild -ba --define \\"_topdir \${build_dir}/rpmbuild\\" --define \\"_source_filedigest_algorithm 8\\" --define \\"_binary_filedigest_algorithm 8\\" --define \\"_source_payload_digest_algorithm 8\\" --define \\"_binary_payload_digest_algorithm 8\\" \${build_dir}/rpmbuild/SPECS/percona-release.spec

                    mkdir -p srpm
                    cp rpmbuild/SRPMS/*.rpm srpm

                    mkdir -p rpm
                    cp rpmbuild/RPMS/noarch/*.rpm rpm/
                "
             """
             break
        case "DEB" :
             sh """
                set -o xtrace
                cd test
                cp ../source_tarball/percona-release.tar.gz .
                ls -la
                export build_dir=\$(pwd -P)
                export ARCH=\$(arch)
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -x -c "
                    cd \${build_dir}
                    apt-get update
                    export DEBIAN_FRONTEND=\\"noninteractive\\"
                    apt-get -y install devscripts debhelper debconf lsb-release
                    tar xvf percona-release.tar.gz
                    cd percona-repositories
                    cp -av scripts/* deb/
                    mv deb/percona-release.sh deb/percona-release
                    mv deb percona-release_\${VERSION}
                    tar czf percona-release_\${VERSION}.orig.tar.gz percona-release_\${VERSION}
                    cd percona-release_\${VERSION}
                    dch --force-distribution -v \\"\${VERSION}-\${RELEASE}.generic\\" \\"Update percona-release package\\"
                    dpkg-buildpackage -rfakeroot
                    mkdir -p \${build_dir}/deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.noble_amd64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.bookworm_amd64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.bullseye_amd64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.buster_amd64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.focal_amd64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.jammy_amd64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.noble_arm64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.bookworm_arm64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.bullseye_arm64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.buster_arm64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.focal_arm64.deb
                    cp ../*.deb \${build_dir}/deb/percona-release_\${VERSION}-\${RELEASE}.jammy_arm64.deb
                "
             """
             break
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/percona-repositories.git',
            description: 'URL for mysql-shell packaging repository',
            name: 'BUILD_REPO')
        string(
            defaultValue: 'release-1.0-28',
            description: 'Tag/Branch for mysql-shell packaging repository',
            name: 'BUILD_BRANCH')
        string(
            defaultValue: '1.0',
            name: 'VERSION')
        string(
            defaultValue: '28',
            name: 'RELEASE')
        choice(
            choices: 'testing\nlaboratory\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15', artifactNumToKeepStr: '15'))
    }
    stages {
         stage('Create percona-release source tarball') {
            steps {
                cleanUpWS()
                buildStage("oraclelinux:8", "SOURCE")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-release.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-release.properties
                   cat uploadPath
                   pwd
                   ls -la test
                   cp -r test/source_tarball .
                   ls -la source_tarball
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build percona-release packages') {
            parallel {
                stage('RPM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "RPM")
                        sh '''
                            pwd
                            ls -la test/rpm
                            cp -r test/srpm .
                            cp -r test/rpm .
                        '''

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('DEB') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:jammy", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
/*
                stage('RPM ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "RPM")
                        sh '''
                            pwd
                            ls -la test/rpm
                            cp -r test/rpm .
                        '''

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('DEB ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:bionic", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
*/
            }  //parallel
        } // stage

        stage('Sign packages') {
            steps {
                signRPM(params.CLOUD)
                signDEB(params.CLOUD)
            }
        }

        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild(params.CLOUD, 'prel', COMPONENT)
            }
        }

    }
    post {
        success {
            // slackNotify("", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${BUILD_BRANCH}, path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
           // slackNotify("", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
