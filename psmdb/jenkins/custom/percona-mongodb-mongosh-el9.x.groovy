library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Generates the in-container repo-pinning script for a given AlmaLinux minor
// version. Pinning every repo to a frozen vault minor keeps OpenSSL at that
// minor's level (e.g. 9.6 -> 3.2.x, no OPENSSL_3.4/3.5 symbols), so the built
// RPM installs on RHEL of that minor and below.
String vaultRepoSetup(String ver) {
    return """
sed -i 's/^enabled=1/enabled=0/' /etc/yum.repos.d/almalinux-*.repo
cat <<'EOF' > /etc/yum.repos.d/almalinux-${ver}-vault.repo
[almalinux-${ver}-baseos]
name=AlmaLinux ${ver} BaseOS (vault)
baseurl=https://repo.almalinux.org/vault/${ver}/BaseOS/\$basearch/os/
enabled=1
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9

[almalinux-${ver}-appstream]
name=AlmaLinux ${ver} AppStream (vault)
baseurl=https://repo.almalinux.org/vault/${ver}/AppStream/\$basearch/os/
enabled=1
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9

[almalinux-${ver}-extras]
name=AlmaLinux ${ver} Extras (vault)
baseurl=https://repo.almalinux.org/vault/${ver}/extras/\$basearch/os/
enabled=1
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9

[almalinux-${ver}-crb]
name=AlmaLinux ${ver} CRB (vault)
baseurl=https://repo.almalinux.org/vault/${ver}/CRB/\$basearch/os/
enabled=0
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9
EOF
"""
}

void buildStage(String ver, String stageParam) {
    // Base image minor tracks the vault minor so the pre-installed packages
    // (incl. openssl) already match the pinned vault and no downgrade is needed.
    def dockerOs = "almalinux:${ver}"
    writeFile file: 'vault_setup.sh', text: vaultRepoSetup(ver)
    sh """
        set -o xtrace
        rm -rf test
        mkdir -p test/source_tarball
        wget \$(echo ${BUILD_GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BUILD_GIT_BRANCH}/percona-mongodb-mongosh-builder.sh -O percona-mongodb-mongosh-builder.sh
        wget \$(echo ${BUILD_GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BUILD_GIT_BRANCH}/mongosh.patch -O mongosh.patch
        TARBALL_NAME=\$(basename ${MONGOSH_TARBALL_URL} | cut -d'?' -f1)
        wget ${MONGOSH_TARBALL_URL} -O test/source_tarball/\$TARBALL_NAME
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${dockerOs} sh -c "
        set -o xtrace
        cd \${build_dir}
        bash -x ./vault_setup.sh
        bash -x ./percona-mongodb-mongosh-builder.sh --builddir=\${build_dir}/test --install_deps=1
        bash -x ./percona-mongodb-mongosh-builder.sh --builddir=\${build_dir}/test --repo=${MONGOSH_GIT_REPO} --version=${VERSION} --branch=${MONGOSH_GIT_BRANCH} ${stageParam}"
    """.stripIndent()
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

void uploadRPMToDownloadsTesting(String cloudName, String productName, String productVersion) {
    def nodeLabel = (cloudName == 'Hetzner') ? 'launcher-x64' : 'master'
    node(nodeLabel) {
        deleteDir()
        unstash 'uploadPath'
        def pathToBuild = sh(returnStdout: true, script: "cat uploadPath").trim()
        def cutProductVersion = productVersion.replaceFirst(/^release-/, '')
        def targetDir = "/data/downloads/TESTING/${productName}-${cutProductVersion}"
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                #!/bin/bash
                set -o xtrace

                cat /etc/hosts > hosts
                echo '10.30.6.9 repo.ci.percona.com' >> hosts
                sudo cp ./hosts /etc || true

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    ssh -p 2222 jenkins-deploy.jenkins-deploy.web.r.int.percona.com mkdir -p ${targetDir}

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    rsync -avt -e '"ssh -p 2222"' --bwlimit=50000 --progress ${pathToBuild}/binary/redhat/ jenkins-deploy.jenkins-deploy.web.r.int.percona.com:${targetDir}/

                curl -k https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
            """
        }
        deleteDir()
    }
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD'
        )
        string(
            defaultValue: '9.6',
            description: 'AlmaLinux vault minor to pin (also selects almalinux:<ver> base image)',
            name: 'VAULT_VERSION'
        )
        string(
            defaultValue: 'https://github.com/mongodb-js/mongosh.git',
            description: 'URL for mongodb-js/mongosh repository',
            name: 'MONGOSH_GIT_REPO'
        )
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for mongodb-js/mongosh repository',
            name: 'MONGOSH_GIT_BRANCH'
        )
        string(
            defaultValue: 'https://github.com/percona/mongosh-packaging.git',
            description: 'URL for percona mongosh packaging repository',
            name: 'BUILD_GIT_REPO'
        )
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for build script repository',
            name: 'BUILD_GIT_BRANCH'
        )
        string(
            defaultValue: '2.8.3',
            description: 'VERSION value',
            name: 'VERSION'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-8.0/percona-server-mongodb-8.0.23-10/source/tarball/percona-mongodb-mongosh-2.8.3.tar.gz',
            description: 'Tarball URL for mongosh sources',
            name: 'MONGOSH_TARBALL_URL'
        )
        string(
            defaultValue: 'CUSTOM28396',
            description: 'Issue folder suffix for testing downloads',
            name: 'CUSTOM_ISSUE'
        )
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Init') {
            steps {
                script {
                    AWS_STASH_PATH = "issue-${params.CUSTOM_ISSUE}"
                    writeFile file: 'uploadPath', text: "UPLOAD/experimental/${AWS_STASH_PATH}"
                }
                stash includes: 'uploadPath', name: 'uploadPath'
            }
        }
        stage('Build MongoDB Shell RPMs (AlmaLinux vault)') {
            parallel {
                stage('AlmaLinux vault (x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        buildStage(params.VAULT_VERSION, "--build_mongosh=1 --build_variant=rpm-x64")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('AlmaLinux vault (aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        buildStage(params.VAULT_VERSION, "--build_mongosh=1 --build_variant=rpm-arm64")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Upload packages from S3') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
            }
            steps {
                cleanUpWS()
                uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                uploadRPMToDownloadsTesting(params.CLOUD, "issue", params.CUSTOM_ISSUE)
            }
        }
        stage('Sign packages') {
            steps {
                signRPM(params.CLOUD)
            }
        }
    }
    post {
        success {
            slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${MONGOSH_GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "[${CLOUD}]: Built on ${MONGOSH_GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${MONGOSH_GIT_BRANCH} - [${BUILD_URL}]")
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
