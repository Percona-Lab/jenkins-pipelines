library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _


void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        mkdir test
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/storage/innobase/xtrabackup/utils/percona-xtrabackup-8.0_builder.sh -O percona-xtrabackup-8.0_builder.sh
        pwd -P
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./percona-xtrabackup-8.0_builder.sh --builddir=\${build_dir}/test --install_deps=1
            bash -x ./percona-xtrabackup-8.0_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --pxb_repo=${PXB_REPO} --rpm_release=${RPM_RELEASE} ${STAGE_PARAM}"
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
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-xtrabackup.git',
            description: 'URL for PXB git repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for PXB repository',
            name: 'BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        choice(
            choices: 'pxb-80\npxb-8x-innovation\npxb-8x-lts\npxb-9x-innovation\npxb-9x-lts',
            description: 'PXB repo name',
            name: 'PXB_REPO')
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
        stage('Create PXB source tarball') {
            agent {
               label 'docker-32gb-aarch64'
            } 
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                buildStage("oraclelinux:8", "--get_sources=1")
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-xtrabackup-8.0.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-xtrabackup-8.0.properties
                   cat uploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                    XB_VERSION_MAJOR = sh(returnStdout: true, script: "grep 'XB_VERSION_MAJOR' ./test/percona-xtrabackup-8.0.properties | cut -d = -f 2 ").trim()
                    XB_VERSION_MINOR = sh(returnStdout: true, script: "grep 'XB_VERSION_MINOR' ./test/percona-xtrabackup-8.0.properties | cut -d = -f 2 ").trim()
                    XB_VERSION_PATCH = sh(returnStdout: true, script: "grep 'XB_VERSION_PATCH' ./test/percona-xtrabackup-8.0.properties | cut -d = -f 2 ").trim()
                    XB_VERSION_EXTRA = sh(returnStdout: true, script: "grep 'XB_VERSION_EXTRA' ./test/percona-xtrabackup-8.0.properties | cut -d = -f 2 | sed 's/-//g'").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }

        stage('Build PXB generic source packages') {
            parallel {
                stage('Build PXB generic source rpm') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_src_rpm=1")

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        }

        stage('Build PXB RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:8", "--build_rpm=1")

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        buildStage("oraclelinux:9", "--build_rpm=1")
            
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
                sync2ProdAutoBuild(PXB_REPO, COMPONENT)
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${BRANCH}"
            }
            deleteDir()
        }
        failure {
           slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH} - [${BUILD_URL}]")
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
