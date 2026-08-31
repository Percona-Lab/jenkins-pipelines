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
            bash -x ./psmdb_builder.sh --builddir=\${build_dir}/test --psm_ver=${PSMDB_VERSION} --psm_release=${PSMDB_RELEASE} ${STAGE_PARAM}"
    """
}

// no shared-library equivalent for rpms, using the same helper as for the el9.x custom builds
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

                # flat file list, not the redhat/ tree: the downloads host does not
                # let you descend into subfolders, so <rhel>/<arch>/ dirs are a dead end
                ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                    rsync -avt -e '"ssh -p 2222"' --bwlimit=50000 --progress ${pathToBuild}/binary/redhat/*/*/*.rpm jenkins-deploy.jenkins-deploy.web.r.int.percona.com:\${target_dir}/

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
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-7.0/percona-server-mongodb-7.0.40-22/source/redhat/percona-server-mongodb-7.0.40-22.generic.src.rpm',
            description: 'URL of the released generic src.rpm to rebuild for el7',
            name: 'SRPM_URL')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for percona-server-mongodb repository to take BuildScript from',
            name: 'BS_GIT_REPO')
        string(
            defaultValue: 'release-7.0.40-22',
            description: 'Tag/Branch for percona-server-mongodb repository to take BuildScript from',
            name: 'BS_GIT_BRANCH')
        string(
            defaultValue: '7.0.40',
            description: 'PSMDB version value',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '22',
            description: 'PSMDB release value',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: 'CUSTOM259',
            description: 'Jira task name without dash(CUSTOM-259 -> CUSTOM259). Must be CUSTOM<digits>, otherwise issue-CUSTOM<digits> is not indexed on the downloads host',
            name: 'JIRA_TASK')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Build RPMs (CentOS 7, x86_64)') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${SRPM_URL} - [${BUILD_URL}]")
                cleanUpWS()
                sh """
                    set -o xtrace
                    mkdir -p srpm
                    curl -fL '${params.SRPM_URL}' -o srpm/\$(basename '${params.SRPM_URL}')
                    ls -la srpm/
                """
                buildStage("centos:7", "--build_rpm=1")
                script {
                    AWS_STASH_PATH = "${JIRA_TASK}/${BUILD_NUMBER}"
                    writeFile file: 'uploadPath', text: "UPLOAD/experimental/${AWS_STASH_PATH}"
                }
                sh '''
                    ls -la rpm/
                    test $(ls -1 rpm/*.el7.x86_64.rpm | wc -l) -gt 0
                '''
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
            }
        }
        stage('Upload packages from S3') {
            steps {
                uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
            }
        }
        stage('Sign packages') {
            steps {
                signRPM(params.CLOUD)
            }
        }
        stage('Push RPMs to TESTING download area') {
            steps {
                script {
                    try {
                        uploadRPMToDownloadsTesting(params.CLOUD, "issue", "${JIRA_TASK}")
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
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${SRPM_URL} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Rebuilt ${SRPM_URL} for el7. Downloads: TESTING/issue-${JIRA_TASK}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${SRPM_URL} - [${BUILD_URL}]")
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
