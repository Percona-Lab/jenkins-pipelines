library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'jenkins'
    }
    parameters {
        string(
            defaultValue: '',
            description: 'PATH_TO_BUILD must be in form $DESTINATION/**release**/$revision',
            name: 'PATH_TO_BUILD')
        string(
            defaultValue: 'PERCONA',
            description: 'separate repository to push to. Please use CAPS letters.',
            name: 'REPOSITORY')
        booleanParam(name: 'REVERSE', defaultValue: false, description: 'please use reverse sync if you want to fix repo copy on signing server. it will be overwritten with known working copy from production')
        booleanParam(name: 'REMOVE_BEFORE_PUSH', defaultValue: false, description: 'check to remove sources and binary version if equals pushing')
        booleanParam(name: 'REMOVE_LOCKFILE', defaultValue: false, description: 'remove lockfile after unsuccessful push')
        choice(
            choices: 'TESTING\nRELEASE\nEXPERIMENTAL\nLABORATORY',
            description: 'repo component to push to',
            name: 'COMPONENT')
        choice(
            choices: 'NO\nYES',
            description: 'show telemetry agent packages listing for corresponding product',
            name: 'COPY_TELEMETRY')
        choice(
            choices: 'NO\nYES',
            description: 'PRO build',
            name: 'PROBUILD')
        choice(
            choices: 'YES\nNO',
            description: 'Push RHEL 10 packages',
            name: 'PUSHRHEL10'
        )
        choice(
            choices: 'NO\nYES',
            description: 'Enable if focal packages have to be pushed',
            name: 'PUSHFOCAL')
        booleanParam(name: 'SKIP_RPM_PUSH', defaultValue: false, description: 'Skip push to RPM repository')
        booleanParam(name: 'SKIP_DEB_PUSH', defaultValue: false, description: 'Skip push to DEB repository')
        booleanParam(name: 'SKIP_REPO_SYNC', defaultValue: false, description: 'Skip sync repos to production')
        booleanParam(name: 'SKIP_PACKAGES_SYNC', defaultValue: false, description: 'Skip sync packages to production download')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Push to RPM repository') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                        sh """ 
                            if [ ${SKIP_RPM_PUSH} = false ]; then
                                if [ x"${PATH_TO_BUILD}" = x ]; then
                                    echo "Empty path!"
                                    exit 1
                                fi
                                ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                    set -o errexit
                                    set -o xtrace
                                    echo /srv/UPLOAD/${PATH_TO_BUILD}
                                    cd /srv/UPLOAD/${PATH_TO_BUILD}
                                    if [ ${PROBUILD} = YES ]; then
                                        PRO_FOLDER="private/"
                                    else
                                        PRO_FOLDER=""
                                    fi
                                    ALGO=""
                                    REPOCOMP=\$(echo "${COMPONENT}" | tr '[:upper:]' '[:lower:]')
                                    LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
                                    NoDBRepos=("PSMDB" "PDMDB")
                                    for repo in \${NoDBRepos[*]}; do
                                        if [[ "${REPOSITORY}" =~ "\${repo}".* ]]; then
                                            export ALGO="--no-database"
                                        fi
                                    done
                                    if [[ ! "${REPOSITORY}" == "PERCONA" ]]; then
                                        export PATH="/usr/local/reprepro5/bin:\${PATH}"
                                    fi
                                    if [[ "${REPOSITORY}" == "DEVELOPMENT" ]]; then
                                       export REPOPATH="yum-repo"
                                    else
                                       export REPOPATH="repo-copy/"\${PRO_FOLDER}\${LCREPOSITORY}"/yum"
                                    fi
                                    echo \${REPOPATH}
                                    if [ ${PUSHRHEL10} = YES ]; then
                                        RHVERS=\$(ls -1 binary/redhat | grep -v 6)
                                    else
                                        RHVERS=\$(ls -1 binary/redhat | grep -v 10 | grep -v 6)
                                    fi
                                    # -------------------------------------> source processing
                                    if [[ -d source/redhat ]]; then
                                        SRCRPM=\$(find source/redhat -name '*.src.rpm')
                                        for rhel in \${RHVERS}; do
                                            mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
                                            cp -v \${SRCRPM} /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
                                            createrepo \${ALGO:-} --update /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
                                            if [[ -f /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml.asc ]]; then
                                                rm -f /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml.asc
                                            fi
                                            gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml
                                        done
                                    fi
                                    # -------------------------------------> binary processing
                                    pushd binary
                                    for rhel in \${RHVERS}; do
                                        mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS
                                        for arch in \$(ls -1 redhat/\${rhel}); do
                                            mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}
                                            cp -av redhat/\${rhel}/\${arch}/*.rpm /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
                                            createrepo  \${ALGO:-} --update /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
                                            if [ -f  /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc ]; then
                                                rm -f  /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc
                                            fi
                                            gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml
                                        done
                                    done
                                    date +%s > /srv/repo-copy/\${PRO_FOLDER}version
ENDSSH
                           else
                              echo "The step is skipped."
                           fi
                        """ 
                    }
                }
            }
        }
        stage('Push to DEB repository') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                        sh """
                            if [ ${SKIP_DEB_PUSH} = false ]; then
                                if [ x"${PATH_TO_BUILD}" = x ]; then
                                    echo "Empty path!"
                                    exit 1
                                fi
                                ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                    set -o errexit
                                    set -o xtrace
                                    cd /srv/UPLOAD/${PATH_TO_BUILD}
                                    if [ ${PROBUILD} = YES ]; then
                                        PRO_FOLDER="private/"
                                    else
                                        PRO_FOLDER=""
                                    fi
                                    REPOPUSH_ARGS=""
                                    REPOCOMP=\$(echo "${COMPONENT}" | tr '[:upper:]' '[:lower:]')
                                    LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
                                    if [ ${REMOVE_BEFORE_PUSH} = true ]; then
                                        if [[ ! ${COMPONENT} == RELEASE ]]; then
                                            REPOPUSH_ARGS=" --remove-package "
                                        else
                                            echo "it is not allowed to remove packages from RELEASE repository"
                                            exit 1
                                        fi
                                    fi
                                    if [[ ! "${REPOSITORY}" == "PERCONA" ]]; then
                                        export PATH="/usr/local/reprepro5/bin:\${PATH}"
                                    fi
                                    if [[ "${REPOSITORY}" == "DEVELOPMENT" ]]; then
                                       export REPOPATH="/srv/apt-repo"
                                    else
                                       export REPOPATH="/srv/repo-copy/"\${PRO_FOLDER}\${LCREPOSITORY}"/apt"
                                    fi
                                    set -e
                                    echo "<*> path to repo is "\${REPOPATH}
                                    echo "<*> reprepro binary is "\$(which reprepro)
                                    cd /srv/UPLOAD/${PATH_TO_BUILD}/binary/debian
                                    if [ ${PUSHFOCAL} = YES ]; then
                                        CODENAMES=\$(ls -1)
                                    else
                                        CODENAMES=\$(ls -1 | grep -v focal)
                                    fi
                                    echo "<*> Distributions are: "\${CODENAMES}
                                    # -------------------------------------> source pushing, it's a bit specific
                                    if [[ ${REMOVE_LOCKFILE} = true ]]; then
                                        echo "<*> Removing lock file as requested..."
                                        rm -vf  \${REPOPATH}/db/lockfile
                                    fi
                                    if [[ ${COMPONENT} == RELEASE ]]; then
                                        export REPOCOMP=main
                                        if [ -d /srv/UPLOAD/${PATH_TO_BUILD}/source/debian ]; then
                                            cd /srv/UPLOAD/${PATH_TO_BUILD}/source/debian
                                            DSC=\$(find . -type f -name '*.dsc')
                                            for DSC_FILE in \${DSC}; do
                                                echo "<*> DSC file is "\${DSC_FILE}
                                                for _codename in \${CODENAMES}; do
                                                    echo "<*> CODENAME: "\${_codename}
                                                    repopush --gpg-pass=${SIGN_PASSWORD} --package=\${DSC_FILE} --repo-path=\${REPOPATH} --component=\${REPOCOMP}  --codename=\${_codename} --verbose \${REPOPUSH_ARGS} || true
                                                    sleep 5
                                                done
                                            done
                                        fi
                                    fi
                                    # -------------------------------------> binary pushing
                                    cd /srv/UPLOAD/${PATH_TO_BUILD}/binary/debian
                                    for _codename in \${CODENAMES}; do
                                        echo "<*> CODENAME: "\${_codename}
                                        pushd \${_codename}
                                        DEBS=\$(find . -type f -name '*.*deb' )
                                        for _deb in \${DEBS}; do
                                            repopush --gpg-pass=${SIGN_PASSWORD} --package=\${_deb} --repo-path=\${REPOPATH} --component=\${REPOCOMP} --codename=\${_codename} --verbose \${REPOPUSH_ARGS}
                                        done
                                        popd
                                    done
                                    date +%s > /srv/repo-copy/\${PRO_FOLDER}version
ENDSSH
                            else
                                echo "The step is skipped."
                            fi
                        """
                    }
                }
            }
        }
        stage('Sync packages to production download') {
            steps {
               withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                   sh """
                       if [ ${SKIP_PACKAGES_SYNC} = false ]; then
                           ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                               set -o errexit
                               set -o xtrace
                               if [ ${COMPONENT} = RELEASE ]; then
                                   cd /srv/UPLOAD/${PATH_TO_BUILD}/
                                   PRODUCT=\$(echo ${PATH_TO_BUILD} | awk -F '/' '{print \$3}')
                                   RELEASE=\$(echo ${PATH_TO_BUILD} | awk -F '/' '{print \$4}')
                                   REVISION=\$(echo ${PATH_TO_BUILD} | awk -F '/' '{print \$6}')
                                   RELEASEDIR="/srv/UPLOAD/${PATH_TO_BUILD}/.tmp/\${PRODUCT}/\${RELEASE}"
                                   LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
                                   rm -fr /srv/UPLOAD/${PATH_TO_BUILD}/.tmp
                                   mkdir -p \${RELEASEDIR}
                                   cp -av ./* \${RELEASEDIR}
                                   # -------------------------------------> create RedHat tar bundles
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
                                   # -------------------------------------> create Debian tar bundles
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
                                   # -------------------------------------> generate sha256sum for sources
                                   cd \${RELEASEDIR}/source/tarball
                                   if [ -d source_tarball ]; then
                                       mv source_tarball/* ./
                                       rm -rf source_tarball
                                   fi
                                   for _tar in *tar.*; do
                                       # don't do it for symlinks (we have those in percona-agent)
                                       if [ ! -h \${_tar} ]; then
                                           sha256sum \${_tar} > \${_tar}.sha256sum
                                       fi
                                   done
                                   # -------------------------------------> generate sha256sum for binary tarballs
                                   if [ -d \${RELEASEDIR}/binary/tarball ]; then 
                                       cd \${RELEASEDIR}/binary/tarball
                                       for _tar in *.tar.*; do
                                          # don't do it for symlinks (we have those in percona-agent)
                                          if [ ! -h \${_tar} ]; then
                                              sha256sum \${_tar} > \${_tar}.sha256sum
                                          fi
                                       done
                                   fi
                                   # -------------------------------------> sync packages
                                   cd \${RELEASEDIR}/..
                                   ln -s \${RELEASE} LATEST
                                   cd /srv/UPLOAD/${PATH_TO_BUILD}/.tmp
                                   if [ ${PROBUILD} = YES ]; then
                                       mkdir -p /srv/repo-copy/private/\${LCREPOSITORY}/tarballs/\${RELEASE}
                                       cp \${RELEASEDIR}/binary/tarball/* /srv/repo-copy/private/\${LCREPOSITORY}/tarballs/\${RELEASE}/
                                   else
                                       if [ ${COPY_TELEMETRY} = YES ]; then
                                           touch \${RELEASEDIR}/binary/telemetry-enhanced.json
                                       fi
                                       rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="*yassl*" --progress \${PRODUCT} jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/
                                   fi
                                   rm -fr /srv/UPLOAD/\${PATH_TO_BUILD}/.tmp
                               else
                                   if [ ${PROBUILD} = YES ]; then
                                       echo "Tarballs are uploaded by a build job into qa-test folder."
                                       #mkdir -p /srv/repo-copy/private/qa-test/\${LCREPOSITORY}/\${RELEASE}
                                       #cp \${RELEASEDIR}/binary/tarball/* /srv/repo-copy/private/qa-test/\${LCREPOSITORY}/\${RELEASE}/
                                   fi
                               fi
ENDSSH
                       else
                           echo "The step is skipped."
                       fi
                   """
                }
            }
        }
        stage('Sync repos to production') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        if [ ${SKIP_REPO_SYNC} = false ]; then
                            ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                                set -o errexit
                                set -o xtrace
                                if [ ${PROBUILD} = YES ]; then
                                    PRO_FOLDER="private/"
                                else
                                    PRO_FOLDER=""
                                fi
                                LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
                                cd /srv/repo-copy/\${PRO_FOLDER}
                                RSYNC_TRANSFER_OPTS=" -avt --delete --delete-excluded --delete-after --progress"
                                if [[ ${REVERSE} = true ]]; then
                                    rsync \${RSYNC_TRANSFER_OPTS} 10.30.9.32:/www/repo.percona.com/htdocs/\${PRO_FOLDER}\${LCREPOSITORY}/* /srv/repo-copy/\${PRO_FOLDER}\${LCREPOSITORY}/
                                else
                                    rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/\${PRO_FOLDER}\${LCREPOSITORY}/* 10.30.9.32:/www/repo.percona.com/htdocs/\${PRO_FOLDER}\${LCREPOSITORY}/
                                    rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/\${PRO_FOLDER}version 10.30.9.32:/www/repo.percona.com/htdocs/\${PRO_FOLDER}
                                fi
                                if [ ${PROBUILD} = YES ]; then
                                    rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/private/qa-test/* 10.30.9.32:/www/repo.percona.com/htdocs/private/qa-test/
                                fi
ENDSSH
                        else
                            echo "The step is skipped."
                        fi
                    """
                }
            }
        }
        stage('Refresh downloads area') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """ 
                       ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
                           if [ ${COMPONENT} = RELEASE ]; then
                               curl -k https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
                           fi
ENDSSH
                    """
                }
            }
        }
        stage('Cleanup') {
            steps {
                deleteDir()
            }
        }
    }
    post {
        always {
            script {
                currentBuild.description = "Repo: ${REPOSITORY}/${COMPONENT}, path to packages: ${PATH_TO_BUILD}"
            }
        }
    }
}
