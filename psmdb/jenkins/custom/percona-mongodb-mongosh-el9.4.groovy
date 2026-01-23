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
'''

void buildStage(String dockerOs, String stageParam) {
    writeFile file: 'vault_setup.sh', text: VAULT_REPO_SETUP
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
            defaultValue: 'almalinux:9.4',
            description: 'Docker image for build environment',
            name: 'DOCKER_OS'
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
            defaultValue: '2.5.10',
            description: 'VERSION value',
            name: 'VERSION'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-8.0/percona-server-mongodb-8.0.17-6/source/tarball/percona-mongodb-mongosh-2.5.10.tar.gz',
            description: 'Tarball URL for mongosh sources',
            name: 'MONGOSH_TARBALL_URL'
        )
        string(
            defaultValue: 'CUSTOM251094',
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
        stage('Build MongoDB Shell RPMs (AlmaLinux 9.4)') {
            parallel {
                stage('AlmaLinux 9.4 (x86_64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        buildStage(params.DOCKER_OS, "--build_mongosh=1 --build_variant=rpm-x64")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('AlmaLinux 9.4 (aarch64)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        buildStage(params.DOCKER_OS, "--build_mongosh=1 --build_variant=rpm-arm64")
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
