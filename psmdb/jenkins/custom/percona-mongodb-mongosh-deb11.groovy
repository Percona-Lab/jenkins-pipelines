library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// psmdb 6.0 still ships on bullseye, the main mongosh job dropped it in PSMDB-2269.
// This one rebuilds an already released mongosh source tarball for bullseye only.

void fetchSourceTarball(String source) {
    def src = source.trim()
    if (src ==~ /^https?:\/\/.+/) {
        sh """
            set -o xtrace
            mkdir -p source_tarball
            curl -fL '${src}' -o source_tarball/\$(basename '${src}' | cut -d'?' -f1)
        """
    } else {
        // path as printed in the mongosh build description: experimental/BUILDS/...
        def stashPath = src.replaceFirst(/^(UPLOAD\/)?experimental\//, '').replaceFirst(/\/+$/, '')
        popArtifactFolder(params.CLOUD, "source_tarball/", stashPath)
    }
    sh '''
        ls -la source_tarball/
        test $(ls -1 source_tarball/percona-mongodb-mongosh-*.tar.gz | wc -l) -eq 1
    '''
}

void buildStage(String dockerOs, String version, String stageParam) {
    sh """
        set -o xtrace
        rm -rf test
        mkdir -p test/source_tarball
        cp source_tarball/percona-mongodb-mongosh-*.tar.gz test/source_tarball/
        wget \$(echo ${BUILD_GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BUILD_GIT_BRANCH}/percona-mongodb-mongosh-builder.sh -O percona-mongodb-mongosh-builder.sh
        export build_dir=\$(pwd -P)
        docker run --rm -u root -v \${build_dir}:\${build_dir} ${dockerOs} sh -c "
            set -o xtrace
            cd \${build_dir}
            # bullseye is EOL: deb.debian.org still has the security index but not the pool
            sed -i 's|http://deb.debian.org/debian-security|http://archive.debian.org/debian-security|' /etc/apt/sources.list
            bash -x ./percona-mongodb-mongosh-builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./percona-mongodb-mongosh-builder.sh --builddir=\${build_dir}/test --version=${version} ${stageParam}"
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH
def MONGOSH_VERSION

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'experimental/BUILDS/percona-mongodb-mongosh/percona-mongodb-mongosh-2.14.1/v2.14.1/456a58b1/47',
            description: 'mongosh source tarball: either an http(s) URL of percona-mongodb-mongosh-<version>.tar.gz, or the "Path to packages" of a percona-mongodb-mongosh build (experimental/BUILDS/...)',
            name: 'MONGOSH_SOURCE')
        string(
            defaultValue: 'https://github.com/percona/mongosh-packaging.git',
            description: 'URL for percona mongosh packaging repository',
            name: 'BUILD_GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for build script repository',
            name: 'BUILD_GIT_BRANCH')
        string(
            defaultValue: 'psmdb-60',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'testing\nlaboratory\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Build MongoDB Shell DEB (Debian Bullseye x86_64)') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting bullseye build for ${MONGOSH_SOURCE} - [${BUILD_URL}]")
                cleanUpWS()
                fetchSourceTarball(params.MONGOSH_SOURCE)
                script {
                    MONGOSH_VERSION = sh(returnStdout: true, script: "basename source_tarball/percona-mongodb-mongosh-*.tar.gz .tar.gz | sed 's/^percona-mongodb-mongosh-//'").trim()
                    AWS_STASH_PATH = "BUILDS/percona-mongodb-mongosh/percona-mongodb-mongosh-${MONGOSH_VERSION}/deb11/${BUILD_NUMBER}"
                    writeFile file: 'uploadPath', text: "UPLOAD/experimental/${AWS_STASH_PATH}"
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                buildStage("debian:bullseye", MONGOSH_VERSION, "--build_mongosh=1 --build_variant=deb-x64")
                sh '''
                    ls -la deb/
                    test $(ls -1 deb/*.bullseye_amd64.deb | wc -l) -gt 0
                '''
                pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
            }
        }
        stage('Upload packages from S3') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
            }
            steps {
                cleanUpWS()
                uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
            }
        }
        stage('Sign packages') {
            steps {
                signDEB(params.CLOUD)
            }
        }
        stage('Push to public repository') {
            steps {
                sync2ProdAutoBuild(params.CLOUD, PSMDB_REPO, COMPONENT)
            }
        }
    }
    post {
        success {
            slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: bullseye build has been finished successfully for ${MONGOSH_SOURCE} - [${BUILD_URL}]")
            script {
                currentBuild.description = "[${CLOUD}]: mongosh ${MONGOSH_VERSION} for bullseye. Path to packages: experimental/${AWS_STASH_PATH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: bullseye build failed for ${MONGOSH_SOURCE} - [${BUILD_URL}]")
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
