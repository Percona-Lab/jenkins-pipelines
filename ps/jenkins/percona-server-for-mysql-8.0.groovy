/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

void installCli(String PLATFORM) {
    sh """
        if [ \${CLOUD} = "AWS" ]; then
            set -o xtrace
            if [ -d aws ]; then
                rm -rf aws
            fi
            if [ ${PLATFORM} = "deb" ]; then
                sudo apt-get update
                sudo apt-get -y install wget curl unzip
            elif [ ${PLATFORM} = "rpm" ]; then
                export RHVER=\$(rpm --eval %rhel)
                if [ \${RHVER} = "7" ]; then
                    sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* || true
                    sudo sed -i 's|#\\s*baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-* || true
                    if [ -e "/etc/yum.repos.d/CentOS-SCLo-scl.repo" ]; then
                        cat /etc/yum.repos.d/CentOS-SCLo-scl.repo
                    fi
                fi
                sudo yum -y install wget curl unzip
            fi
            curl https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o awscliv2.zip
            unzip awscliv2.zip
            sudo ./aws/install || true
       fi
    """
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
      sh """
          set -o xtrace
          mkdir -p test
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
                      -o ps_builder.sh \
                      "https://api.github.com/repos/percona/percona-server-private-build/contents/build-ps/percona-server-8.0_builder.sh?ref=\${PRO_BRANCH}"
              sed -i 's|percona-server-server/usr|percona-server-server-pro/usr|g' ps_builder.sh
              sed -i 's|dbg-package=percona-server-dbg|dbg-package=percona-server-pro-dbg|g' ps_builder.sh
          else
              wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -O ps_builder.sh || curl \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/build-ps/percona-server-8.0_builder.sh -o ps_builder.sh
          fi
          grep "percona-server-server" ps_builder.sh
          export build_dir=\$(pwd -P)
          if [ "$DOCKER_OS" = "none" ]; then
              set -o xtrace
              cd \${build_dir}
              if [ \${FIPSMODE} = "YES" ]; then
                  git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-server-private-build.git percona-server-private-build
                  mv -f \${build_dir}/percona-server-private-build/build-ps \${build_dir}/test/.
              fi
              if [ -f ./test/percona-server-8.0.properties ]; then
                  . ./test/percona-server-8.0.properties
              fi
              sudo bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
              if [ ${BUILD_TOKUDB_TOKUBACKUP} = "ON" ]; then
                  bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
              else
                  bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
              fi
          else
              docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                  set -o xtrace
                  cd \${build_dir}
                  if [ \${FIPSMODE} = "YES" ]; then
                      git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-server-private-build.git percona-server-private-build
                      mv -f \${build_dir}/percona-server-private-build/build-ps \${build_dir}/test/.
                  fi
                  if [ -f ./test/percona-server-8.0.properties ]; then
                      . ./test/percona-server-8.0.properties
                  fi
                  bash -x ./ps_builder.sh --builddir=\${build_dir}/test --install_deps=1
                  if [ ${BUILD_TOKUDB_TOKUBACKUP} = \"ON\" ]; then
                      bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --build_tokudb_tokubackup=1 --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                  else
                      bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --perconaft_branch=${PERCONAFT_BRANCH} --tokubackup_branch=${TOKUBACKUP_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                  fi"
          fi
      """
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def installDependencies(def nodeName) {
    def aptNodes = ['min-bullseye-x64', 'min-bookworm-x64', 'min-focal-x64', 'min-jammy-x64', 'min-noble-x64']
    def yumNodes = ['min-ol-8-x64', 'min-centos-7-x64', 'min-ol-9-x64', 'min-amazon-2-x64']
    try{
        if (aptNodes.contains(nodeName)) {
            if(nodeName == "min-bullseye-x64" || nodeName == "min-bookworm-x64"){            
                sh '''
                    sudo apt-get update
                    sudo apt-get install -y ansible git wget
                '''
            }else if(nodeName == "min-focal-x64" || nodeName == "min-jammy-x64" || nodeName == "min-noble-x64"){
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
        slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: Server Provision for Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!")
    }

}

def runPlaybook(def nodeName) {

    try {
        def playbook = "ps_lts_innovation.yml"
        def playbook_path = "package-testing/playbooks/${playbook}"

        sh '''
            set -xe
            git clone --depth 1 https://github.com/Percona-QA/package-testing
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
        slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: Mini Package Testing for ${nodeName} at ${BRANCH}  FAILED !!!")
        mini_test_error="True"
    }
}

def minitestNodes = [  "min-bullseye-x64",
                       "min-bookworm-x64",
                       "min-centos-7-x64",
                       "min-ol-8-x64",
                       "min-focal-x64",
                       "min-amazon-2-x64",
                       "min-jammy-x64",
                       "min-noble-x64",
                       "min-ol-9-x64"     ]

def package_tests_ps80(def nodes) {
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
def AWS_STASH_PATH
def PS8_RELEASE_VERSION
def product_to_test = 'innovation-lts'
def install_repo = 'testing'
def action_to_test = 'install'
def check_warnings = 'yes'
def install_mysql_shell = 'no'

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
parameters {
    choice(
         choices: [ 'Hetzner','AWS' ],
         description: 'Cloud infra for build',
         name: 'CLOUD' )
        string(defaultValue: 'https://github.com/percona/percona-server.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'release-8.0.28-19', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version', name: 'DEB_RELEASE')
        choice(
            choices: 'OFF\nON',
            description: 'The TokuDB storage is no longer supported since 8.0.28',
            name: 'BUILD_TOKUDB_TOKUBACKUP')
        string(defaultValue: '0', description: 'PerconaFT repository', name: 'PERCONAFT_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for PerconaFT repository', name: 'PERCONAFT_BRANCH')
        string(defaultValue: '0', description: 'TokuBackup repository', name: 'TOKUBACKUP_REPO')
        string(defaultValue: 'Percona-Server-8.0.27-18', description: 'Tag/Branch for TokuBackup repository', name: 'TOKUBACKUP_BRANCH')
        choice(
            choices: 'NO\nYES',
            description: 'Prepare packages and tarballs for Centos 7',
            name: 'ENABLE_EL7')
        choice(
            choices: 'ON\nOFF',
            description: 'Compile with ZenFS support?, only affects Ubuntu Hirsute',
            name: 'ENABLE_ZENFS')
        choice(
            choices: 'NO\nYES',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'testing\nlaboratory\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases\n#releases-ci',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {

        stage('Create PS source tarball') {
            agent {
               label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
            }
            steps {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("deb")
                script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--get_sources=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--get_sources=1")
                            }
                       }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "UPLOAD" test/percona-server-8.0.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/percona-server-8.0.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: 'test/percona-server-8.0.properties', name: 'properties'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PS generic source packages') {
            parallel {
                stage('Build PS generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("centos:7", "--build_src_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("centos:7", "--build_src_rpm=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PS generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:focal", "--build_source_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:focal", "--build_source_deb=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PS RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Centos 7') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES' || env.ENABLE_EL7 == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                                buildStage("centos:7", "--build_rpm=1")

                                pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--build_rpm=1")

                                pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Centos 8 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                                buildStage("centos:8", "--build_rpm=1")

                                pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 10') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:10", "--build_rpm=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:10", "--build_rpm=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Amazon Linux 2023') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")

                                pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Amazon Linux 2023 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")

                                pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 binary tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES' || env.ENABLE_EL7 == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                                buildStage("centos:7", "--build_tarball=1")

                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Centos 7 debug tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES' || env.ENABLE_EL7 == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                                buildStage("centos:7", "--debug=1 --build_tarball=1")

                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 binary tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--build_tarball=1")

                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 debug tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                                buildStage("oraclelinux:8", "--debug=1 --build_tarball=1")

                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ZenFS tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                buildStage("oraclelinux:9", "--build_tarball=1 --with_zenfs=1")
                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 debug tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--debug=1 --build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--debug=1 --build_tarball=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04) tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_tarball=1")

                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04) debug tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--debug=1 --build_tarball=1")

                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) ZenFS tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                buildStage("ubuntu:jammy", "--build_tarball=1 --with_zenfs=1")
                                pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) debug tarball') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--debug=1 --build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--debug=1 --build_tarball=1")
                            }
                        }

                        pushArtifactFolder(params.CLOUD, "tarball/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Upload packages and tarballs from S3') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                cleanUpWS()
                installCli("rpm")
                unstash 'properties'

                uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
            }
        }

        stage('Sign packages') {
            steps {
                signRPM()
                signDEB()
            }
        }
        stage('Push to public repository') {
            steps {
                unstash 'properties'
                script {
                    MYSQL_VERSION_MINOR = sh(returnStdout: true, script: ''' curl -s -O $(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git$||')/${BRANCH}/MYSQL_VERSION; cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print $2}' ''').trim()
                    PS_MAJOR_RELEASE = sh(returnStdout: true, script: ''' echo ${BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}' ''').trim()
                    // sync packages
                    if ("${MYSQL_VERSION_MINOR}" == "0") {
                        if (env.FIPSMODE == 'YES') {
                            sync2PrivateProdAutoBuild(params.CLOUD, "ps-80-pro", COMPONENT)
                        } else {
                            sync2ProdAutoBuild(params.CLOUD, "ps-80", COMPONENT)
                        }
                    } else {
                        if (env.FIPSMODE == 'YES') {
                            if ("${MYSQL_VERSION_MINOR}" == "4") {
                                sync2PrivateProdAutoBuild(params.CLOUD, "ps-84-pro", COMPONENT)
                            } else {
                                sync2PrivateProdAutoBuild(params.CLOUD, "ps-8x-innovation-pro", COMPONENT)
                            }
                        } else {
                            if ("${MYSQL_VERSION_MINOR}" == "4") {
                                sync2ProdAutoBuild(params.CLOUD, "ps-84-lts", COMPONENT)
                            } else {
                                sync2ProdAutoBuild(params.CLOUD, "ps-8x-innovation", COMPONENT)
                            }
                        }
                    }
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            steps {
                script {
                    try {
                        if (env.FIPSMODE == 'YES') {
                            uploadTarballToDownloadsTesting(params.CLOUD, "ps-gated", "${BRANCH}")
                        } else {
                            uploadTarballToDownloadsTesting(params.CLOUD, "ps", "${BRANCH}")
                        }
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Build docker containers') {
            agent {
                label 'launcher-x64'
            }
            steps {
                script {
                    if (env.FIPSMODE == 'YES') {
                        echo "The step is skipped"
                    } else {
                        echo "====> Build docker container"
                        sh '''
                            PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                            PS_MAJOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}')
                            if [ ${PS_MAJOR_RELEASE} != "80" ]; then
                                MYSQL_SHELL_RELEASE=$(echo ${BRANCH} | sed 's/release-//g' | awk '{print substr($0, 0, 6)}' | sed 's/-//g')
                                MYSQL_ROUTER_RELEASE=$(echo ${BRANCH} | sed 's/release-//g' | awk '{print substr($0, 0, 6)}' | sed 's/-//g')
                            else
                                MYSQL_SHELL_RELEASE=$(echo ${BRANCH} | sed 's/release-//g' | awk '{print substr($0, 0, 7)}' | sed 's/-//g')
                                MYSQL_ROUTER_RELEASE=$(echo ${BRANCH} | sed 's/release-//g' | awk '{print substr($0, 0, 7)}' | sed 's/-//g')
                            fi
                            sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                            sudo apt-get -y install apparmor
                            sudo aa-status
                            sudo systemctl stop apparmor
                            sudo systemctl disable apparmor
                            sudo apt-get install -y docker-ce docker-ce-cli containerd.io
                            export DOCKER_CLI_EXPERIMENTAL=enabled
                            sudo mkdir -p /usr/libexec/docker/cli-plugins/
                            sudo curl -L https://github.com/docker/buildx/releases/download/v0.21.2/buildx-v0.21.2.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            sudo apt-get install -y qemu-system binfmt-support qemu-user-static
                            sudo qemu-system-x86_64 --version
                            sudo lscpu | grep -q 'sse4_2' && grep -q 'popcnt' /proc/cpuinfo && echo "Supports x86-64-v2" || echo "Does NOT support x86-64-v2"
                            sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                            git clone https://github.com/percona/percona-docker
                            cd percona-docker/percona-server-8.0
                            sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${PS_RELEASE}.${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PS_TELEMETRY_VERSION.*/ENV PS_TELEMETRY_VERSION ${PS_RELEASE}-${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV MYSQL_SHELL_VERSION.*/ENV MYSQL_SHELL_VERSION ${MYSQL_SHELL_RELEASE}-${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PS_REPO .*/ENV PS_REPO testing/g" Dockerfile
                            if [ ${PS_MAJOR_RELEASE} != "80" ]; then
                                if [ ${PS_MAJOR_RELEASE} = "84" ]; then
                                    sed -i "s/percona-release enable ps-80/percona-release enable ps-84-lts/g" Dockerfile
                                else
                                    sed -i "s/percona-release enable ps-80/percona-release enable ps-8x-innovation/g" Dockerfile
                                fi
                                sed -i "s/percona-release enable mysql-shell/PS_REPO=\"testing\";percona-release enable mysql-shell/g" Dockerfile
                            fi
                            sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${PS_RELEASE}.${RPM_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PS_TELEMETRY_VERSION.*/ENV PS_TELEMETRY_VERSION ${PS_RELEASE}-${RPM_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PS_REPO .*/ENV PS_REPO testing/g" Dockerfile.aarch64
                            if [ ${PS_MAJOR_RELEASE} != "80" ]; then
                                if [ ${PS_MAJOR_RELEASE} = "84" ]; then
                                    sed -i "s/percona-release enable ps-80/percona-release enable ps-84-lts/g" Dockerfile.aarch64
                                else
                                    sed -i "s/percona-release enable ps-80/percona-release enable ps-8x-innovation/g" Dockerfile.aarch64
                                fi
                                sed -i "s/percona-release enable mysql-shell/PS_REPO=\"testing\";percona-release enable mysql-shell/g" Dockerfile.aarch64
                            fi
                            sudo docker build -t perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 --progress plain --platform="linux/amd64" .
                            sudo docker buildx build --platform linux/arm64 -t perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 --load -f Dockerfile.aarch64 .
                            cd ../mysql-router
                            sed -i "s/ENV ROUTE_VERSION.*/ENV ROUTE_VERSION ${PS_RELEASE}.${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV MYSQL_SHELL_VERSION.*/ENV MYSQL_SHELL_VERSION ${MYSQL_SHELL_RELEASE}-${RPM_RELEASE}/g" Dockerfile
                            if [ ${PS_MAJOR_RELEASE} != "80" ]; then
                                if [ ${PS_MAJOR_RELEASE} = "84" ]; then
                                    sed -i "s/percona-release enable ps-80 testing/percona-release enable ps-84-lts testing/g" Dockerfile
                                else
                                    sed -i "s/percona-release enable ps-80 testing/percona-release enable ps-8x-innovation testing/g" Dockerfile
                                fi
                            fi
                            sudo docker build -t perconalab/percona-mysql-router:${MYSQL_ROUTER_RELEASE}-amd64 --platform="linux/amd64" .
                            sudo docker build -t perconalab/percona-mysql-router:${MYSQL_ROUTER_RELEASE}-arm64 --platform="linux/arm64" .
                            sudo docker tag perconalab/percona-mysql-router:${MYSQL_ROUTER_RELEASE}-amd64 perconalab/percona-mysql-router:${MYSQL_ROUTER_RELEASE}
                            sudo docker images
                        '''
                        withCredentials([
                        usernamePassword(credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS',
                        usernameVariable: 'USER'
                        )]) {
                        sh '''
                            echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                            PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                            PS_MAJOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | awk '{print substr($0, 0, 3)}')
                            if [ ${PS_MAJOR_RELEASE} != "80" ]; then
                                MYSQL_ROUTER_RELEASE=$(echo ${BRANCH} | sed 's/release-//g' | awk '{print substr($0, 0, 6)}' | sed 's/-//g')
                            else
                                MYSQL_ROUTER_RELEASE=$(echo ${BRANCH} | sed 's/release-//g' | awk '{print substr($0, 0, 7)}' | sed 's/-//g')
                            fi
                            sudo docker tag perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 perconalab/percona-server:${PS_RELEASE}-amd64
                            sudo docker push perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64
                            sudo docker push perconalab/percona-server:${PS_RELEASE}-amd64
                            sudo docker tag perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 perconalab/percona-server:${PS_RELEASE}-arm64
                            sudo docker push perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64
                            sudo docker push perconalab/percona-server:${PS_RELEASE}-arm64
                            sudo docker tag perconalab/percona-mysql-router:${MYSQL_ROUTER_RELEASE} perconalab/percona-mysql-router:${PS_MAJOR_RELEASE}
                            sudo docker push perconalab/percona-mysql-router:${MYSQL_ROUTER_RELEASE}-amd64
                            sudo docker push perconalab/percona-mysql-router:${MYSQL_ROUTER_RELEASE}-arm64
                            sudo docker push perconalab/percona-mysql-router:${MYSQL_ROUTER_RELEASE}
                            sudo docker push perconalab/percona-mysql-router:${PS_MAJOR_RELEASE}
                       '''
                       }
                       sh '''
                           PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                           sudo docker manifest create perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE} \
                               perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 \
                               perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64
                           sudo docker manifest annotate perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE} perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                           sudo docker manifest annotate perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE} perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 --os linux --arch amd64
                           sudo docker manifest inspect perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}
                       '''
                       withCredentials([
                       usernamePassword(credentialsId: 'hub.docker.com',
                       passwordVariable: 'PASS',
                       usernameVariable: 'USER'
                       )]) {
                       sh '''
                           PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                           PS_MAJOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | awk '{print substr($0, 0, 3)}')
                           PS_MAJOR_FULL_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | sed "s/-.*//g")
                           echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                           sudo docker manifest push perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t perconalab/percona-server:${PS_RELEASE} perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t perconalab/percona-server:${PS_MAJOR_FULL_RELEASE} perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t perconalab/percona-server:${PS_MAJOR_RELEASE} perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}
                       '''
                       }
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                if (env.FIPSMODE == 'YES') {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: PRO -> build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
                }
            }
            slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: Triggering Builds for Package Testing for ${BRANCH} - [${BUILD_URL}]")
            unstash 'properties'
            script {
                currentBuild.description = "Built on ${BRANCH}; path to packages: ${COMPONENT}/${AWS_STASH_PATH}"
                REVISION = sh(returnStdout: true, script: "grep REVISION test/percona-server-8.0.properties | awk -F '=' '{ print\$2 }'").trim()
                PS_RELEASE = sh(returnStdout: true, script: "echo ${BRANCH} | sed 's/release-//g'").trim()
                PS8_RELEASE_VERSION = sh(returnStdout: true, script: """ echo ${BRANCH} | sed -nE '/release-(8\\.[0-9]{1})\\..*/s//\\1/p' """).trim()

                if("${PS8_RELEASE_VERSION}"){
                    echo "Executing MINITESTS as VALID VALUES FOR PS8_RELEASE_VERSION:${PS8_RELEASE_VERSION}"
                    echo "Checking for the Github Repo VERSIONS file changes..."
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                    sh """
                        set -x
                        git clone https://jenkins-pxc-cd:$TOKEN@github.com/Percona-QA/package-testing.git
                        cd package-testing
                        git config user.name "jenkins-pxc-cd"
                        git config user.email "it+jenkins-pxc-cd@percona.com"
                        echo "${PS8_RELEASE_VERSION} is the VALUE!!@!"
                        export RELEASE_VER_VAL="${PS8_RELEASE_VERSION}"
                        if [[ "\$RELEASE_VER_VAL" =~ ^8.[0-9]{1}\$ ]]; then
                            echo "\$RELEASE_VER_VAL is a valid version"
                            OLD_REV=\$(cat VERSIONS | grep PS_INN_LTS_REV | cut -d '=' -f2- )
                            OLD_VER=\$(cat VERSIONS | grep PS_INN_LTS_VER | cut -d '=' -f2- )
                            sed -i s/PS_INN_LTS_REV=\$OLD_REV/PS_INN_LTS_REV='"'${REVISION}'"'/g VERSIONS
                            sed -i s/PS_INN_LTS_VER=\$OLD_VER/PS_INN_LTS_VER='"'${PS_RELEASE}'"'/g VERSIONS

                        else
                            echo "INVALID PS8_RELEASE_VERSION VALUE: ${PS8_RELEASE_VERSION}"
                        fi
                        git diff
                        if [[ -z \$(git diff) ]]; then
                            echo "No changes"
                        else
                            echo "There are changes"
                            git add -A
                        git commit -m "Autocommit: add ${REVISION} and ${PS_RELEASE} for ${PS8_RELEASE_VERSION} package testing VERSIONS file."
                            git push
                        fi
                    """
                    }
                    echo "Start Minitests for PS"                
                    package_tests_ps80(minitestNodes)
                    if("${mini_test_error}" == "True"){
                        error "NOT TRIGGERING PACKAGE TESTS AND INTEGRATION TESTS DUE TO MINITEST FAILURE !!"
                    }else{
                        echo "TRIGGERING THE PACKAGE TESTING JOB!!!"
                        build job: 'package-testing-ps-innovation-lts', propagate: false, wait: false, parameters: [string(name: 'product_to_test', value: "${product_to_test}"),string(name: 'install_repo', value: "testing"),string(name: 'node_to_test', value: "all"),string(name: 'action_to_test', value: "all"),string(name: 'check_warnings', value: "yes"),string(name: 'install_mysql_shell', value: "no")]
                                                                                                                                            
                        echo "Trigger PMM_PS Github Actions Workflow"
                        
                        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                            sh """
                                curl -i -v -X POST \
                                    -H "Accept: application/vnd.github.v3+json" \
                                    -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                    "https://api.github.com/repos/Percona-Lab/qa-integration/actions/workflows/PMM_PS.yaml/dispatches" \
                                    -d '{"ref":"main","inputs":{"ps_version":"${PS_RELEASE}"}}'
                            """
                        }

                    }
                }
                else{
                    error "Skipping MINITESTS and Other Triggers as invalid RELEASE VERSION FOR THIS JOB"
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: Skipping MINITESTS and Other Triggers as invalid RELEASE VERSION FOR THIS JOB ${BRANCH} - [${BUILD_URL}]")
                }
            }
            deleteDir()
        }
        failure {
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                if (env.FIPSMODE == 'YES') {
                    currentBuild.description = "Pro -> Build on ${BRANCH}"
                } else {
                    currentBuild.description = "Build on ${BRANCH}"
                }
            }
            deleteDir()
        }
    }
}
