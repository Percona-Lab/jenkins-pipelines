library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
      sh """
          set -o xtrace
          mkdir test
          if [ \${FIPSMODE} = "YES" ]; then
              MYSQL_VERSION_MINOR=\$(curl -s -O \$(echo \${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/\${BRANCH}/MYSQL_VERSION && grep MYSQL_VERSION_MINOR MYSQL_VERSION | awk -F= '{print \$2}')
              if [ \${MYSQL_VERSION_MINOR} = "0" ]; then
                  PRO_BRANCH="8.0"
              elif [ \${MYSQL_VERSION_MINOR} = "4" ]; then
                  PRO_BRANCH="8.4"
              else
                  PRO_BRANCH="trunk"
              fi
              curl -L -H "Authorization: Bearer \${TOKEN}" \
                      -H "Accept: application/vnd.github.v3.raw" \
                      -o percona-xtrabackup-8.0_builder.sh \
                      "https://api.github.com/repos/percona/percona-xtrabackup-private-build/contents/percona-xtrabackup-8.0_builder.sh?ref=\${PRO_BRANCH}"
          else
              wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/storage/innobase/xtrabackup/utils/percona-xtrabackup-8.0_builder.sh -O percona-xtrabackup-8.0_builder.sh
          fi
          pwd -P
          export build_dir=\$(pwd -P)
          docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
              set -o xtrace
              cd \${build_dir}
              bash -x ./percona-xtrabackup-8.0_builder.sh --builddir=\${build_dir}/test --install_deps=1
              if [ \${FIPSMODE} = "YES" ]; then
                  git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-xtrabackup-private-build.git percona-xtrabackup-private-build
                  mv -f \${build_dir}/percona-xtrabackup-private-build \${build_dir}/test/.
                  ls \${build_dir}/test
              fi
              bash -x ./percona-xtrabackup-8.0_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}"
      """
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

def installDependencies(def nodeName) {
    def aptNodes = ['min-bullseye-x64', 'min-bookworm-x64', 'min-focal-x64', 'min-jammy-x64']
    def yumNodes = ['min-ol-8-x64', 'min-centos-7-x64', 'min-ol-9-x64', 'min-amazon-2-x64']
    try{
        if (aptNodes.contains(nodeName)) {
            if(nodeName == "min-bullseye-x64" || nodeName == "min-bookworm-x64"){            
                sh '''
                    sudo apt-get update
                    sudo apt-get install -y ansible git wget
                '''
            }else if(nodeName == "min-focal-x64" || nodeName == "min-jammy-x64"){
                sh '''
                    sudo apt-get update
                    sudo apt-get install -y software-properties-common
                    sudo apt-add-repository --yes --update ppa:ansible/ansible
                    sudo apt-get install -y ansible git wget
                '''
            }else {
                error "Node Not Listed in APT"
            }
        } else if (yumNodes.contains(nodeName)) {

            if(nodeName == "min-centos-7-x64" || nodeName == "min-ol-9-x64"){            
                sh '''
                    sudo yum install -y epel-release
                    sudo yum -y update
                    sudo yum install -y ansible git wget tar
                '''
            }else if(nodeName == "min-ol-8-x64"){
                sh '''
                    sudo yum install -y epel-release
                    sudo yum -y update
                    sudo yum install -y ansible-2.9.27 git wget tar
                '''
            }else if(nodeName == "min-amazon-2-x64"){
                sh '''
                    sudo amazon-linux-extras install epel
                    sudo yum -y update
                    sudo yum install -y ansible git wget
                '''
            }
            else {
                error "Node Not Listed in YUM"
            }
        } else {
            echo "Unexpected node name: ${nodeName}"
        }
    } catch (Exception e) {
        slackNotify( "#FF0000", "[${JOB_NAME}]: Server Provision for Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!")
    }

}

def runPlaybook(def nodeName) {

    try {
        def playbook = "pxb_innovation_lts.yml"
        def playbook_path = "package-testing/playbooks/${playbook}"

        sh '''
            set -xe
            git clone --depth 1 -b master https://github.com/Percona-QA/package-testing
        '''
        sh """
            set -xe
            export install_repo="\${install_repo}"
            export client_to_test="ps80"
            export check_warning="\${check_warnings}"
            export install_mysql_shell="\${install_mysql_shell}"
            ansible-playbook \
            --connection=local \
            --inventory 127.0.0.1, \
            --limit 127.0.0.1 \
            ${playbook_path}
        """
    } catch (Exception e) {
        slackNotify( "#FF0000", "[${JOB_NAME}]: Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!!")
        mini_test_error="True"
    }
}

def minitestNodes = [  "min-bullseye-x64",
                       "min-bookworm-x64",
                       "min-centos-7-x64",
                       "min-ol-8-x64",
                       "min-focal-x64",
                       "min-jammy-x64",
                       "min-ol-9-x64"     ]

def package_tests_pxb(def nodes) {
    def stepsForParallel = [:]
    for (int i = 0; i < nodes.size(); i++) {
        def nodeName = nodes[i]
        stepsForParallel[nodeName] = {
            stage("Minitest run on ${nodeName}") {
                node(nodeName) {
                        installDependencies(nodeName)
                        runPlaybook(nodeName)
                }
            }
        }
    }
    parallel stepsForParallel
}

@Field def mini_test_error = "False"
def product_to_test = "pxb_innovation_lts"
def install_repo = "testing"
def git_repo = "https://github.com/Percona-QA/package-testing.git"


pipeline {
    agent {
        label 'docker-32gb'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-xtrabackup.git',
            description: 'URL for PXB git repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '9.0',
            description: 'Tag/Branch for PXB repository',
            name: 'BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        choice(
            choices: 'NO\nYES',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'YES\nNO',
            description: 'Experimental packages only',
            name: 'EXPERIMENTALMODE')
        choice(
            choices: 'experimental\nlaboratory\ntesting',
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
            steps {
                // slackNotify("", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:focal", "--get_sources=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:focal", "--get_sources=1")
                            }
                       }
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
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("centos:7", "--build_src_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("centos:7", "--build_src_rpm=1")
                            }
                        }

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PXB generic source deb') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:focal", "--build_source_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:focal", "--build_source_deb=1")
                            }
                        }

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        }

        stage('Build PXB RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Oracle Linux 8') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--build_rpm=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                    uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                                }
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--build_rpm=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                    uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                                }
                            }
                        }
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("srpm/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') { 
                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                } 
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("srpm/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Amazon Linux 2023') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Amazon Linux 2023 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_deb=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("deb/", AWS_STASH_PATH)
                                    uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                                }
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_deb=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("deb/", AWS_STASH_PATH)
                                    uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                                }
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1")
                            }

                            pushArtifactFolder("deb/", AWS_STASH_PATH)
                            uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_deb=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("deb/", AWS_STASH_PATH)
                                    uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                                }
                            }
                        }
                    }
                }
                stage('Debian Bullseye(11) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_deb=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("deb/", AWS_STASH_PATH)
                                    uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                                }
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_deb/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--build_tarball=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                    uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                                }
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_tarball=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                    uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                                }
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                            }
                        }
                    }
                }
                stage('Ubuntu Noble(24.04) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_tarball=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                            }
                        }
                    }
                }
                stage('Debian Bullseye(11) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_tarball=1")

                                if (env.EXPERIMENTALMODE == 'NO') {
                                    pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                    uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                                }
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12) tarball') {
                    agent {
                        label 'docker-32gb'
                    }
                    steps {
                        script {
                            cleanUpWS()
                            popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_tarball=1")
                            }

                            if (env.EXPERIMENTALMODE == 'NO') {
                                pushArtifactFolder("test/tarball/", AWS_STASH_PATH)
                                uploadTarballfromAWS("test/tarball/", AWS_STASH_PATH, 'binary')
                            }
                        }
                    }
                }
            }
        }

        stage('Sign packages') {
            steps {
                script {
                    if (env.EXPERIMENTALMODE == 'NO') {
                        signRPM()
                    }
                }
                signDEB()
            }
        }
        
        stage('Push to public repository') {
            steps {
                script {
                    MYSQL_VERSION_MINOR = sh(returnStdout: true, script: ''' curl -s -O $(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git$||')/${BRANCH}/MYSQL_VERSION; cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print $2}' ''').trim()
                    PS_MAJOR_RELEASE = sh(returnStdout: true, script: ''' echo ${BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}' ''').trim()
                    // sync packages
                    if ("${MYSQL_VERSION_MINOR}" == "0") {
                        if (env.FIPSMODE == 'YES') {
                            sync2PrivateProdAutoBuild("pxb-90-pro", COMPONENT)
                        } else {
                            sync2ProdAutoBuild("pxb-90", COMPONENT)
                        }
                    } else {
                        if (env.FIPSMODE == 'YES') {
                            if ("${MYSQL_VERSION_MINOR}" == "7") {
                                sync2PrivateProdAutoBuild("pxb-97-pro", COMPONENT)
                            } else {
                                sync2PrivateProdAutoBuild("pxb-9x-innovation-pro", COMPONENT)
                            }
                        } else {
                            if ("${MYSQL_VERSION_MINOR}" == "7") {
                                sync2ProdAutoBuild("pxb-97-lts", COMPONENT)
                            } else {
                                sync2ProdAutoBuild("pxb-9x-innovation", COMPONENT)
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
            unstash 'uploadPath'
            script {
                if (env.FIPSMODE == 'YES') {
                    currentBuild.description = "PRO -> Built on ${BRANCH}; path to packages: [${COMPONENT}/${AWS_STASH_PATH}]"
                } else {
                    currentBuild.description = "Built on ${BRANCH}; path to packages: [${COMPONENT}/${AWS_STASH_PATH}]"
                }
            }
            deleteDir()
        }
        failure {
           // slackNotify("", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH} - [${BUILD_URL}]")
            script {
                if (env.FIPSMODE == 'YES') {
                    currentBuild.description = "PRO -> Built on ${BRANCH}; path to packages: [${COMPONENT}/${AWS_STASH_PATH}]"
                } else {
                    currentBuild.description = "Built on ${BRANCH}; path to packages: [${COMPONENT}/${AWS_STASH_PATH}]"
                }
            }
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
