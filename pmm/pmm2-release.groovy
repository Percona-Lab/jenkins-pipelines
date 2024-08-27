library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'master'
    }

    environment {
        CLIENT_IMAGE     = "perconalab/pmm-client:${VERSION}-rc"
        SERVER_IMAGE     = "perconalab/pmm-server:${VERSION}-rc"
        PATH_TO_CLIENT   = "testing/pmm2-client-autobuilds/pmm2/${VERSION}/pmm-${VERSION}/${PATH_TO_CLIENT}"
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
                    if (!params.PATH_TO_CLIENT) {
                        error("ERROR: empty parameter PATH_TO_CLIENT")
                    }
                }
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                set -x
                                set -e

                                # We are only pushing to 'pmm2-client' repository
                                REPOPATH=repo-copy/pmm2-client/yum
                                cd /srv/UPLOAD/${PATH_TO_CLIENT}

                                # getting the list of RH systems
                                RHVERS=\$(ls -1 binary/redhat | grep -v 6 | grep -v 7)

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

                                # We are only pushing to 'pmm2-client' repository
                                REPOPATH=/srv/repo-copy/pmm2-client/apt
                                export PATH=/usr/local/reprepro5/bin:\${PATH}

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
ENDSSH
                        """
                    }
                }
            }
        }

        stage('Sync repos to production') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                        set -x
                        set -e

                        cd /srv/repo-copy
                        REPO=pmm2-client
                        date +%s > /srv/repo-copy/version
                        RSYNC_TRANSFER_OPTS=" -avt --delete --delete-excluded --delete-after --progress"
                        rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/\${REPO}/* 10.30.9.32:/www/repo.percona.com/htdocs/\${REPO}/
                        rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/version 10.30.9.32:/www/repo.percona.com/htdocs/
ENDSSH
                    """
                }
            }
        }

        stage('Upload client to percona.com') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                            set -e
                            cd  /srv/UPLOAD/${PATH_TO_CLIENT}/
                            #
                            PRODUCT=\$(echo ${PATH_TO_CLIENT} | awk -F '/' '{print \$3}')
                            RELEASE=\$(echo ${PATH_TO_CLIENT} | awk -F '/' '{print \$4}')
                            REVISION=\$(echo ${PATH_TO_CLIENT} | awk -F '/' '{print \$6}')
                            #
                            RELEASEDIR="/srv/UPLOAD/${PATH_TO_CLIENT}/.tmp/\${PRODUCT}/\${RELEASE}"
                            rm -fr /srv/UPLOAD/${PATH_TO_CLIENT}/.tmp
                            mkdir -p \${RELEASEDIR}
                            cp -av ./* \${RELEASEDIR}
                            #####################
                            # create RedHat tar bundles
                            #
                            cd \${RELEASEDIR}/binary/redhat
                            for _dist in *; do
                                cd \${_dist}
                                for _arch in *; do
                                    cd \${_arch}
                                    # don't create bundle if there's only 1 package inside directory
                                    NUM_PACKAGES=\$(find . -maxdepth 1 -type f -name '*.rpm'|wc -l)
                                    if [ \${NUM_PACKAGES} -gt 1 ]; then
                                        tar --owner=0 --group=0 -cf \${RELEASE}-r\${REVISION}-el\${_dist}-\${_arch}-bundle.tar  *.rpm
                                    fi
                                    cd ..
                                done
                                cd ..
                            done
                            #####################
                            # create Debian tar bundles
                            #
                            cd \${RELEASEDIR}/binary/debian
                            for _dist in *; do
                                cd \${_dist}
                                for _arch in *; do
                                    cd \${_arch}
                                    # don't create bundle if there's only 1 package inside directory
                                    NUM_PACKAGES=\$(find . -maxdepth 1 -type f -name '*.deb'|wc -l)
                                    if [ \${NUM_PACKAGES} -gt 1 ]; then
                                        tar --owner=0 --group=0 -cf \${RELEASE}-r\${REVISION}-\${_dist}-\${_arch}-bundle.tar *.deb
                                    fi
                                    cd ..
                                done
                                cd ..
                            done
                            #####################
                            # generate sha256sum for sources
                            #
                            cd \${RELEASEDIR}/source/tarball
                            if [ -d source_tarball ]; then
                                mv source_tarball/* ./
                                rm -rf source_tarball
                            fi
                            for _tar in *tar.*; do
                                sha256sum \${_tar} > \${_tar}.sha256sum
                            done
                            #####################
                            # generate sha256sum for binary tarballs
                            #
                            if [ -d \${RELEASEDIR}/binary/tarball ]; then 
                                cd \${RELEASEDIR}/binary/tarball
                                for _tar in *.tar.*; do
                                    # don't do it for symlinks (we have those in percona-agent)
                                    if [ ! -h \${_tar} ]; then
                                        sha256sum \${_tar} > \${_tar}.sha256sum
                                    fi
                                done
                            fi

                            #
                            cd \${RELEASEDIR}/..
                            #
                            ln -s \${RELEASE} LATEST
                            #
                            cd /srv/UPLOAD/${PATH_TO_CLIENT}/.tmp

                            rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="*yassl*" --progress \${PRODUCT} jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/

                            #
                            rm -fr /srv/UPLOAD/${PATH_TO_CLIENT}/.tmp
ENDSSH
                """
                }
            }
        }

        stage('Get Docker RPMs') {
            agent {
                label 'min-ol-9-x64'
            }
            steps {
                installDocker()
                slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: release started - ${BUILD_URL}"
                sh "sg docker -c 'docker run ${SERVER_IMAGE} /usr/bin/rpm -qa' > rpms.list"
                stash includes: 'rpms.list', name: 'rpms-stash'
            }
        }

        stage('Get repo RPMs') {
            steps {
                unstash 'rpms-stash'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            ls /srv/repo-copy/pmm2-components/yum/testing/9/RPMS/x86_64 > repo.list
                        cat rpms.list \
                            | grep -v 'pmm2-client' \
                            | sed -e 's/[^A-Za-z0-9\\._+-]//g' \
                            | xargs -n 1 -I {} grep "^{}.rpm" repo.list \
                            | sort \
                            | tee copy.list
                    '''
                }
                stash includes: 'copy.list', name: 'copy-stash'
                archiveArtifacts 'copy.list'
            }
        }
        // Publish RPMs to repo.ci.percona.com
        stage('Copy RPMs to PMM repo') {
            steps {
                unstash 'copy-stash'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        cat copy.list | ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            "cat - | xargs -I{} cp -v /srv/repo-copy/pmm2-components/yum/testing/9/RPMS/x86_64/{} /srv/repo-copy/pmm2-components/yum/release/9/RPMS/x86_64/{}"
                    '''
                }
            }
        }
        stage('Create repo') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com "
                            createrepo --update /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/
                            if [ -f /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml.asc ]; then
                                rm -f /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml.asc
                            fi

                            createrepo --update /srv/repo-copy/pmm2-components/yum/release/9/RPMS/x86_64/
                            if [ -f /srv/repo-copy/pmm2-components/yum/release/9/RPMS/x86_64/repodata/repomd.xml.asc ]; then
                                    rm -f /srv/repo-copy/pmm2-components/yum/release/9/RPMS/x86_64/repodata/repomd.xml.asc
                            fi

                            export SIGN_PASSWORD=\${SIGN_PASSWORD}
                            gpg --detach-sign --armor --passphrase \${SIGN_PASSWORD} /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml
                            gpg --detach-sign --armor --passphrase \${SIGN_PASSWORD} /srv/repo-copy/pmm2-components/yum/release/9/RPMS/x86_64/repodata/repomd.xml
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
                                10.30.9.32:/www/repo.percona.com/htdocs/pmm2-components/yum/
                            date +%s > /srv/repo-copy/version
                            rsync /srv/repo-copy/version 10.30.9.32:/www/repo.percona.com/htdocs/
                        "
                    """
                }
            }
        }

        stage('Set Docker Tag') {
            agent {
                label 'min-ol-9-x64'
            }
            steps {
                installDocker()
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            echo "${PASS}" | docker login -u "${USER}" --password-stdin
                        "
                    """
                }
                sh """
                    echo ${VERSION} > VERSION
                    VERSION=\$(cat VERSION)
                    TOP_VER=\$(cat VERSION | cut -d. -f1)
                    MID_VER=\$(cat VERSION | cut -d. -f2)
                    DOCKER_MID="\$TOP_VER.\$MID_VER"
                    sg docker -c "
                        set -ex
                        # push pmm-server el9
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
                    sh '''
                        set -ex
                        aws s3 cp --only-show-errors pmm-server-${VERSION}.docker s3://percona-vm/pmm-server-${VERSION}.docker
                        aws s3 cp --only-show-errors pmm-client-${VERSION}.docker s3://percona-vm/pmm-client-${VERSION}.docker
                    '''
                }
                deleteDir()
            }
        }
        stage('Publish Docker image') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -ex
                        aws s3 cp --only-show-errors s3://percona-vm/pmm-server-${VERSION}.docker pmm-server-${VERSION}.docker
                        aws s3 cp --only-show-errors s3://percona-vm/pmm-client-${VERSION}.docker pmm-client-${VERSION}.docker
                    '''
                }
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        sha256sum pmm-server-${VERSION}.docker | tee pmm-server-${VERSION}.sha256sum
                        sha256sum pmm-client-${VERSION}.docker | tee pmm-client-${VERSION}.sha256sum
                        export UPLOAD_HOST=$(dig +short downloads-rsync-endpoint.int.percona.com @10.30.6.240 @10.30.6.241 | tail -1)
                        ssh -p 2222 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@$UPLOAD_HOST "mkdir -p /data/downloads/pmm2/${VERSION}/docker"
                        scp -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} pmm-server-${VERSION}.docker pmm-server-${VERSION}.sha256sum ${USER}@$UPLOAD_HOST:/data/downloads/pmm2/${VERSION}/docker/
                        scp -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} pmm-client-${VERSION}.docker pmm-client-${VERSION}.sha256sum ${USER}@$UPLOAD_HOST:/data/downloads/pmm2/${VERSION}/docker/
                        ssh -p 2222 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@$UPLOAD_HOST "ls -l /data/downloads/pmm2/${VERSION}/docker"
                    '''
                }
                deleteDir()
            }
        }
        stage('Publish OVF image') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        aws s3 cp --only-show-errors s3://percona-vm/PMM2-Server-${VERSION}.ova pmm-server-${VERSION}.ova
                    '''
                }
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        sha256sum pmm-server-${VERSION}.ova | tee pmm-server-${VERSION}.sha256sum
                        export UPLOAD_HOST=$(dig +short downloads-rsync-endpoint.int.percona.com @10.30.6.240 @10.30.6.241 | tail -1)
                        ssh -p 2222 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@$UPLOAD_HOST "mkdir -p /data/downloads/pmm2/${VERSION}/ova"
                        scp -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} pmm-server-${VERSION}.ova pmm-server-${VERSION}.sha256sum ${USER}@$UPLOAD_HOST:/data/downloads/pmm2/${VERSION}/ova/
                    '''
                }
                deleteDir()
            }
        }
        stage('Refresh website') {
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
        stage('Set git release tags') {
            steps {
                deleteDir()
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        # This step must never cause the pipeline to fail, so that we can create tags outside of it
                        set +e
                        curl -o create-tags https://raw.githubusercontent.com/percona/pmm/pmm-${VERSION}/build/scripts/create-tags || :
                        if [ -f create-tags ]; then
                            chmod +x create-tags
                            bash -E "$(pwd)/create-tags"
                        fi
                    '''
                }
            }
        }
        stage('Run post-release tests GH Actions') {
            steps{
                script{
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        sh '''
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/percona/pmm-qa/actions/workflows/package-test-single.yml/dispatches" \
                                -d '{"ref":"main","inputs":{"playbook": "pmm2-client", "package": "pmm2-client", "repository": "release", "metrics_mode": "auto"}}'
                        '''
                        sh '''
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/percona/pmm-qa/actions/workflows/e2e-upgrade-tests-matrix-full.yml/dispatches" \
                                -d '{"ref":"main","inputs":{"pmm_ui_tests_branch": "main", "pmm_qa_branch": "main", "repository": "release", "versions_range": 1}}'
                        '''
                    }
                }
            }
        }
        stage('Scan Image for Vulnerabilities') {
            steps {
                script {
                    imageScan = build job: 'pmm2-image-scanning', propagate: false, parameters: [
                        string(name: 'IMAGE', value: "perconalab/pmm-server"),
                        string(name: 'TAG', value: "${VERSION}")
                    ]

                    env.SCAN_REPORT_URL = ""
                    if (imageScan.result == 'SUCCESS') {
                        copyArtifacts filter: 'report.html', projectName: 'pmm2-image-scanning'
                        sh 'mv report.html report-${VERSION}.html'
                        archiveArtifacts "report-${VERSION}.html"
                        env.SCAN_REPORT_URL = "CVE Scan Report: ${BUILD_URL}artifact/report-${VERSION}.html"

                        copyArtifacts filter: 'evaluations/**/evaluation_*.json', projectName: 'pmm2-image-scanning'
                        sh 'mv evaluations/*/*/*/evaluation_*.json ./report-${VERSION}.json'
                        archiveArtifacts "report-${VERSION}.json"
                    }
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
        success {
            slackSend botUser: true, channel: '#pmm-dev', color: '#00FF00', message: "PMM ${VERSION} was released!\nBuild URL: ${BUILD_URL}\n${env.SCAN_REPORT_URL}"
            slackSend botUser: true, channel: '#releases', color: '#00FF00', message: "PMM ${VERSION} was released!\nBuild URL: ${BUILD_URL}\n${env.SCAN_REPORT_URL}"
        }
        failure {
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: release failed - ${BUILD_URL}"
        }
    }
}
