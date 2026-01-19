import groovy.transform.Field

library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

@Field
final String VAULT_REPO_SETUP = '''
sed -i 's/^enabled=1/enabled=0/' /etc/yum.repos.d/almalinux-*.repo
cat <<'EOF' > /etc/yum.repos.d/almalinux-9.4-vault.repo
[almalinux-9.4-baseos]
name=AlmaLinux 9.4 BaseOS (vault)
baseurl=https://repo.almalinux.org/vault/9.4/BaseOS/\$basearch/os/
enabled=1
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9

[almalinux-9.4-appstream]
name=AlmaLinux 9.4 AppStream (vault)
baseurl=https://repo.almalinux.org/vault/9.4/AppStream/\$basearch/os/
enabled=1
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9

[almalinux-9.4-extras]
name=AlmaLinux 9.4 Extras (vault)
baseurl=https://repo.almalinux.org/vault/9.4/extras/\$basearch/os/
enabled=1
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9

[almalinux-9.4-crb]
name=AlmaLinux 9.4 CRB (vault)
baseurl=https://repo.almalinux.org/vault/9.4/CRB/\$basearch/os/
enabled=0
gpgcheck=1
gpgkey=https://repo.almalinux.org/almalinux/RPM-GPG-KEY-AlmaLinux-9
EOF

# Force Bazel platform detection to match Oracle Linux (rhel9_amd64)
if [ -f /etc/os-release ]; then
  sed -i 's/^ID=.*/ID="ol"/' /etc/os-release
  sed -i 's/^ID_LIKE=.*/ID_LIKE="fedora"/' /etc/os-release
  sed -i 's/^NAME=.*/NAME="Oracle Linux Server"/' /etc/os-release
  sed -i 's/^VERSION_ID=.*/VERSION_ID="9.4"/' /etc/os-release
fi
'''

void buildRpmFromSrpm(Map cfg) {
    dir(cfg.workDir) {
        writeFile file: 'vault_setup.sh', text: VAULT_REPO_SETUP
        sh """
            set -o xtrace
            rm -rf test rpm
            mkdir -p test/srpm srpm rpm ${env.WORKSPACE}/${cfg.customDir}
            curl -Lf '${cfg.srpmUrl}' -o test/srpm/${cfg.srpmFile}
            cp -av test/srpm/${cfg.srpmFile} srpm/
            wget \$(echo ${cfg.gitRepo} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${cfg.branch}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
            export build_dir=\$(pwd -P)
            docker run -u root -v \${build_dir}:\${build_dir} ${cfg.dockerOs} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./vault_setup.sh
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --repo=${cfg.gitRepo} --branch=${cfg.branch} --psm_ver=${cfg.psmVer} --psm_release=${cfg.psmRelease} --mongo_tools_tag=${cfg.mongoToolsTag} --build_rpm=1
            "
            cp -av test/rpm/*.rpm rpm/
            cp -av test/rpm/*.rpm ${env.WORKSPACE}/${cfg.customDir}/
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
            defaultValue: 'almalinux:9.4',
            description: 'Docker image for build environment',
            name: 'DOCKER_OS'
        )
        string(
            defaultValue: 'https://github.com/vorsel/percona-server-mongodb.git',
            description: 'Repository to fetch build script from',
            name: 'GIT_REPO'
        )
        string(
            defaultValue: 'release-6.0.27-21_el9.4',
            description: 'Branch for 6.0 build script',
            name: 'BRANCH_60'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-6.0/percona-server-mongodb-6.0.27-21/source/redhat/percona-server-mongodb-6.0.27-21.generic.src.rpm',
            description: 'SRPM URL for 6.0',
            name: 'SRPM_URL_60'
        )
        string(
            defaultValue: 'CUSTOM60272194',
            description: 'Custom folder for 6.0 artifacts',
            name: 'CUSTOM_DIR_60'
        )
        string(
            defaultValue: '6.0.27',
            description: 'PSMDB version for 6.0 build',
            name: 'PSMDB_VERSION_60'
        )
        string(
            defaultValue: '21',
            description: 'PSMDB release for 6.0 build',
            name: 'PSMDB_RELEASE_60'
        )
        string(
            defaultValue: '100.14.0',
            description: 'Mongo tools tag for 6.0 build',
            name: 'MONGO_TOOLS_TAG_60'
        )
        string(
            defaultValue: 'release-7.0.28-15_el9.4',
            description: 'Branch for 7.0 build script',
            name: 'BRANCH_70'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-7.0/percona-server-mongodb-7.0.28-15/source/redhat/percona-server-mongodb-7.0.28-15.generic.src.rpm',
            description: 'SRPM URL for 7.0',
            name: 'SRPM_URL_70'
        )
        string(
            defaultValue: 'CUSTOM70281594',
            description: 'Custom folder for 7.0 artifacts',
            name: 'CUSTOM_DIR_70'
        )
        string(
            defaultValue: '7.0.28',
            description: 'PSMDB version for 7.0 build',
            name: 'PSMDB_VERSION_70'
        )
        string(
            defaultValue: '15',
            description: 'PSMDB release for 7.0 build',
            name: 'PSMDB_RELEASE_70'
        )
        string(
            defaultValue: '100.14.0',
            description: 'Mongo tools tag for 7.0 build',
            name: 'MONGO_TOOLS_TAG_70'
        )
        string(
            defaultValue: 'release-8.0.17-6_el9.4',
            description: 'Branch for 8.0 build script',
            name: 'BRANCH_80'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-8.0/percona-server-mongodb-8.0.17-6/source/redhat/percona-server-mongodb-8.0.17-6.generic.src.rpm',
            description: 'SRPM URL for 8.0',
            name: 'SRPM_URL_80'
        )
        string(
            defaultValue: 'CUSTOM8017694',
            description: 'Custom folder for 8.0 artifacts',
            name: 'CUSTOM_DIR_80'
        )
        string(
            defaultValue: '8.0.17',
            description: 'PSMDB version for 8.0 build',
            name: 'PSMDB_VERSION_80'
        )
        string(
            defaultValue: '6',
            description: 'PSMDB release for 8.0 build',
            name: 'PSMDB_RELEASE_80'
        )
        string(
            defaultValue: '100.14.0',
            description: 'Mongo tools tag for 8.0 build',
            name: 'MONGO_TOOLS_TAG_80'
        )
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Build RPMs (AlmaLinux 9.4)') {
            parallel {
                stage('PSMDB 6.0.27-21') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-6.0',
                            dockerOs: params.DOCKER_OS,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_60,
                            psmVer: params.PSMDB_VERSION_60,
                            psmRelease: params.PSMDB_RELEASE_60,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_60,
                            srpmUrl: params.SRPM_URL_60,
                            srpmFile: 'percona-server-mongodb-6.0.27-21.generic.src.rpm',
                            customDir: params.CUSTOM_DIR_60
                        )
                        dir('build-6.0') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_60)
                        }
                    }
                }
                stage('PSMDB 6.0.27-21 (aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-6.0-arm',
                            dockerOs: params.DOCKER_OS,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_60,
                            psmVer: params.PSMDB_VERSION_60,
                            psmRelease: params.PSMDB_RELEASE_60,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_60,
                            srpmUrl: params.SRPM_URL_60,
                            srpmFile: 'percona-server-mongodb-6.0.27-21.generic.src.rpm',
                            customDir: params.CUSTOM_DIR_60
                        )
                        dir('build-6.0-arm') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_60)
                        }
                    }
                }
                stage('PSMDB 7.0.28-15') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-7.0',
                            dockerOs: params.DOCKER_OS,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_70,
                            psmVer: params.PSMDB_VERSION_70,
                            psmRelease: params.PSMDB_RELEASE_70,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_70,
                            srpmUrl: params.SRPM_URL_70,
                            srpmFile: 'percona-server-mongodb-7.0.28-15.generic.src.rpm',
                            customDir: params.CUSTOM_DIR_70
                        )
                        dir('build-7.0') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_70)
                        }
                    }
                }
                stage('PSMDB 7.0.28-15 (aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-7.0-arm',
                            dockerOs: params.DOCKER_OS,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_70,
                            psmVer: params.PSMDB_VERSION_70,
                            psmRelease: params.PSMDB_RELEASE_70,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_70,
                            srpmUrl: params.SRPM_URL_70,
                            srpmFile: 'percona-server-mongodb-7.0.28-15.generic.src.rpm',
                            customDir: params.CUSTOM_DIR_70
                        )
                        dir('build-7.0-arm') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_70)
                        }
                    }
                }
                stage('PSMDB 8.0.17-6') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-8.0',
                            dockerOs: params.DOCKER_OS,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_80,
                            psmVer: params.PSMDB_VERSION_80,
                            psmRelease: params.PSMDB_RELEASE_80,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_80,
                            srpmUrl: params.SRPM_URL_80,
                            srpmFile: 'percona-server-mongodb-8.0.17-6.generic.src.rpm',
                            customDir: params.CUSTOM_DIR_80
                        )
                        dir('build-8.0') {
                            pushArtifactFolder(params.CLOUD, "rpm/", params.CUSTOM_DIR_80)
                        }
                    }
                }
                stage('PSMDB 8.0.17-6 (aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        buildRpmFromSrpm(
                            workDir: 'build-8.0-arm',
                            dockerOs: params.DOCKER_OS,
                            gitRepo: params.GIT_REPO,
                            branch: params.BRANCH_80,
                            psmVer: params.PSMDB_VERSION_80,
                            psmRelease: params.PSMDB_RELEASE_80,
                            mongoToolsTag: params.MONGO_TOOLS_TAG_80,
                            srpmUrl: params.SRPM_URL_80,
                            srpmFile: 'percona-server-mongodb-8.0.17-6.generic.src.rpm',
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
                    [params.CUSTOM_DIR_60, params.CUSTOM_DIR_70, params.CUSTOM_DIR_80].each { customDir ->
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
