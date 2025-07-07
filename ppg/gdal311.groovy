library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_IMAGE, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir -p test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/master/gdal311/builder.sh -O builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_IMAGE} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./builder.sh --builddir=\${build_dir}/test --rpm_release=${RPM_RELEASE} ${STAGE_PARAM}"
    """
}

void cleanUpWS() {
    sh "sudo rm -rf ./*"
}

def AWS_STASH_PATH

pipeline {
    agent none

    parameters {
        choice(name: 'CLOUD', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
        string(name: 'GIT_REPO', defaultValue: 'https://github.com/percona/postgres-packaging.git', description: 'URL for gdal311 repository')
        string(name: 'GIT_BRANCH', defaultValue: '16.3', description: 'Tag/Branch for postgresql packaging repository')
        string(name: 'RPM_RELEASE', defaultValue: '1', description: 'RPM release value')
        string(name: 'PPG_REPO', defaultValue: 'ppg-16.3', description: 'PPG repo name')
        choice(name: 'COMPONENT', choices: ['laboratory', 'testing', 'experimental'], description: 'Repo component to push packages to')
    }

    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }

    stages {
        stage('Build SRPM (generic)') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_src_rpm=1")
                pushArtifactFolder(params.CLOUD, "srpm/", "")
                popArtifactFolder(params.CLOUD, "srpm/", "")
                sh '''
                    REPO_UPLOAD_PATH=$(grep "UPLOAD" test/gdal311.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                    AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed "s:UPLOAD/experimental/::")
                    echo ${REPO_UPLOAD_PATH} > uploadPath
                    echo ${AWS_STASH_PATH} > awsUploadPath
                    cat test/gdal311.properties
                    cat uploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/**', name: 'srpmContents'
            }
        }
        stage('Build RPMs in Parallel') {
            parallel {
                stage('Build Oracle Linux 9 x86_64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'uploadPath'
                        unstash 'srpmContents'
                        script {
                            buildStage("oraclelinux:9", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }

                stage('Build Oracle Linux 9 aarch64') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'uploadPath'
                        unstash 'srpmContents'
                        script {
                            buildStage("oraclelinux:9", "--build_rpm=1")
                        }
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Sign packages') {
            agent { label 'docker' }
            steps {
                signRPM(params.CLOUD)
            }
        }

        stage('Push to public repository') {
            agent { label 'docker' }
            steps {
                sync2ProdAutoBuild(params.CLOUD, PPG_REPO, COMPONENT)
            }
        }
    }

    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}"
            }
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
        }
        always {
            cleanUpWS()
            deleteDir()
        }
    }
}