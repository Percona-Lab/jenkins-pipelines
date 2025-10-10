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
                    git clone \${BUILD_URL}
                    cd qpress-packaging
                    git clean -fd
                    git reset --hard
                    git checkout \${BUILD_BRANCH}
                    echo \${VERSION} \${RELEASE}
                    sed -i 's/Version:.*/Version:        \"${VERSION}\"/g' \${build_dir}/qpress-packaging/rpm/SPECS/qpress.spec
                    sed -i 's/Release:.*/Release:        \"${RELEASE}\"%{?dist}/g' \${build_dir}/qpress-packaging/rpm/SPECS/qpress.spec
                    cat \${build_dir}/qpress-packaging/rpm/SPECS/qpress.spec
                    cd ..
                    tar --owner=0 --group=0 -czf qpress-packaging.tar.gz qpress-packaging
                    echo \"UPLOAD=UPLOAD/experimental/BUILDS/qpress/${BUILD_BRANCH}/${BUILD_ID}\" >> qpress.properties

                    mkdir -p source_tarball
                    cp qpress-packaging.tar.gz source_tarball
                "
            """
            break
        case "RPM" :
            sh """
                set -o xtrace
                cd test
                cp ../source_tarball/qpress-packaging.tar.gz .
                ls -la
                export build_dir=\$(pwd -P)
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -x -c "
                    export ARCH=\\\$(arch)
                    export RHEL=\\\$(rpm --eval %rhel)

                    yum -y install wget gcc gcc-c++ rpm-build make git

                    cd \${build_dir}

                    #git clone ${BUILD_URL}
                    #cd qpress-packaging

                    tar xvf qpress-packaging.tar.gz
                    cd qpress-packaging

                    wget \"${QPRESS_SOURCE}\"
                    tar -xvzf 20220819.tar.gz
                    cd qpress-20220819
                    zip -q qpress-11-source.zip *
                    mv qpress-11-source.zip ../
                    cd ../
                    rm -rf qpress-20220819 20220819.tar.gz
                    rm -rf deb

                    rm -fr \${build_dir}/rpmbuild
                    mkdir -p \${build_dir}/rpmbuild/{RPMS/\${ARCH},SOURCES,SRPMS,SPECS,BUILD}
                    cp -av \${build_dir}/qpress-packaging/rpm/SOURCES/* \${build_dir}/rpmbuild/SOURCES
                    cp -av \${build_dir}/qpress-packaging/rpm/SPECS/* \${build_dir}/rpmbuild/SPECS
                    cp -av \${build_dir}/qpress-packaging/qpress-11-source.zip \${build_dir}/rpmbuild/SOURCES
                    cd ..

                    rpmbuild -ba --define \\"debug_package %{nil}\\" rpmbuild/SPECS/qpress.spec --define \\"_topdir \$PWD/rpmbuild\\"

                    mkdir -p srpm
                    cp rpmbuild/SRPMS/*.rpm srpm

                    mkdir -p rpm
                    cp rpmbuild/RPMS/*/*.rpm rpm/
                "
             """
             break
        case "DEB" :
             sh """
                set -o xtrace
                cd test
                cp ../source_tarball/qpress-packaging.tar.gz .
                ls -la
                export build_dir=\$(pwd -P)
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -x -c "
                    export ARCH=\\\$(arch)
                    cd \${build_dir}
                    until DEBIAN_FRONTEND=noninteractive apt update; do
                        echo \\"waiting\\"
                        sleep 10
                    done
                    until DEBIAN_FRONTEND=noninteractive apt-get -y install lsb-release gnupg2; do
                        echo \\"waiting\\"
                        sleep 10
                    done
                    export DEBIAN_VERSION=\\\$(lsb_release -sc)
                    DEBIAN_FRONTEND=noninteractive apt-get -y purge eatmydata || true
                    PKGLIST=\\"bzr curl bison cmake perl libssl-dev gcc g++ libaio-dev libldap2-dev libwrap0-dev gdb unzip gawk\\"
	            PKGLIST=\\"\\\${PKGLIST} libmecab-dev libncurses5-dev libreadline-dev libpam-dev zlib1g-dev libcurl4-openssl-dev\\"
                    PKGLIST=\\"\\\${PKGLIST} libldap2-dev libnuma-dev libjemalloc-dev libc6-dbg valgrind libjson-perl\\"
                    PKGLIST=\\"\\\${PKGLIST} libmecab2 mecab mecab-ipadic zip unzip wget\\"
                    PKGLIST=\\"\\\${PKGLIST} build-essential debhelper devscripts lintian diffutils patch patchutils\\"
                    if [ \\\$DEBIAN_VERSION = focal -o  \\\$DEBIAN_VERSION = bullseye -o \\\$DEBIAN_VERSION = jammy -o  \\\$DEBIAN_VERSION = bookworm -o \\\$DEBIAN_VERSION = trixie -o  \\\$DEBIAN_VERSION = noble ]; then
                        PKGLIST=\\"\\\${PKGLIST} python3-mysqldb\\"
                    else
                        PKGLIST=\\"\\\${PKGLIST} python-mysqldb\\"
                    fi
                    DEBIAN_FRONTEND=noninteractive apt-get -y install \\\${PKGLIST}

                    wget \\"\${QPRESS_SOURCE}\\"
                    tar -xvzf 20220819.tar.gz
                    cd qpress-20220819
                    zip -q qpress-11-source.zip *
                    mv qpress-11-source.zip ../
                    cd ../
                    unzip ./qpress-11-source.zip
                    tar xvf qpress-packaging.tar.gz
                    mv qpress-packaging/deb/debian .
                    dch -m -D \\"\$(lsb_release -sc)\\" --force-distribution -v \\"\${VERSION}-\${RELEASE}.\\\$(lsb_release -sc)\\" \\"Update qpress distribution\\"
                    dpkg-buildpackage -sa -uc -us -b
                    mkdir -p \${build_dir}/deb
                    cp ../*.deb \${build_dir}/deb/
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
            defaultValue: 'https://github.com/percona-lab/qpress-packaging.git',
            description: 'URL for qpress packaging repository',
            name: 'BUILD_URL')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for qpress packaging repository',
            name: 'BUILD_BRANCH')
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/qpress/archive/refs/tags/20220819.tar.gz',
            description: 'Source for qpress',
            name: 'QPRESS_SOURCE')
        string(
            defaultValue: '11',
            description: 'Version value',
            name: 'VERSION')
        string(
            defaultValue: '3',
            description: 'Release value',
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
         stage('Create qpress source tarball') {
            steps {
                cleanUpWS()
                buildStage("oraclelinux:8", "SOURCE")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/qpress.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/qpress.properties
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
        stage('Build qpress packages') {
            parallel {
                stage('Oracle Linux 8') {
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
                stage('Oracle Linux 8 ARM') {
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
                            cp -r test/srpm .
                            cp -r test/rpm .
                        '''

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "RPM")
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
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "RPM")
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
                stage('Oracle Linux 10') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "RPM")
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
                stage('Oracle Linux 10 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:10", "RPM")
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
                stage('Amazon Linux 2023') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "RPM")
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
                stage('Amazon Linux 2023 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("amazonlinux:2023", "RPM")
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
                stage('Ubuntu Focal (20.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
		stage('Ubuntu Focal (20.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:focal", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye (11)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
		stage('Debian Bullseye (11) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bullseye", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy (22.04)') {
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
		stage('Ubuntu Jammy (22.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
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
                stage('Debian Bookworm (12)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
		stage('Debian Bookworm (12) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:bookworm", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie (13)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Trixie (13) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("debian:trixie", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble (24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
		stage('Ubuntu Noble (24.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        buildStage("ubuntu:noble", "DEB")
                        sh '''
                            pwd
                            ls -la test/deb
                            cp -r test/deb .
                        '''

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
            }
        } 
        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild(params.CLOUD, 'tools', COMPONENT)
            }
        }

    }
    post {
        success {
            script {
                currentBuild.description = "Built on ${BUILD_BRANCH}, path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
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
