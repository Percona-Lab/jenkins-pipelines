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
                    export ARCH=\\\$(arch)
                    export RHEL=\\\$(rpm --eval %rhel)
                    if [ \\\${RHEL} = 8 ]; then
                        sed -i 's/mirrorlist=/#mirrorlist=/g' /etc/yum.repos.d/CentOS-*
                        sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
                    fi

                    yum -y install rpm-build gcc gcc-c++ make automake autoconf libxslt wget

                    cd \${build_dir}
                    wget --no-check-certificate \${JEMALLOC_RPM_SOURCE}

                    rpm2cpio jemalloc-3.6.0-1.el7.src.rpm | cpio -id

                    tar -xvf jemalloc-3.6.0.tar.bz2
                    sed -i 's/@EXTRA_LDFLAGS@/@EXTRA_LDFLAGS@ -Wl,--allow-multiple-definition/g' jemalloc-3.6.0/Makefile.in
                    rm -rf jemalloc-3.6.0.tar.bz2
                    tar -cjf jemalloc-3.6.0.tar.bz2 jemalloc-3.6.0/
                    rm -rf jemalloc-3.6.0/

                    ls -la
                    echo \${VERSION} \${RELEASE}
                    echo \"UPLOAD=UPLOAD/experimental/BUILDS/jemalloc/${VERSION}-${RELEASE}/${BUILD_ID}\" >> jemalloc.properties

                    mkdir -p source_tarball
                    cp jemalloc-3.6.0.tar.bz2 source_tarball
                "
            """
            break
        case "RPM" :
            sh """
                set -o xtrace
                cd test
                ls -la
                export build_dir=\$(pwd -P)
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -x -c "
                    export ARCH=\\\$(arch)
                    export RHEL=\\\$(rpm --eval %rhel)
                    if [ \\\${RHEL} = 8 ]; then
                        sed -i 's/mirrorlist=/#mirrorlist=/g' /etc/yum.repos.d/CentOS-*
                        sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
                    fi

                    yum -y install rpm-build gcc gcc-c++ make automake autoconf libxslt wget tree

                    cd \${build_dir}
                    wget --no-check-certificate \${JEMALLOC_RPM_SOURCE}

                    rpm2cpio jemalloc-3.6.0-1.el7.src.rpm | cpio -id
                    tree

                    tar -xvf jemalloc-3.6.0.tar.bz2
                    sed -i 's/@EXTRA_LDFLAGS@/@EXTRA_LDFLAGS@ -Wl,--allow-multiple-definition/g' jemalloc-3.6.0/Makefile.in
                    rm -rf jemalloc-3.6.0.tar.bz2
                    tar -cjf jemalloc-3.6.0.tar.bz2 jemalloc-3.6.0/
                    rm -rf jemalloc-3.6.0/

                    mkdir -p \${build_dir}/rpmbuild/{RPMS/\\\${ARCH},SOURCES,SRPMS,SPECS,BUILD}

                    mv jemalloc.spec rpmbuild/SPECS/
                    mv jemalloc* rpmbuild/SOURCES/

                    sed -i '1i Epoch: 1' rpmbuild/SPECS/jemalloc.spec
                    sed -i 's/Requires:       %{name} = /Requires:       %{name} = 1:/g' rpmbuild/SPECS/jemalloc.spec

                    rpmbuild -bs --define \\"dist .generic\\" rpmbuild/SPECS/jemalloc.spec --define \\"_topdir \${build_dir}/rpmbuild\\"
                    mkdir -p srpm
                    cp rpmbuild/SRPMS/*.rpm srpm
                    mv rpmbuild/SRPMS/* ./

                    rm -rf rpmbuild
                    mkdir rpmbuild

                    rpmbuild --define \\"_topdir \${build_dir}/rpmbuild\\" --rebuild jemalloc-*.src.rpm
                    mkdir -p rpm
                    cp rpmbuild/RPMS/*/*.rpm rpm/
                "
            """
            break
        case "DEB" :
             sh """
                set -o xtrace
                cd test
                export build_dir=\$(pwd -P)
                docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -x -c "
                    export ARCH=\\\$(arch)
                    cd \${build_dir}
                    until DEBIAN_FRONTEND=noninteractive apt update; do
                        echo \\"waiting\\"
                        sleep 10
                    done
                    until DEBIAN_FRONTEND=noninteractive apt-get -y install dpkg-dev wget debhelper docbook-xsl xsltproc devscripts automake; do
                        echo \\"waiting\\"
                        sleep 10
                    done
                    export DEBIAN_VERSION=\\\$(lsb_release -sc)
                    DEBIAN_FRONTEND=noninteractive apt-get -y purge eatmydata || true
                    if [ \\\$DEBIAN_VERSION = focal -o  \\\$DEBIAN_VERSION = bullseye -o \\\$DEBIAN_VERSION = jammy -o  \\\$DEBIAN_VERSION = noble ]; then
                        PKGLIST=\\"gcc-9\\"
                    elif [ \\\$DEBIAN_VERSION = trixie ]; then
                        PKGLIST=\\"gcc-13\\"
                    else
                        PKGLIST=\\"gcc-11\\"
                    fi
                    if [ \\\$DEBIAN_VERSION = focal -o  \\\$DEBIAN_VERSION = bullseye -o \\\$DEBIAN_VERSION = jammy -o  \\\$DEBIAN_VERSION = bookworm -o  \\\$DEBIAN_VERSION = noble -o  \\\$DEBIAN_VERSION = trixie ]; then
                        PKGLIST=\\"\\\${PKGLIST} python3-mysqldb\\"
                    else
                        PKGLIST=\\"\\\${PKGLIST} python-mysqldb\\"
                    fi
                    DEBIAN_FRONTEND=noninteractive apt-get -y install \\\${PKGLIST}

                    if [ \\\$DEBIAN_VERSION = focal -o  \\\$DEBIAN_VERSION = bullseye -o \\\$DEBIAN_VERSION = jammy -o  \\\$DEBIAN_VERSION = noble ]; then
                         ln -s -f /usr/bin/g++-9 /usr/bin/g++
                         ln -s -f /usr/bin/gcc-9 /usr/bin/gcc
                         ln -s -f /usr/bin/gcc-ar-9 /usr/bin/gcc-ar
                         ln -s -f /usr/bin/gcc-nm-9 /usr/bin/gcc-nm
                         ln -s -f /usr/bin/gcc-ranlib-9 /usr/bin/gcc-ranlib
                         ln -s -f /usr/bin/x86_64-linux-gnu-g++-9 /usr/bin/x86_64-linux-gnu-g++
                         ln -s -f /usr/bin/x86_64-linux-gnu-gcc-9 /usr/bin/x86_64-linux-gnu-gcc
                         ln -s -f /usr/bin/x86_64-linux-gnu-gcc-ar-9 /usr/bin/x86_64-linux-gnu-gcc-ar
                         ln -s -f /usr/bin/x86_64-linux-gnu-gcc-nm-9 /usr/bin/x86_64-linux-gnu-gcc-nm
                         ln -s -f /usr/bin/x86_64-linux-gnu-gcc-ranlib-9 /usr/bin/x86_64-linux-gnu-gcc-ranlib
                    fi
                    wget \${JEMALLOC_DEB_SOURCE}/jemalloc_3.6.0-2.debian.tar.xz \${JEMALLOC_DEB_SOURCE}/jemalloc_3.6.0-2.dsc \${JEMALLOC_DEB_SOURCE}/jemalloc_3.6.0.orig.tar.bz2

                    dpkg-source -x jemalloc_3.6.0-2.dsc
                    cd jemalloc-3.6.0
                    sed -i 's/@EXTRA_LDFLAGS@/@EXTRA_LDFLAGS@ -Wl,--allow-multiple-definition/g' Makefile.in
                    if [ \\\$DEBIAN_VERSION = bookworm -o  \\\$DEBIAN_VERSION = trixie ]; then
                        if [ \\\$ARCH = aarch64 ]; then
                            sed -i 's/make check/#make check/g' debian/rules
                        fi
                    fi
                    sed -i 's/override_dh_auto_test:/override_dh_builddeb:\\n\\tdh_builddeb -- -Zgzip\n\noverride_dh_auto_test:/g' debian/rules
                    cat debian/rules

                    dch -m -D \\"\$(lsb_release -sc)\\" --force-distribution -v \\"\${VERSION}-\${RELEASE}.\\\$(lsb_release -sc)\\" \\"Update jemalloc distribution\\"
                    dpkg-buildpackage -rfakeroot -us -uc -b

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
/*
        string(
            defaultValue: 'https://github.com/percona-lab/jemalloc-packaging.git',
            description: 'URL for jemalloc packaging repository',
            name: 'BUILD_URL')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for jemalloc packaging repository',
            name: 'BUILD_BRANCH')
*/
        string(
            defaultValue: 'https://downloads.percona.com/downloads/packaging/yum-repo/jemalloc-3.6.0-1.el7.src.rpm',
            description: 'Source for jemalloc',
            name: 'JEMALLOC_RPM_SOURCE')
        string(
            defaultValue: 'https://repo.percona.com/apt/pool/main/j/jemalloc/',
            description: 'Source for jemalloc. Next files are expected jemalloc_3.6.0-2.debian.tar.xz, jemalloc_3.6.0-2.dsc, jemalloc_3.6.0.orig.tar.bz2',
            name: 'JEMALLOC_DEB_SOURCE')
        string(
            defaultValue: '3.6.0',
            description: 'Version value',
            name: 'VERSION')
        string(
            defaultValue: '2',
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
         stage('Create jemalloc source tarball') {
            steps {
                cleanUpWS()
                buildStage("centos:8", "SOURCE")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/jemalloc.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/jemalloc.properties
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
        stage('Build jemalloc packages') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        sh '''
                           echo "============>"
                        '''
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
                currentBuild.description = "${VERSION}-${RELEASE}, path to packages: experimental/${AWS_STASH_PATH}"
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
