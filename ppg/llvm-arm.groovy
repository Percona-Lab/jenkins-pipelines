library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
	mkdir -p test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/llvm/llvm_builder.sh -O builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./builder.sh --builddir=\${build_dir}/test --rpm_release=${RPM_RELEASE} ${STAGE_PARAM}"
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
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/postgres-packaging.git',
            description: 'URL for llvm repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '16.3',
            description: 'Tag/Branch for postgresql packaging repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: 'ppg-16.3',
            description: 'PPG repo name',
            name: 'PPG_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Build LLVM RPMs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
			buildStage("oraclelinux:8", "--get_src_rpm=1")
			pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
			popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        sh '''
                            REPO_UPLOAD_PATH=$(grep "UPLOAD" test/llvm.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                            AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                            echo ${REPO_UPLOAD_PATH} > uploadPath
                            echo ${AWS_STASH_PATH} > awsUploadPath
                            cat test/llvm.properties
                            cat uploadPath
                           '''
			script {
                            AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                        }
                        stash includes: 'uploadPath', name: 'uploadPath'
                        buildStage("oraclelinux:8", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
			buildStage("oraclelinux:9", "--get_src_rpm=1")
                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        sh '''
                            REPO_UPLOAD_PATH=$(grep "UPLOAD" test/llvm.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                            AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                            echo ${REPO_UPLOAD_PATH} > uploadPath
                            echo ${AWS_STASH_PATH} > awsUploadPath
                            cat test/llvm.properties
                            cat uploadPath
                           '''
			script {
                            AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                        }
                        stash includes: 'uploadPath', name: 'uploadPath'
                        buildStage("oraclelinux:9", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
			buildStage("oraclelinux:10", "--get_src_rpm=1")
			pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
			popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        sh '''
                            REPO_UPLOAD_PATH=$(grep "UPLOAD" test/llvm.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                            AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                            echo ${REPO_UPLOAD_PATH} > uploadPath
                            echo ${AWS_STASH_PATH} > awsUploadPath
                            cat test/llvm.properties
                            cat uploadPath
                           '''
			script {
                            AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                        }
                        stash includes: 'uploadPath', name: 'uploadPath'
                        buildStage("oraclelinux:10", "--build_rpm=1")
                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
            }
        }

        stage('Sign packages') {
            steps {
                signRPM(params.CLOUD)
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild(params.CLOUD, PPG_REPO, COMPONENT)
            }
        }

    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
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
