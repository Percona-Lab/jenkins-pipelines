library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Generates the in-container repo-pinning script for a given AlmaLinux minor
// version. Pinning every repo to a frozen vault minor keeps OpenSSL at that
// minor's level (e.g. 9.6 -> 3.2.x, no OPENSSL_3.4/3.5 symbols), so the built
// RPM installs on RHEL of that minor and below. The os-release spoof to Oracle
// Linux is only there to satisfy Bazel platform detection (rhel9_amd64).
String vaultRepoSetup(String ver) {
    return """
sed -i 's/^enabled=1/enabled=0/' /etc/yum.repos.d/almalinux-*.repo
cat <<'REPOEOF' > /etc/yum.repos.d/almalinux-${ver}-vault.repo
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
enabled=1
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9
REPOEOF

# Force Bazel platform detection to match Oracle Linux (rhel9_amd64)
if [ -f /etc/os-release ]; then
  sed -i 's/^ID=.*/ID="ol"/' /etc/os-release
  sed -i 's/^ID_LIKE=.*/ID_LIKE="fedora"/' /etc/os-release
  sed -i 's/^NAME=.*/NAME="Oracle Linux Server"/' /etc/os-release
  sed -i 's/^VERSION_ID=.*/VERSION_ID="${ver}"/' /etc/os-release
fi
"""
}

void buildRpmFromSrpm(Map cfg) {
    // Base image minor tracks the vault minor so the pre-installed packages
    // (incl. openssl) already match the pinned vault and no downgrade is needed.
    def dockerOs = "almalinux:${cfg.vaultVer}"
    dir(cfg.workDir) {
        writeFile file: 'vault_setup.sh', text: vaultRepoSetup(cfg.vaultVer)
        sh """
            set -o xtrace
            rm -rf test rpm
            mkdir -p test/srpm srpm rpm ${env.WORKSPACE}/${cfg.customDir}
            curl -Lf '${cfg.srpmUrl}' -o test/srpm/${cfg.srpmFile}
            cp -av test/srpm/${cfg.srpmFile} srpm/
            wget \$(echo ${cfg.gitRepo} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${cfg.branch}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
            export build_dir=\$(pwd -P)
            docker run -u root -v \${build_dir}:\${build_dir} ${dockerOs} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./vault_setup.sh
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${cfg.gitRepo} --branch=${cfg.branch} --psm_ver=${cfg.psmVer} --psm_release=${cfg.psmRelease} --mongo_tools_tag=${cfg.mongoToolsTag} --build_rpm=1
            "
            cp -av test/rpm/*.rpm ${env.WORKSPACE}/${cfg.customDir}/
            # Bundle the latest percona-telemetry-agent for this arch alongside the
            # psmdb packages: percona-server-mongodb pulls it in as a dependency, so
            # the custom bundle must be self-contained for an offline install.
            TELE_ARCH=\$(uname -m)
            TELE_BASE="https://repo.percona.com/telemetry/yum/release/9/RPMS/\${TELE_ARCH}"
            TELE_RPM=\$(curl -sL "\${TELE_BASE}/" | grep -oE "percona-telemetry-agent-[0-9.]+-[0-9]+\\.el9\\.\${TELE_ARCH}\\.rpm" | sort -Vu | tail -1)
            if [ -z "\${TELE_RPM}" ]; then echo "ERROR: no percona-telemetry-agent rpm found for \${TELE_ARCH}"; exit 1; fi
            curl -fL "\${TELE_BASE}/\${TELE_RPM}" -o "rpm/\${TELE_RPM}" \\
              && test -s "rpm/\${TELE_RPM}" \\
              && cp -av "rpm/\${TELE_RPM}" ${env.WORKSPACE}/${cfg.customDir}/
        """.stripIndent()
    }
}

void uploadRPMToDownloadsTesting(String cloudName, String productName, String productVersion) {
    def nodeLabel = (cloudName == 'Hetzner') ? 'launcher-x64' : 'master'
    node(nodeLabel) {
        deleteDir()
        unstash 'uploadPath'
        def pathToBuild = sh(returnStdout: true, script: "cat uploadPath").trim()
        withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
            sh """
                #!/bin/bash
                set -o xtrace

                cat /etc/hosts > hosts
                echo '10.30.6.9 repo.ci.percona.com' >> hosts
                sudo cp ./hosts /etc || true

                # Cut prefix if it's provided
                cutProductVersion=\$(echo ${productVersion} | sed 's/release-//g');

                target_dir=/data/downloads/TESTING/${productName}-\${cutProductVersion}

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    ssh -p 2222 jenkins-deploy.jenkins-deploy.web.r.int.percona.com mkdir -p \${target_dir}

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    rsync -avt -e '"ssh -p 2222"' --bwlimit=50000 --progress ${pathToBuild}/binary/redhat/ jenkins-deploy.jenkins-deploy.web.r.int.percona.com:\${target_dir}/

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
            choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD'
        )
        string(
            defaultValue: 'https://github.com/vorsel/percona-server-mongodb.git',
            description: 'Repository to fetch build script from',
            name: 'GIT_REPO'
        )
        booleanParam(
            defaultValue: true,
            description: 'Build PSMDB 6.0 line',
            name: 'BUILD_60'
        )
        string(
            defaultValue: '9.6',
            description: 'AlmaLinux vault minor to pin for 6.0 (also selects almalinux:<ver> base image)',
            name: 'VAULT_VERSION_60'
        )
        string(
            defaultValue: 'release-6.0.28-22_el9.6',
            description: 'Branch for 6.0 build script',
            name: 'BRANCH_60'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-6.0/percona-server-mongodb-6.0.28-22/source/redhat/percona-server-mongodb-6.0.28-22.generic.src.rpm',
            description: 'SRPM URL for 6.0',
            name: 'SRPM_URL_60'
        )
        string(
            defaultValue: '6.0.28',
            description: 'PSMDB version for 6.0 build',
            name: 'PSMDB_VERSION_60'
        )
        string(
            defaultValue: '22',
            description: 'PSMDB release for 6.0 build',
            name: 'PSMDB_RELEASE_60'
        )
        string(
            defaultValue: '100.17.0',
            description: 'Mongo tools tag for 6.0 build',
            name: 'MONGO_TOOLS_TAG_60'
        )
        string(
            defaultValue: 'CUSTOM60282296',
            description: 'Custom folder for 6.0 artifacts (must be CUSTOM<digits> -> issue-CUSTOM<digits>, otherwise it is not indexed/reachable on the downloads host)',
            name: 'CUSTOM_DIR_60'
        )
        booleanParam(
            defaultValue: true,
            description: 'Build PSMDB 7.0 line',
            name: 'BUILD_70'
        )
        string(
            defaultValue: '9.6',
            description: 'AlmaLinux vault minor to pin for 7.0 (also selects almalinux:<ver> base image)',
            name: 'VAULT_VERSION_70'
        )
        string(
            defaultValue: 'release-7.0.34-19_el9.6',
            description: 'Branch for 7.0 build script',
            name: 'BRANCH_70'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-7.0/percona-server-mongodb-7.0.34-19/source/redhat/percona-server-mongodb-7.0.34-19.generic.src.rpm',
            description: 'SRPM URL for 7.0',
            name: 'SRPM_URL_70'
        )
        string(
            defaultValue: '7.0.34',
            description: 'PSMDB version for 7.0 build',
            name: 'PSMDB_VERSION_70'
        )
        string(
            defaultValue: '19',
            description: 'PSMDB release for 7.0 build',
            name: 'PSMDB_RELEASE_70'
        )
        string(
            defaultValue: '100.17.0',
            description: 'Mongo tools tag for 7.0 build',
            name: 'MONGO_TOOLS_TAG_70'
        )
        string(
            defaultValue: 'CUSTOM70341996',
            description: 'Custom folder for 7.0 artifacts (must be CUSTOM<digits> -> issue-CUSTOM<digits>, otherwise it is not indexed/reachable on the downloads host)',
            name: 'CUSTOM_DIR_70'
        )
        booleanParam(
            defaultValue: true,
            description: 'Build PSMDB 8.0 line',
            name: 'BUILD_80'
        )
        string(
            defaultValue: '9.6',
            description: 'AlmaLinux vault minor to pin for 8.0 (also selects almalinux:<ver> base image)',
            name: 'VAULT_VERSION_80'
        )
        string(
            defaultValue: 'release-8.0.23-10_el9.6',
            description: 'Branch for 8.0 build script',
            name: 'BRANCH_80'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-8.0/percona-server-mongodb-8.0.23-10/source/redhat/percona-server-mongodb-8.0.23-10.generic.src.rpm',
            description: 'SRPM URL for 8.0',
            name: 'SRPM_URL_80'
        )
        string(
            defaultValue: '8.0.23',
            description: 'PSMDB version for 8.0 build',
            name: 'PSMDB_VERSION_80'
        )
        string(
            defaultValue: '10',
            description: 'PSMDB release for 8.0 build',
            name: 'PSMDB_RELEASE_80'
        )
        string(
            defaultValue: '100.17.0',
            description: 'Mongo tools tag for 8.0 build',
            name: 'MONGO_TOOLS_TAG_80'
        )
        string(
            defaultValue: 'CUSTOM80231096',
            description: 'Custom folder for 8.0 artifacts (must be CUSTOM<digits> -> issue-CUSTOM<digits>, otherwise it is not indexed/reachable on the downloads host)',
            name: 'CUSTOM_DIR_80'
        )
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Build RPMs (AlmaLinux vault)') {
            parallel {
                stage('PSMDB 6.0') {
                    when { beforeAgent true; expression { return params.BUILD_60 } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-6.0',
                            vaultVer: params.VAULT_VERSION_60,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_60,
                            psmVer: params.PSMDB_VERSION_60,
                            psmRelease: params.PSMDB_RELEASE_60,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_60,
                            srpmUrl: params.SRPM_URL_60,
                            srpmFile: "percona-server-mongodb-${params.PSMDB_VERSION_60}-${params.PSMDB_RELEASE_60}.generic.src.rpm",
                            customDir: params.CUSTOM_DIR_60
                        )
                        dir('build-6.0') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_60)
                        }
                    }
                }
                stage('PSMDB 6.0 (aarch64)') {
                    when { beforeAgent true; expression { return params.BUILD_60 } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-6.0-arm',
                            vaultVer: params.VAULT_VERSION_60,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_60,
                            psmVer: params.PSMDB_VERSION_60,
                            psmRelease: params.PSMDB_RELEASE_60,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_60,
                            srpmUrl: params.SRPM_URL_60,
                            srpmFile: "percona-server-mongodb-${params.PSMDB_VERSION_60}-${params.PSMDB_RELEASE_60}.generic.src.rpm",
                            customDir: params.CUSTOM_DIR_60
                        )
                        dir('build-6.0-arm') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_60)
                        }
                    }
                }
                stage('PSMDB 7.0') {
                    when { beforeAgent true; expression { return params.BUILD_70 } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-7.0',
                            vaultVer: params.VAULT_VERSION_70,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_70,
                            psmVer: params.PSMDB_VERSION_70,
                            psmRelease: params.PSMDB_RELEASE_70,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_70,
                            srpmUrl: params.SRPM_URL_70,
                            srpmFile: "percona-server-mongodb-${params.PSMDB_VERSION_70}-${params.PSMDB_RELEASE_70}.generic.src.rpm",
                            customDir: params.CUSTOM_DIR_70
                        )
                        dir('build-7.0') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_70)
                        }
                    }
                }
                stage('PSMDB 7.0 (aarch64)') {
                    when { beforeAgent true; expression { return params.BUILD_70 } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-7.0-arm',
                            vaultVer: params.VAULT_VERSION_70,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_70,
                            psmVer: params.PSMDB_VERSION_70,
                            psmRelease: params.PSMDB_RELEASE_70,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_70,
                            srpmUrl: params.SRPM_URL_70,
                            srpmFile: "percona-server-mongodb-${params.PSMDB_VERSION_70}-${params.PSMDB_RELEASE_70}.generic.src.rpm",
                            customDir: params.CUSTOM_DIR_70
                        )
                        dir('build-7.0-arm') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_70)
                        }
                    }
                }
                stage('PSMDB 8.0') {
                    when { beforeAgent true; expression { return params.BUILD_80 } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-8.0',
                            vaultVer: params.VAULT_VERSION_80,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_80,
                            psmVer: params.PSMDB_VERSION_80,
                            psmRelease: params.PSMDB_RELEASE_80,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_80,
                            srpmUrl: params.SRPM_URL_80,
                            srpmFile: "percona-server-mongodb-${params.PSMDB_VERSION_80}-${params.PSMDB_RELEASE_80}.generic.src.rpm",
                            customDir: params.CUSTOM_DIR_80
                        )
                        dir('build-8.0') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_80)
                        }
                    }
                }
                stage('PSMDB 8.0 (aarch64)') {
                    when { beforeAgent true; expression { return params.BUILD_80 } }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-8.0-arm',
                            vaultVer: params.VAULT_VERSION_80,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_80,
                            psmVer: params.PSMDB_VERSION_80,
                            psmRelease: params.PSMDB_RELEASE_80,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_80,
                            srpmUrl: params.SRPM_URL_80,
                            srpmFile: "percona-server-mongodb-${params.PSMDB_VERSION_80}-${params.PSMDB_RELEASE_80}.generic.src.rpm",
                            customDir: params.CUSTOM_DIR_80
                        )
                        dir('build-8.0-arm') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_80)
                        }
                    }
                }
            }
        }
        stage('Upload RPMs to custom folders') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
            }
            steps {
                script {
                    def customDirs = []
                    if (params.BUILD_60) { customDirs << params.CUSTOM_DIR_60 }
                    if (params.BUILD_70) { customDirs << params.CUSTOM_DIR_70 }
                    if (params.BUILD_80) { customDirs << params.CUSTOM_DIR_80 }
                    customDirs.each { customDir ->
                        writeFile file: 'uploadPath', text: "UPLOAD/experimental/${customDir}"
                        stash includes: 'uploadPath', name: 'uploadPath'
                        uploadRPMfromAWS(params.CLOUD, "rpm/", customDir)
                        uploadRPMToDownloadsTesting(params.CLOUD, "issue", customDir)
                    }
                }
            }
        }
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
