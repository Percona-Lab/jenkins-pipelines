library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'jenkins'
    }
    parameters {
        choice(
            choices: [ 'Hetzner', 'AWS' ],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'ppg-16.13',
            description: 'PPG repository to promote packages from (testing -> release). Use the versioned repo name, e.g. ppg-16.13, ppg-17.4, ppg-18.0. Packages will also be copied to the corresponding major repo (e.g. ppg-16).',
            name: 'REPOSITORY')
        booleanParam(name: 'REMOVE_LOCKFILE', defaultValue: false, description: 'remove lockfile after unsuccessful push')
        booleanParam(name: 'SKIP_RPM_PUSH', defaultValue: false, description: 'Skip push to RPM repository')
        booleanParam(name: 'SKIP_DEB_PUSH', defaultValue: false, description: 'Skip push to DEB repository')
        booleanParam(name: 'SKIP_PACKAGES_SYNC', defaultValue: false, description: 'Skip sync packages to production download')
        booleanParam(name: 'SKIP_REPO_SYNC', defaultValue: false, description: 'Skip sync repos to production')
        booleanParam(name: 'SKIP_UPDATE_INDEX', defaultValue: false, description: 'Skip update index')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Notify start') {
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting promotion of ${REPOSITORY} from testing to release - [${BUILD_URL}]")
            }
        }
        stage('Push to RPM repository') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                        sh """
                            if [ ${SKIP_RPM_PUSH} = false ]; then
                                ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
set -o errexit
set -o xtrace

REPOCOMP=release
LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
export PATH="/usr/local/reprepro5/bin:\${PATH}"
export REPOPATH="repo-copy/\${LCREPOSITORY}/yum"
echo \${REPOPATH}

# -------------------------------------> PPG versioned release: copy from testing to release
TESTING_PATH="/srv/\${REPOPATH}/testing"
RHVERS=\$(ls -1 \${TESTING_PATH} | grep -E '^[0-9]+\$')

# -------------------------------------> source processing from testing
for rhel in \${RHVERS}; do
    if [ -d \${TESTING_PATH}/\${rhel}/SRPMS ]; then
        mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
        cp -av \${TESTING_PATH}/\${rhel}/SRPMS/*.rpm /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/
        createrepo --update /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
        if [[ -f /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml.asc ]]; then
            rm -f /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml.asc
        fi
        gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml
    fi
done

# -------------------------------------> binary processing from testing
for rhel in \${RHVERS}; do
    if [ -d \${TESTING_PATH}/\${rhel}/RPMS ]; then
        mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS
        for arch in \$(ls -1 \${TESTING_PATH}/\${rhel}/RPMS); do
            mkdir -p /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}
            RPM_COUNT=\$(find \${TESTING_PATH}/\${rhel}/RPMS/\${arch}/ -maxdepth 1 -name '*.rpm' 2>/dev/null | wc -l)
            if [ \${RPM_COUNT} -gt 0 ]; then
                cp -av \${TESTING_PATH}/\${rhel}/RPMS/\${arch}/*.rpm /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
                createrepo --update /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
                if [ -f /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc ]; then
                    rm -f /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc
                fi
                gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml
            else
                rm -rf /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata
                cp -av \${TESTING_PATH}/\${rhel}/RPMS/\${arch}/repodata /srv/\${REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
            fi
        done
    fi
done

# -------------------------------------> also copy packages to major version repo (e.g. ppg-18)
MAJOR_REPO=\$(echo \${LCREPOSITORY} | cut -d. -f1)
MAJOR_REPOPATH="repo-copy/\${MAJOR_REPO}/yum"
echo "Copying packages from \${LCREPOSITORY} testing to \${MAJOR_REPO} release"

# -------------------------------------> source processing for major repo
for rhel in \${RHVERS}; do
    if [ -d \${TESTING_PATH}/\${rhel}/SRPMS ]; then
        mkdir -p /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
        cp -av \${TESTING_PATH}/\${rhel}/SRPMS/*.rpm /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/
        createrepo --update /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS
        if [[ -f /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml.asc ]]; then
            rm -f /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml.asc
        fi
        gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/SRPMS/repodata/repomd.xml
    fi
done

# -------------------------------------> binary processing for major repo
for rhel in \${RHVERS}; do
    if [ -d \${TESTING_PATH}/\${rhel}/RPMS ]; then
        mkdir -p /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS
        for arch in \$(ls -1 \${TESTING_PATH}/\${rhel}/RPMS); do
            mkdir -p /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}
            RPM_COUNT=\$(find \${TESTING_PATH}/\${rhel}/RPMS/\${arch}/ -maxdepth 1 -name '*.rpm' 2>/dev/null | wc -l)
            if [ \${RPM_COUNT} -gt 0 ]; then
                cp -av \${TESTING_PATH}/\${rhel}/RPMS/\${arch}/*.rpm /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
                createrepo --update /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
                if [ -f /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc ]; then
                    rm -f /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml.asc
                fi
                gpg --detach-sign --armor --passphrase $SIGN_PASSWORD /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata/repomd.xml
            else
                rm -rf /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/repodata
                cp -av \${TESTING_PATH}/\${rhel}/RPMS/\${arch}/repodata /srv/\${MAJOR_REPOPATH}/\${REPOCOMP}/\${rhel}/RPMS/\${arch}/
            fi
        done
    fi
done

date +%s > /srv/repo-copy/version
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
                                ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
set -o errexit
set -o xtrace

LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
export PATH="/usr/local/reprepro5/bin:\${PATH}"
export REPOPATH="/srv/repo-copy/\${LCREPOSITORY}/apt"
echo "<*> path to repo is "\${REPOPATH}
echo "<*> reprepro binary is "\$(which reprepro)

if [[ ${REMOVE_LOCKFILE} = true ]]; then
    echo "<*> Removing lock file as requested..."
    rm -vf \${REPOPATH}/db/lockfile
fi

# -------------------------------------> PPG versioned release: copy from testing to main
export REPOCOMP=main
CODENAMES=\$(ls -1 \${REPOPATH}/dists/)
echo "<*> Distributions are: "\${CODENAMES}

# -------------------------------------> source pushing from testing pool
DSC=\$(find \${REPOPATH}/pool/testing/ -type f -name '*.dsc' 2>/dev/null || true)
if [ -n "\${DSC}" ]; then
    for DSC_FILE in \${DSC}; do
        echo "<*> DSC file is "\${DSC_FILE}
        for _codename in \${CODENAMES}; do
            echo "<*> CODENAME: "\${_codename}
            repopush --gpg-pass=${SIGN_PASSWORD} --package=\${DSC_FILE} --repo-path=\${REPOPATH} --component=\${REPOCOMP} --codename=\${_codename} --verbose || true
            sleep 5
        done
    done
fi

# -------------------------------------> binary pushing from testing pool
for _codename in \${CODENAMES}; do
    echo "<*> CODENAME: "\${_codename}
    DEBS=\$(find \${REPOPATH}/pool/testing/ -type f -name "*\${_codename}*.*deb")
    for _deb in \${DEBS}; do
        repopush --gpg-pass=${SIGN_PASSWORD} --package=\${_deb} --repo-path=\${REPOPATH} --component=\${REPOCOMP} --codename=\${_codename} --verbose
    done
done

# -------------------------------------> also push to major version repo (e.g. ppg-18)
MAJOR_REPO=\$(echo \${LCREPOSITORY} | cut -d. -f1)
MAJOR_REPOPATH="/srv/repo-copy/\${MAJOR_REPO}/apt"
echo "<*> Copying packages to major repo: \${MAJOR_REPOPATH}"

if [[ ${REMOVE_LOCKFILE} = true ]]; then
    rm -vf \${MAJOR_REPOPATH}/db/lockfile
fi

MAJOR_CODENAMES=\$(ls -1 \${MAJOR_REPOPATH}/dists/)

# -------------------------------------> source pushing to major repo
# Skip the .dsc push when the source package is already in the major repo pool
if [ -n "\${DSC}" ]; then
    for DSC_FILE in \${DSC}; do
        echo "<*> DSC file is "\${DSC_FILE}
        SRCNAME=\$(grep -m1 "^Source:" \${DSC_FILE}  | sed "s/^Source: *//")
        FULLVER=\$(grep -m1 "^Version:" \${DSC_FILE} | sed "s/^Version: *//")
        UPVER="\${FULLVER%-*}"
        EXISTING_DSC=\$(find \${MAJOR_REPOPATH}/pool/main -type f -name "\$(basename \${DSC_FILE})" 2>/dev/null | head -1)
        if [ -n "\${EXISTING_DSC}" ]; then
            echo "<*> Skipping \${SRCNAME} \${FULLVER} source push to major repo: dsc already in pool (\${EXISTING_DSC})"
            continue
        fi
        EXISTING_ORIG=\$(find \${MAJOR_REPOPATH}/pool/main -type f -name "\${SRCNAME}_\${UPVER}.orig.tar.gz" 2>/dev/null | head -1)
        if [ -n "\${EXISTING_ORIG}" ]; then
            echo "<*> Skipping \${SRCNAME} \${UPVER} source push to major repo: orig tarball already in pool (\${EXISTING_ORIG})"
            continue
        fi
        echo "<*> Pushing \${SRCNAME} \${FULLVER} source to major repo (not yet in pool)"
        for _codename in \${MAJOR_CODENAMES}; do
            echo "<*> CODENAME: "\${_codename}
            repopush --gpg-pass=${SIGN_PASSWORD} --package=\${DSC_FILE} --repo-path=\${MAJOR_REPOPATH} --component=main --codename=\${_codename} --verbose
            sleep 5
        done
    done
fi

# -------------------------------------> binary pushing to major repo
for _codename in \${MAJOR_CODENAMES}; do
    echo "<*> CODENAME: "\${_codename}
    DEBS=\$(find \${REPOPATH}/pool/testing/ -type f -name "*\${_codename}*.*deb")
    for _deb in \${DEBS}; do
        repopush --gpg-pass=${SIGN_PASSWORD} --package=\${_deb} --repo-path=\${MAJOR_REPOPATH} --component=main --codename=\${_codename} --verbose
    done
done

date +%s > /srv/repo-copy/version
ENDSSH
                            else
                                echo "The step is skipped."
                            fi
                        """
                    }
                }
            }
        }
        stage('Sync Tarballs/SBOMs to production download') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        if [ ${SKIP_PACKAGES_SYNC} = false ]; then
                            ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
set -o errexit
set -o xtrace

LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
PPG_VERSION=\$(echo \${LCREPOSITORY} | sed 's/^ppg-//')
PG_MAJOR=\$(echo \${PPG_VERSION} | cut -d. -f1)

# -------------------------------------> release tarballs to downloads server
TARBALL_PRODUCT="Percona-PostgreSQL-Tarballs"
TARBALL_BASE="/srv/UPLOAD/testing/BUILDS/\${TARBALL_PRODUCT}/\${TARBALL_PRODUCT}-\${PPG_VERSION}"
TARBALL_SRC="\${TARBALL_BASE}/latest/binary/tarball"

if [ -d \${TARBALL_SRC} ]; then
    ssh -p 2222 jenkins-deploy.jenkins-deploy.web.r.int.percona.com "cd /data/downloads/ && mkdir -p postgresql-distribution-\${PG_MAJOR}/\${PPG_VERSION}/binary/tarball"
    cd \${TARBALL_SRC}
    rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="*yassl*" --progress *tar.* jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/postgresql-distribution-\${PG_MAJOR}/\${PPG_VERSION}/binary/tarball/

    # -------------------------------------> release SBOMs to downloads server
    SBOM_BASE="/srv/UPLOAD/testing/BUILDS/PG_SBOM/\${PPG_VERSION}"
    if [ -d \${SBOM_BASE} ]; then
        SBOM_LATEST_TS=\$(ls -t \${SBOM_BASE} | head -1)
        SBOM_SRC="\${SBOM_BASE}/\${SBOM_LATEST_TS}/json"
        if [ -d \${SBOM_SRC} ]; then
            cd \${SBOM_SRC}
            rsync -avt -e "ssh -p 2222" --bwlimit=50000 --exclude="yassl" --progress *json jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/postgresql-distribution-\${PG_MAJOR}/\${PPG_VERSION}/binary/tarball/
        fi
    else
        echo "SBOM directory \${SBOM_BASE} not found, skipping SBOM release"
    fi
else
    echo "Tarball latest directory \${TARBALL_SRC} not found, skipping tarball release"
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

LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
MAJOR_REPO=\$(echo \${LCREPOSITORY} | cut -d. -f1)
cd /srv/repo-copy/
RSYNC_TRANSFER_OPTS=" -avt --delete --delete-excluded --delete-after --progress"

rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/\${LCREPOSITORY}/* 10.30.9.32:/www/repo.percona.com/htdocs/\${LCREPOSITORY}/
rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/\${MAJOR_REPO}/* 10.30.9.32:/www/repo.percona.com/htdocs/\${MAJOR_REPO}/
rsync \${RSYNC_TRANSFER_OPTS} --exclude=*.sh --exclude=*.bak /srv/repo-copy/version 10.30.9.32:/www/repo.percona.com/htdocs/
ENDSSH
                        else
                            echo "The step is skipped."
                        fi
                    """
                }
            }
        }
        stage('Update index') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        if [ ${SKIP_UPDATE_INDEX} = false ]; then
                            ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${KEY_PATH} ${USER}@repo.ci.percona.com << 'ENDSSH'
set -o errexit
set -o xtrace

LCREPOSITORY=\$(echo "${REPOSITORY}" | tr '[:upper:]' '[:lower:]')
cd /srv/repo-copy/

if ! grep -q "\${LCREPOSITORY}/" index.html; then
    REPO_PREFIX=\$(echo "\${LCREPOSITORY}" | cut -d. -f1)
    LAST_MATCH=\$(grep -n "href=\\"\${REPO_PREFIX}[./]" index.html | tail -1 | cut -d: -f1)
    if [ -n "\${LAST_MATCH}" ]; then
        INSERT_LINE=\$(tail -n +"\${LAST_MATCH}" index.html | grep -n '</tr>' | head -1 | cut -d: -f1)
        INSERT_LINE=\$((\${LAST_MATCH} + \${INSERT_LINE} - 1))
        sed -i "\${INSERT_LINE}a\\            <tr>\\n              <td><a href=\\"\${LCREPOSITORY}/\\">\${LCREPOSITORY}/<\\/a><\\/td>\\n            <\\/tr>" index.html
    else
        sed -i "/<\\/table>/i\\            <tr>\\n              <td><a href=\\"\${LCREPOSITORY}/\\">\${LCREPOSITORY}/<\\/a><\\/td>\\n            <\\/tr>" index.html
    fi
    echo "Added \${LCREPOSITORY} to index.html"
else
    echo "\${LCREPOSITORY} already exists in index.html, skipping"
fi

./rsync-index.sh
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
curl -k https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
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
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: promotion of ${REPOSITORY} from testing to release finished successfully - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: promotion of ${REPOSITORY} from testing to release failed - [${BUILD_URL}]")
        }
        always {
            script {
                currentBuild.description = "Repo: ${REPOSITORY} -> release"
            }
        }
    }
}
