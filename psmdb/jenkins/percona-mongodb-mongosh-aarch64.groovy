library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${BUILD_GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BUILD_GIT_BRANCH}/percona-mongodb-mongosh-builder.sh -O percona-mongodb-mongosh-builder.sh
        wget \$(echo ${BUILD_GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BUILD_GIT_BRANCH}/mongosh.patch -O mongosh.patch
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./percona-mongodb-mongosh-builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./percona-mongodb-mongosh-builder.sh --builddir=\${build_dir}/test --repo=${MONGOSH_GIT_REPO} --version=${VERSION} --branch=${MONGOSH_GIT_BRANCH} ${STAGE_PARAM}"
    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label 'master'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/mongodb-js/mongosh.git',
            description: 'URL for percona-mongodb-backup repository',
            name: 'MONGOSH_GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for mongodb-js/mongosh repository',
            name: 'MONGOSH_GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/mongosh-packaging.git',
            description: 'URL for percona mongosh packaging repository',
            name: 'BUILD_GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for build script repository',
            name: 'BUILD_GIT_BRANCH')
        string(
            defaultValue: '1.6.0',
            description: 'VERSION value',
            name: 'VERSION')
        string(
            defaultValue: 'psmdb-60',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
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
        stage('Create MongoDB Shell source tarball') {
            agent {
                label 'docker-64gb-aarch64'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${MONGOSH_GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-mongodb-mongosh.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-mongodb-mongosh.properties
                   cat uploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build MongoDB Shell aarch64 RPMs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label 'docker-64gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_mongosh=1 --build_variant=rpm-arm64")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
            }
        }

        stage('Sign packages') {
            steps {
                signRPM()
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild(PSMDB_REPO, COMPONENT)
            }
        }

    }
    post {
        success {
            slackNotify("#releases", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${MONGOSH_GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${MONGOSH_GIT_BRANCH}. Path to packages: experimental/${AWS_STASH_PATH}"
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
