library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        rm -rf test/*
        mkdir -p test
        wget \$(echo ${BS_GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BS_GIT_BRANCH}/percona-packaging/scripts/psmdb_builder.sh -O psmdb_builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test ${STAGE_PARAM}"
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
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-7.0/percona-server-mongodb-7.0.40-22/source/tarball/percona-server-mongodb-7.0.40-22.tar.gz',
            description: 'URL of the released source tarball to build the el7 binary tarball from',
            name: 'SOURCE_TARBALL_URL')
        string(
            defaultValue: 'https://github.com/vorsel/percona-server-mongodb.git',
            description: 'URL for CUSTOM percona-server-mongodb repository to take BuildScript from',
            name: 'BS_GIT_REPO')
        string(
            defaultValue: 'release-7.0.40-22_el7',
            description: 'Tag/Branch for CUSTOM percona-server-mongodb repository to take BuildScript from (carries the el7 kTLS patch)',
            name: 'BS_GIT_BRANCH')
        string(
            defaultValue: 'CUSTOM261',
            description: 'Jira task name without dash(CUSTOM-261 -> CUSTOM261). Must be CUSTOM<digits>, otherwise issue-CUSTOM<digits> is not indexed on the downloads host',
            name: 'JIRA_TASK')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Centos 7 binary tarball(glibc2.17)') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${SOURCE_TARBALL_URL} - [${BUILD_URL}]")
                cleanUpWS()
                // psmdb_builder.sh picks the source tarball up from ./source_tarball,
                // so no --get_sources needed. sbom.cdx.json, mongo-tools and
                // percona-packaging all ship inside the released archive.
                sh """
                    set -o xtrace
                    mkdir -p source_tarball
                    curl -fL '${params.SOURCE_TARBALL_URL}' -o source_tarball/\$(basename '${params.SOURCE_TARBALL_URL}')
                    ls -la source_tarball/
                """
                buildStage("centos:7", "--build_tarball=1")
                script {
                    AWS_STASH_PATH = "${JIRA_TASK}/${BUILD_NUMBER}"
                    writeFile file: 'uploadPath', text: "UPLOAD/experimental/${AWS_STASH_PATH}"
                }
                // pushArtifactFolder swallows aws errors, so fail here rather than
                // silently publishing an empty folder
                sh '''
                    ls -la tarball/
                    test $(ls -1 tarball/*.tar.gz | wc -l) -gt 0
                '''
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
            }
        }
        stage('Upload tarballs from S3') {
            steps {
                uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "issue", "${JIRA_TASK}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${SOURCE_TARBALL_URL} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built ${SOURCE_TARBALL_URL} for el7. Downloads: TESTING/issue-${JIRA_TASK}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${SOURCE_TARBALL_URL} - [${BUILD_URL}]")
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
