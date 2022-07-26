library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'master'
    }

    environment {
        specName = 'pmm2-release'
        CLIENT_IMAGE = "perconalab/pmm-client:${VERSION}-rc"
        SERVER_IMAGE = "perconalab/pmm-server:${VERSION}-rc"
        PATH_TO_CLIENT = "testing/pmm2-client-autobuilds/pmm2/${VERSION}/pmm-${VERSION}/${PATH_TO_CLIENT}"
    }

    parameters {
        string(
            defaultValue: '2.0.0',
            description: 'PMM2 Server version',
            name: 'VERSION')
        string(
            defaultValue: '',
            description: 'Path to client packages in testing repo. Example: 12aec0c9/3052',
            name: 'PATH_TO_CLIENT')
    }
    stages {
        stage('Push PRM client to public repository') {
            steps {
                script {
                    currentBuild.description = "VERSION: ${VERSION}<br>CLIENT: ${CLIENT_IMAGE}<br>SERVER: ${SERVER_IMAGE}<br>PATH_TO_CLIENT: ${PATH_TO_CLIENT}"
                }
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                set -x
                                set -e
                                #
                                REPOS='PERCONA TOOLS PMM2-CLIENT'

                                for REPOSITORY in \$REPOS; do
                                    if [[ \${REPOSITORY} = PERCONA ]]; then
                                        REPOPATH=repo-copy/percona/yum
                                    fi
                                    if [[ \${REPOSITORY} = TOOLS ]]; then
                                        REPOPATH=repo-copy/tools/yum
                                    fi
                                    if [[ \${REPOSITORY} = PMM2-CLIENT ]]; then
                                        REPOPATH=repo-copy/pmm2-client/yum
                                    fi
                                    cd /srv/UPLOAD/${PATH_TO_CLIENT}
                                    #
                                    # getting the list of RH systems
                                    RHVERS=\$(ls -1 binary/redhat | grep -v 6)
                                    #
                                    # source processing
                                    if [ -d source/redhat ]; then
                                        SRCRPM=\$(find source/redhat -name '*.src.rpm')
                                        for rhel in \${RHVERS}; do
                                            mkdir -p /srv/\${REPOPATH}/release/\${rhel}/SRPMS
                                            cp -v \${SRCRPM} /srv/\${REPOPATH}/release/\${rhel}/SRPMS
                                            createrepo --update /srv/\${REPOPATH}/release/\${rhel}/SRPMS
                                            if [ -f /srv/\${REPOPATH}/release/\${rhel}/SRPMS/repodata/repomd.xml.asc ]; then
                                                rm -f /srv/\${REPOPATH}/release/\${rhel}/SRPMS/repodata/repomd.xml.asc
                                            fi
                                            gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${REPOPATH}/release/\${rhel}/SRPMS/repodata/repomd.xml
                                        done
                                    fi

                                    # binary processing
                                    pushd binary
                                    for rhel in \${RHVERS}; do
                                        mkdir -p /srv/\${REPOPATH}/release/\${rhel}/RPMS
                                        for arch in \$(ls -1 redhat/\${rhel}); do
                                            mkdir -p /srv/\${REPOPATH}/release/\${rhel}/RPMS/\${arch}
                                            cp -av redhat/\${rhel}/\${arch}/*.rpm /srv/\${REPOPATH}/release/\${rhel}/RPMS/\${arch}/
                                            createrepo --update /srv/\${REPOPATH}/release/\${rhel}/RPMS/\${arch}/
                                            if [ -f  /srv/\${REPOPATH}/release/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc ]; then
                                                rm -f  /srv/\${REPOPATH}/release/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc
                                            fi
                                            gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${REPOPATH}/release/\${rhel}/RPMS/\${arch}/repodata/repomd.xml
                                        done
                                    done
                                done
ENDSSH
                        """
                    }
                }
            }
        }
        stage('Push DEB client to public repository') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                set -e
                                #
                                #
                                REPOS='PERCONA TOOLS PMM2-CLIENT'
                                for REPOSITORY in \$REPOS; do
                                    if [[ \${REPOSITORY} = PERCONA ]]; then
                                        REPOPATH=/srv/repo-copy/percona/apt
                                        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/opt/puppetlabs/bin
                                    fi
                                    #
                                    if [[ \${REPOSITORY} = TOOLS ]]; then
                                        REPOPATH=/srv/repo-copy/tools/apt
                                        export PATH=/usr/local/reprepro5/bin:\${PATH}
                                    fi
                                    #
                                    if [[ \${REPOSITORY} = PMM2-CLIENT ]]; then
                                        REPOPATH=/srv/repo-copy/pmm2-client/apt
                                        export PATH=/usr/local/reprepro5/bin:\${PATH}
                                    fi
                                    echo reprepro binary is \$(which reprepro)
                                    pushd /srv/UPLOAD/${PATH_TO_CLIENT}/binary/debian
                                        echo Looking for Debian build directories...
                                        CODENAMES=\$(ls -1 | egrep -v 'cosmic|disco')
                                        echo Distributions are: \${CODENAMES}
                                    popd

                                    #######################################
                                    # source pushing, it's a bit specific #
                                    #######################################

                                    # pushing sources
                                    if  [ -d /srv/UPLOAD/${PATH_TO_CLIENT}/source/debian ]; then
                                        cd /srv/UPLOAD/${PATH_TO_CLIENT}/source/debian
                                        DSC=\$(find . -type f -name '*.dsc')
                                        for DSC_FILE in \${DSC}; do
                                            echo DSC file is \${DSC_FILE}
                                            for _codename in \${CODENAMES}; do
                                                echo ===>DSC \$DSC_FILE
                                                repopush --gpg-pass=${SIGN_PASSWORD} --package=\${DSC_FILE} --repo-path=\${REPOPATH} --component=main  --codename=\${_codename} --verbose || true
                                                if [ -f \${REPOPATH}/db/lockfile ]; then
                                                    sudo rm -vf \${REPOPATH}/db/lockfile
                                                fi
                                                sleep 1
                                            done
                                        done
                                    fi

                                    #######################################
                                    # binary pushing                      #
                                    #######################################
                                    cd /srv/UPLOAD/$PATH_TO_CLIENT/binary/debian

                                    for _codename in \${CODENAMES}; do
                                        pushd \${_codename}
                                            DEBS=\$(find . -type f -name '*.*deb' )
                                            for _deb in \${DEBS}; do
                                                repopush --gpg-pass=$SIGN_PASSWORD --package=\${_deb} --repo-path=\${REPOPATH} --component=main --codename=\${_codename} --verbose
                                            done
                                        popd
                                    done
                                    #
                                done
ENDSSH
                        """
                    }
                }
            }
        }


        stage('Get Docker RPMs') {
            agent {
                label 'min-centos-7-x64'
            }
            steps {
                installDocker()
                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
                sh "sg docker -c 'docker run ${SERVER_IMAGE} /usr/bin/rpm -qa' > rpms.list"
                stash includes: 'rpms.list', name: 'rpms'
            }
        }

        stage('Get repo RPMs') {
            steps {
                unstash 'rpms'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            ls /srv/repo-copy/pmm2-components/yum/testing/7/RPMS/x86_64 \
                            > repo.list
                        cat rpms.list \
                            | grep -v 'pmm2-client' \
                            | sed -e 's/[^A-Za-z0-9\\._+-]//g' \
                            | xargs -n 1 -I {} grep "^{}.rpm" repo.list \
                            | sort \
                            | tee copy.list
                    '''
                }
                stash includes: 'copy.list', name: 'copy'
                archiveArtifacts 'copy.list'
            }
        }
// Publish RPMs to repo.ci.percona.com
        stage('Copy RPMs to PMM repo') {
            steps {
                unstash 'copy'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        cat copy.list | ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            "cat - | xargs -I{} cp -v /srv/repo-copy/pmm2-components/yum/testing/7/RPMS/x86_64/{} /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/{}"
                    '''
                }
            }
        }
        stage('Createrepo') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com " \
                            createrepo --update /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/
                            if [ -f /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml.asc ]; then
                                rm -f /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml.asc
                            fi
                            export SIGN_PASSWORD=\${SIGN_PASSWORD}
                            gpg --detach-sign --armor --passphrase \${SIGN_PASSWORD} /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml
                        "
                    """
                    }
                }
            }
        }
// Publish RPMs to repo.percona.com
        stage('Publish RPMs') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com "
                            rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                                /srv/repo-copy/pmm2-components/yum/release \
                                10.10.9.209:/www/repo.percona.com/htdocs/pmm2-components/yum/
                            date +%s > /srv/repo-copy/version
                            rsync /srv/repo-copy/version 10.10.9.209:/www/repo.percona.com/htdocs/
                        "
                    """
                }
            }
        }
        stage('Set Tags') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                    unstash 'copy'
                    sh """
                        echo ${GITHUB_API_TOKEN} > GITHUB_API_TOKEN
                        echo ${VERSION} > VERSION
                    """
                    sh '''
                        set -ex
                        export VERSION=$(cat VERSION)
                        export TOP_VER=$(cat VERSION | cut -d. -f1)
                        export MID_VER=$(cat VERSION | cut -d. -f2)
                        export DOCKER_MID="$TOP_VER.$MID_VER"
                        declare -A repo=(
                            ["percona-dashboards"]="percona/grafana-dashboards"
                            ["pmm-server"]="percona/pmm-server"
                            ["percona-qan-api2"]="percona/qan-api2"
                            ["pmm-update"]="percona/pmm-update"
                            ["pmm"]="percona/pmm"
                        )

                        for package in "${!repo[@]}"; do
                            SHA=$(
                                grep "^$package-$VERSION-" copy.list \
                                    | perl -p -e 's/.*[.]\\d{10}[.]([0-9a-f]{7})[.]el7.*/$1/'
                            )
                            if [[ -n "$package" ]] && [[ -n "$SHA" ]]; then
                                rm -fr $package
                                mkdir $package
                                pushd $package >/dev/null
                                    git clone https://github.com/${repo["$package"]} ./
                                    git checkout $SHA
                                    FULL_SHA=$(git rev-parse HEAD)

                                    echo "$FULL_SHA"
                                    echo "$VERSION"
                                popd >/dev/null
                            fi
                        done
                    '''
                }
                stash includes: 'VERSION', name: 'version_file'
            }
        }
        stage('Set Docker Tag') {
            agent {
                label 'min-rhel-7-x64'
            }
            steps {
                unstash 'version_file'
                installDocker()
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            echo "${PASS}" | docker login -u "${USER}" --password-stdin
                        "
                    """
                }
                sh """
                    VERSION=\$(cat VERSION)
                    TOP_VER=\$(cat VERSION | cut -d. -f1)
                    MID_VER=\$(cat VERSION | cut -d. -f2)
                    DOCKER_MID="\$TOP_VER.\$MID_VER"
                    sg docker -c "
                        set -ex
                        # push pmm-server
                        docker pull \${SERVER_IMAGE}
                        docker tag \${SERVER_IMAGE} percona/pmm-server:latest
                        docker push percona/pmm-server:latest

                        docker tag \${SERVER_IMAGE} percona/pmm-server:\${TOP_VER}
                        docker tag \${SERVER_IMAGE} percona/pmm-server:\${DOCKER_MID}
                        docker tag \${SERVER_IMAGE} percona/pmm-server:\${VERSION}
                        docker push percona/pmm-server:\${TOP_VER}
                        docker push percona/pmm-server:\${DOCKER_MID}
                        docker push percona/pmm-server:\${VERSION}

                        docker tag \${SERVER_IMAGE} perconalab/pmm-server:\${TOP_VER}
                        docker tag \${SERVER_IMAGE} perconalab/pmm-server:\${DOCKER_MID}
                        docker tag \${SERVER_IMAGE} perconalab/pmm-server:\${VERSION}
                        docker push perconalab/pmm-server:\${TOP_VER}
                        docker push perconalab/pmm-server:\${DOCKER_MID}
                        docker push perconalab/pmm-server:\${VERSION}

                        docker save percona/pmm-server:\${VERSION} | xz > pmm-server-\${VERSION}.docker

                        # push pmm-client
                        docker pull \${CLIENT_IMAGE}
                        docker tag \${CLIENT_IMAGE} percona/pmm-client:latest
                        docker push percona/pmm-client:latest

                        docker tag \${CLIENT_IMAGE} percona/pmm-client:\${TOP_VER}
                        docker tag \${CLIENT_IMAGE} percona/pmm-client:\${DOCKER_MID}
                        docker tag \${CLIENT_IMAGE} percona/pmm-client:\${VERSION}
                        docker push percona/pmm-client:\${TOP_VER}
                        docker push percona/pmm-client:\${DOCKER_MID}
                        docker push percona/pmm-client:\${VERSION}

                        docker tag \${CLIENT_IMAGE} perconalab/pmm-client:\${TOP_VER}
                        docker tag \${CLIENT_IMAGE} perconalab/pmm-client:\${DOCKER_MID}
                        docker tag \${CLIENT_IMAGE} perconalab/pmm-client:\${VERSION}
                        docker push perconalab/pmm-client:\${TOP_VER}
                        docker push perconalab/pmm-client:\${DOCKER_MID}
                        docker push perconalab/pmm-client:\${VERSION}

                        docker save percona/pmm-client:\${VERSION} | xz > pmm-client-\${VERSION}.docker
                    "
                """
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -ex
                        aws s3 cp --only-show-errors pmm-server-\${VERSION}.docker s3://percona-vm/pmm-server-\${VERSION}.docker
                        aws s3 cp --only-show-errors pmm-client-\${VERSION}.docker s3://percona-vm/pmm-client-\${VERSION}.docker
                    """
                }
                deleteDir()
            }
        }
        stage('Publish Docker image') {
            agent {
                label 'virtualbox'
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -ex
                        aws s3 cp --only-show-errors s3://percona-vm/pmm-server-\${VERSION}.docker pmm-server-\${VERSION}.docker
                        aws s3 cp --only-show-errors s3://percona-vm/pmm-client-\${VERSION}.docker pmm-client-\${VERSION}.docker
                    """
                }
                sh """
                    ssh -i ~/.ssh/id_rsa_downloads -p 2222 jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com "mkdir -p /data/downloads/pmm2/\${VERSION}/docker" || true
                    sha256sum pmm-server-\${VERSION}.docker > pmm-server-\${VERSION}.sha256sum
                    sha256sum pmm-client-\${VERSION}.docker > pmm-client-\${VERSION}.sha256sum
                    scp -i ~/.ssh/id_rsa_downloads -P 2222 pmm-server-\${VERSION}.docker pmm-server-\${VERSION}.sha256sum jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/pmm2/\${VERSION}/docker/
                    scp -i ~/.ssh/id_rsa_downloads -P 2222 pmm-client-\${VERSION}.docker pmm-client-\${VERSION}.sha256sum jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/pmm2/\${VERSION}/docker/
                """
                deleteDir()
            }
        }
        stage('Publish OVF image') {
            agent {
                label 'virtualbox'
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        aws s3 cp --only-show-errors s3://percona-vm/PMM2-Server-\${VERSION}.ova pmm-server-\${VERSION}.ova
                    """
                }
                sh """
                    ssh -i ~/.ssh/id_rsa_downloads -p 2222 jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com "mkdir -p /data/downloads/pmm2/\${VERSION}/ova" || true
                    sha256sum pmm-server-\${VERSION}.ova > pmm-server-\${VERSION}.sha256sum
                    scp -i ~/.ssh/id_rsa_downloads -P 2222 pmm-server-\${VERSION}.ova pmm-server-\${VERSION}.sha256sum jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/pmm2/\${VERSION}/ova/
                """
                deleteDir()
            }
        }
        stage('Refresh website') {
            agent {
                label 'virtualbox'
            }
            steps {
                sh """
                    until curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory > /tmp/crawler; do
                        tail /tmp/crawler
                        sleep 10
                    done
                    tail /tmp/crawler
                """
            }
        }
        stage('Run post-release tests') {
            steps {
                build job: 'pmm2-release-tests', propagate: false, wait: false, parameters: [
                    string(name: 'VERSION', value: VERSION)
                ]
            }
        }
    }
    post {
        always {
            deleteDir()
        }
        success {
            unstash 'copy'
            script {
                def IMAGE = sh(returnStdout: true, script: "cat copy.list").trim()
            }
            slackSend botUser: true,
                        channel: '#pmm-dev',
                        color: '#00FF00',
                        message: "PMM ${VERSION} was released!"
            slackSend botUser: true,
                      channel: '#releases',
                      color: '#00FF00',
                      message: "PMM ${VERSION} was released!"
        }
        failure {
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}
