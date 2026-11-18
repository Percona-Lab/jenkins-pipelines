library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Custom rebuild of a released PSMDB 7.0 for Debian 11. Bullseye is EOL and was
// dropped from the 7.0 job in PSMDB-2269, so this only goes to TESTING/issue-<JIRA_TASK>,
// never to the repo. Takes the released sources from downloads, no --get_sources.
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
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} ${STAGE_PARAM}"
    """
}

// fetch the released sources into the dir the builder looks in
void fetchSources(String dir, List names) {
    def base = params.SOURCE_BASE_URL.replaceAll('/+$', '')
    for (n in names) {
        sh """
            set -o xtrace
            mkdir -p ${dir}
            curl -fL '${base}/${n}' -o ${dir}/\$(basename '${n}')
        """
    }
    sh "ls -la ${dir}/"
}

// no shared-library equivalent for debs, same helper as custom-psmdb-tools
void uploadDEBToDownloadsTesting(String cloudName, String productName, String productVersion) {
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

                cutProductVersion=\$(echo ${productVersion} | sed 's/release-//g');
                target_dir=/data/downloads/TESTING/${productName}-\${cutProductVersion}

                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    ssh -p 2222 jenkins-deploy.jenkins-deploy.web.r.int.percona.com mkdir -p \${target_dir}

                # flat file list: the downloads host doesn't let you descend into subfolders
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    rsync -avt -e '"ssh -p 2222"' --bwlimit=50000 --progress ${pathToBuild}/binary/debian/*/*/*.deb jenkins-deploy.jenkins-deploy.web.r.int.percona.com:\${target_dir}/

                curl -k https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory
            """
        }
        deleteDir()
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH
def BUILD_TARBALL = false
def BUILD_PACKAGES = false

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        choice(
            choices: ['both', 'packages', 'tarball'],
            description: 'What to build: Debian 11 debs, .bullseye binary tarball (glibc 2.31) or both',
            name: 'BUILD_TYPE')
        string(
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-7.0/percona-server-mongodb-7.0.48-25/source',
            description: 'Released source folder on downloads, with tarball/ and debian/ inside',
            name: 'SOURCE_BASE_URL')
        string(
            defaultValue: '7.0.48',
            description: 'Released PSMDB version, e.g. 7.0.48',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '25',
            description: 'Released PSMDB release, e.g. 25 for 7.0.48-25',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: 'https://github.com/vorsel/percona-server-mongodb.git',
            description: 'URL for CUSTOM percona-server-mongodb repository to take BuildScript from',
            name: 'BS_GIT_REPO')
        string(
            defaultValue: 'release-7.0.48-25_deb11',
            description: 'Tag/Branch for CUSTOM percona-server-mongodb repository to take BuildScript from (carries the bullseye archive fix)',
            name: 'BS_GIT_BRANCH')
        string(
            defaultValue: 'CUSTOM274',
            description: 'Jira task name without dash(CUSTOM-274 -> CUSTOM274). Must be CUSTOM<digits>, otherwise issue-CUSTOM<digits> is not indexed on the downloads host',
            name: 'JIRA_TASK')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    BUILD_TARBALL = params.BUILD_TYPE in ['tarball', 'both']
                    BUILD_PACKAGES = params.BUILD_TYPE in ['packages', 'both']
                    AWS_STASH_PATH = "${JIRA_TASK}/${BUILD_NUMBER}"
                    writeFile file: 'uploadPath', text: "UPLOAD/experimental/${AWS_STASH_PATH}"
                    stash includes: 'uploadPath', name: 'uploadPath'
                }
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting ${BUILD_TYPE} Debian 11 build of ${PSMDB_VERSION}-${PSMDB_RELEASE} - [${BUILD_URL}]")
            }
        }
        stage('Build') {
            parallel {
                stage('Debian Bullseye(11)(x86_64)') {
                    when {
                        expression { BUILD_PACKAGES }
                        beforeAgent true
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        script {
                            def vr = "${PSMDB_VERSION}-${PSMDB_RELEASE}"
                            fetchSources("source_deb", [
                                "debian/percona-server-mongodb_${vr}.dsc",
                                "debian/percona-server-mongodb_${PSMDB_VERSION}.orig.tar.gz",
                                "debian/percona-server-mongodb_${vr}.debian.tar.xz",
                                "debian/percona-server-mongodb_${vr}_source.changes",
                            ])
                        }
                        buildStage("debian:bullseye", "--build_deb=1")
                        // pushArtifactFolder swallows aws errors, so fail here rather than
                        // silently publishing an empty folder
                        sh 'ls -la deb/ && test $(ls -1 deb/*.bullseye_amd64.deb | wc -l) -gt 0'
                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11) binary tarball(glibc2.31)') {
                    when {
                        expression { BUILD_TARBALL }
                        beforeAgent true
                    }
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
                    }
                    steps {
                        cleanUpWS()
                        script {
                            fetchSources("source_tarball", ["tarball/percona-server-mongodb-${PSMDB_VERSION}-${PSMDB_RELEASE}.tar.gz"])
                        }
                        buildStage("debian:bullseye", "--build_tarball=1")
                        sh 'ls -la tarball/ && test $(ls -1 tarball/*.bullseye*.tar.gz | wc -l) -gt 0'
                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Upload tarball from S3') {
            when {
                expression { BUILD_TARBALL }
            }
            steps {
                uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
            }
        }
        stage('Upload and sign DEBs') {
            when {
                expression { BUILD_PACKAGES }
            }
            steps {
                uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                signDEB(params.CLOUD)
            }
        }
        stage('Push to TESTING download area') {
            steps {
                script {
                    try {
                        if (BUILD_TARBALL) {
                            uploadTarballToDownloadsTesting(params.CLOUD, "issue", "${JIRA_TASK}")
                        }
                        if (BUILD_PACKAGES) {
                            uploadDEBToDownloadsTesting(params.CLOUD, "issue", "${JIRA_TASK}")
                        }
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
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: ${BUILD_TYPE} Debian 11 build of ${PSMDB_VERSION}-${PSMDB_RELEASE} finished - [${BUILD_URL}]")
            script {
                currentBuild.description = "${PSMDB_VERSION}-${PSMDB_RELEASE}, Debian 11, ${BUILD_TYPE}. Downloads: TESTING/issue-${JIRA_TASK}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: ${BUILD_TYPE} Debian 11 build of ${PSMDB_VERSION}-${PSMDB_RELEASE} failed - [${BUILD_URL}]")
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
