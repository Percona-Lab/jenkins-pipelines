/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field
void installCli(String PLATFORM) {
    sh """
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
    def aptNodes = ['min-bullseye-x64', 'min-bookworm-x64', 'min-jammy-x64', 'min-noble-x64']
    def yumNodes = ['min-ol-8-x64' , 'min-ol-9-x64']
    try{
        if (aptNodes.contains(nodeName)) {
            if(nodeName == "min-bullseye-x64" || nodeName == "min-bookworm-x64"){            
                sh '''
                    sudo apt-get update
                    sudo apt-get install -y ansible git wget
                '''
            }else if(nodeName == "min-jammy-x64" || nodeName == "min-noble-x64"){
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

            if(nodeName == "min-ol-9-x64"){            
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
        script {
            env.PS_RELEASE = sh(returnStdout: true, script: "echo ${BRANCH} | sed 's/release-//g'").trim()
            echo "PS_RELEASE : ${env.PS_RELEASE}"
            env.PS_VERSION_SHORT_KEY=  sh(script: """echo ${PS_RELEASE} | awk -F'.' '{print \$1 \".\" \$2}'""", returnStdout: true).trim()
            echo "Version is : ${env.PS_VERSION_SHORT_KEY}"
            env.PS_VERSION_SHORT = "PS${env.PS_VERSION_SHORT_KEY.replace('.', '')}"
            echo "Value is : ${env.PS_VERSION_SHORT}"
            echo "Using PS_VERSION_SHORT in another function: ${env.PS_VERSION_SHORT}"
            def playbook
            if (env.PS_VERSION_SHORT == 'PS80') {
                playbook = "ps_80.yml"
            } else {
                playbook = "ps_84.yml"
            }
            def client_to_test = PS_VERSION_SHORT
            def playbook_path = "package-testing/playbooks/${playbook}"
            sh '''
                set -xe
                git clone --depth 1 https://github.com/Percona-QA/package-testing
            '''
            def exitCode = sh(
                script: """
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
                """,
                returnStatus: true
            )
            if (exitCode != 0) {
                error "Ansible playbook failed on ${nodeName} with exit code ${exitCode}"
            }
        }
    }
def minitestNodes =   [  "min-bullseye-x64",
                         "min-bookworm-x64",
                         "min-ol-8-x64",
                         "min-jammy-x64",
                         "min-noble-x64",
                         "min-ol-9-x64"]

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
def docker_test() {
    def stepsForParallel = [:] 
        stepsForParallel['Run for ARM64'] = {
            node('docker-32gb-aarch64') {
                stage("Docker tests for ARM64") {
                    script{
                        sh '''
                            echo "running test for ARM"
                            export DOCKER_PLATFORM=linux/arm64
                            # disable THP on the host for TokuDB
                            echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                            echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                            chmod +x disable_thp.sh
                            sudo ./disable_thp.sh
                            # run test
                            export PATH=${PATH}:~/.local/bin
                            sudo yum install -y python3 python3-pip
                            rm -rf package-testing
                            git clone https://github.com/Percona-QA/package-testing.git --depth 1
                            cd package-testing/docker-image-tests/ps-arm
                            pip3 install --user -r requirements.txt
                            export PS_VERSION="${PS_RELEASE}-arm64"
                            echo "printing variables: \$DOCKER_ACC , \$PS_VERSION , \$PS_REVISION "
                            ./run.sh
                        '''
                    }
                }
                stage('Docker image version check for ARM64'){
                    script{
                        sh '''
                            export PS_VERSION="${PS_RELEASE}-arm64"
                            fetched_docker_version=$(docker run -i --rm -e MYSQL_ROOT_PASSWORD=asdasd ${DOCKER_ACC}/percona-server:${PS_VERSION} \
                                bash -c "mysql --version" | awk '{print $3}')
                            echo "fetching docker version: \$fetched_docker_version"
                            if [[ "$PS_RELEASE" == "$fetched_docker_version" ]]; then 
                                echo "Run succesfully for arm"
                            else 
                                echo "Failed for arm"
                            fi
                        '''
                    }
                }
                stage('Run trivy analyzer ARM64') {
                    script{
                        sh """
                            sudo yum install -y curl wget git
                            TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                            ARCH=\$(uname -m)
                            if [[ "\$ARCH" == "aarch64" ]]; then
                                ARCH_NAME="ARM64"
                            elif [[ "\$ARCH" == "x86_64" ]]; then
                                ARCH_NAME="64bit"
                            else
                                echo "Unsupported architecture: \$ARCH"
                                exit 1
                            fi
                            echo "Detected architecture: \$ARCH, using Trivy for Linux-\$ARCH_NAME"
                            wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz
                            sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-\${ARCH_NAME}.tar.gz -C /usr/local/bin/
                            wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                            /usr/local/bin/trivy image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                            --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/percona-server:${PS_RELEASE}-arm64 || true
                            echo "Ran succesfully for arm"
                        """
                    }
                }
            }
        }
        stepsForParallel['Run for AMD'] = {
            node ( 'docker' ) {
                stage("Docker image version check for AMD") {
                    script {
                         sh '''
                                echo "running the test for AMD" 
                                # disable THP on the host for TokuDB
                                echo "echo never > /sys/kernel/mm/transparent_hugepage/enabled" > disable_thp.sh
                                echo "echo never > /sys/kernel/mm/transparent_hugepage/defrag" >> disable_thp.sh
                                chmod +x disable_thp.sh
                                sudo ./disable_thp.sh
                                # run test
                                export PATH=${PATH}:~/.local/bin
                                sudo yum install -y python3 python3-pip
                                rm -rf package-testing
                                git clone https://github.com/Percona-QA/package-testing.git --depth 1
                                cd package-testing/docker-image-tests/ps
                                pip3 install --user -r requirements.txt
                                export PS_VERSION="${PS_RELEASE}-amd64"
                                echo "printing variables: \$DOCKER_ACC , \$PS_VERSION ,\$PS_REVISION "
                                ./run.sh
                            ''' 
                        }
                    }
                stage ("Docker image version check for amd64") {
                    script{
                        sh '''
                            export PS_VERSION="${PS_RELEASE}-amd64"
                            fetched_docker_version=$(docker run -i --rm -e MYSQL_ROOT_PASSWORD=asdasd ${DOCKER_ACC}/percona-server:${PS_VERSION} \
                                bash -c "mysql --version" | awk '{print $3}')
                            echo "fetching docker version: \$fetched_docker_version"
                            if [[ "$PS_RELEASE" == "$fetched_docker_version" ]]; then 
                                echo "Run succesfully for amd"
                            else 
                                echo "Failed for amd"
                            fi 
                        '''
                    }
                }
                stage ('Run trivy analyzer for AMD') {
                    script {
                        sh """
                            sudo yum install -y curl wget git
                            TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                            wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                            sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                            wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                            /usr/local/bin/trivy image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                            --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/percona-server:${PS_RELEASE}-amd64 || true
                            echo "ran succesfully for amd docker trivy"
                        """        
                    }
                }
            }  
        }
    parallel stepsForParallel
}

@Field def mini_test_error = "False"
def AWS_STASH_PATH
def product_to_test = ''
def install_repo = 'testing'
def action_to_test = 'install'
def check_warnings = 'yes'
def install_mysql_shell = 'no'

pipeline {
    agent {
        label 'docker'
    }
parameters {
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
            choices: 'laboratory\ntesting\nexperimental\nrelease',
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
        stage('Preparation') {
            steps {
                script {
                    env.DOCKER_ACC= 'perconalab'
                    env.PS_RELEASE = sh(script: "echo ${BRANCH} | sed 's/release-//g'", returnStdout: true).trim()
                    echo "PS_RELEASE: ${env.PS_RELEASE}"
                    env.PS_VERSION_SHORT_KEY = "${env.PS_RELEASE}".split('\\.')[0..1].join('.')
                    echo "PS_VERSION_SHORT_KEY: ${env.PS_VERSION_SHORT_KEY}"
                    env.PS_VERSION_SHORT = "PS${env.PS_VERSION_SHORT_KEY.replace('.', '')}"
                    echo "PS_VERSION_SHORT: ${env.PS_VERSION_SHORT}"
                    if (env.PS_VERSION_SHORT == 'PS84') {
                        product_to_test = 'ps_84'
                    } 
                    else {
                        product_to_test = 'ps_80'
                    }
                    echo "Product to test is: ${product_to_test}"
                }
            }
        }
        stage('Create PS source tarball') {
            agent {
               label 'min-focal-x64'
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
                pushArtifactFolder("source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS("source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PS generic source packages') {
            parallel {
                stage('Build PS generic source rpm') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--build_src_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--build_src_rpm=1")
                            }
                        }

                        pushArtifactFolder("srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS("srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PS generic source deb') {
                    agent {
                        label 'min-buster-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--build_source_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--build_source_deb=1")
                            }
                        }

                        pushArtifactFolder("source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS("source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PS RPMs/DEBs/Binary tarballs') {
            parallel {
                stage('Centos 7') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES' || env.ENABLE_EL7 == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("none", "--build_rpm=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("none", "--build_rpm=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Centos 8 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("centos:8", "--build_rpm=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--build_rpm=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--build_rpm=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("srpm/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("oraclelinux:9", "--build_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("oraclelinux:9", "--build_rpm=1")
                            }
                        }

                        pushArtifactFolder("rpm/", AWS_STASH_PATH)
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
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
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
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("srpm/", AWS_STASH_PATH)
                                buildStage("amazonlinux:2023", "--build_rpm=1 --enable_fipsmode=1")

                                pushArtifactFolder("rpm/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04)') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("deb")
                                unstash 'properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("none", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04)') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04)') {
                    agent {
                        label 'min-noble-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Debian Bullseye(11)') {
                    agent {
                        label 'min-bullseye-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("deb")
                                unstash 'properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("none", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12)') {
                    agent {
                        label 'min-bookworm-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
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
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("ubuntu:focal", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:jammy", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:jammy", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Noble(24.04) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:noble", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:noble", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
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
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_deb/", AWS_STASH_PATH)
                                buildStage("debian:bullseye", "--build_deb=1 --with_zenfs=1")

                                pushArtifactFolder("deb/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Debian Bookworm(12) ARM') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_deb/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("debian:bookworm", "--build_deb=1 --with_zenfs=1 --enable_fipsmode=1")
                            } else {
                                buildStage("debian:bookworm", "--build_deb=1 --with_zenfs=1")
                            }
                        }

                        pushArtifactFolder("deb/", AWS_STASH_PATH)
                    }
                }
                stage('Centos 7 binary tarball') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES' || env.ENABLE_EL7 == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("none", "--build_tarball=1")

                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Centos 7 debug tarball') {
                    agent {
                        label 'min-centos-7-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES' || env.ENABLE_EL7 == 'NO') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("none", "--debug=1 --build_tarball=1")

                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 binary tarball') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("none", "--build_tarball=1")

                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 8 debug tarball') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("rpm")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("none", "--debug=1 --build_tarball=1")

                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 tarball') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--build_tarball=1")
                            }
                        }

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Oracle Linux 9 ZenFS tarball') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                buildStage("none", "--build_tarball=1 --with_zenfs=1")
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Oracle Linux 9 debug tarball') {
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("rpm")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--debug=1 --build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--debug=1 --build_tarball=1")
                            }
                        }

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Focal(20.04) tarball') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("deb")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("none", "--build_tarball=1")

                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Focal(20.04) debug tarball') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps {
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                cleanUpWS()
                                installCli("deb")
                                unstash 'properties'
                                popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                                buildStage("none", "--debug=1 --build_tarball=1")

                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) tarball') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--build_tarball=1")
                            }
                        }

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
                stage('Ubuntu Jammy(22.04) ZenFS tarball') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                echo "The step is skipped"
                            } else {
                                buildStage("none", "--build_tarball=1 --with_zenfs=1")
                                pushArtifactFolder("tarball/", AWS_STASH_PATH)
                            }
                        }
                    }
                }
                stage('Ubuntu Jammy(22.04) debug tarball') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        unstash 'properties'
                        popArtifactFolder("source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("none", "--debug=1 --build_tarball=1 --enable_fipsmode=1")
                            } else {
                                buildStage("none", "--debug=1 --build_tarball=1")
                            }
                        }

                        pushArtifactFolder("tarball/", AWS_STASH_PATH)
                    }
                }
            }
        }
        stage('Upload packages and tarballs from S3') {
            agent {
                label 'min-jammy-x64'
            }
            steps {
                cleanUpWS()
                installCli("deb")
                unstash 'properties'

                uploadRPMfromAWS("rpm/", AWS_STASH_PATH)
                uploadDEBfromAWS("deb/", AWS_STASH_PATH)
                uploadTarballfromAWS("tarball/", AWS_STASH_PATH, 'binary')
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
                            sync2PrivateProdAutoBuild("ps-80-pro", COMPONENT)
                        } else {
                            sync2ProdAutoBuild("ps-80", COMPONENT)
                        }
                    } else {
                        if (env.FIPSMODE == 'YES') {
                            if ("${MYSQL_VERSION_MINOR}" == "4") {
                                sync2PrivateProdAutoBuild("ps-84-pro", COMPONENT)
                            } else {
                                sync2PrivateProdAutoBuild("ps-8x-innovation-pro", COMPONENT)
                            }
                        } else {
                            if ("${MYSQL_VERSION_MINOR}" == "4") {
                                sync2ProdAutoBuild("ps-84-lts", COMPONENT)
                            } else {
                                sync2ProdAutoBuild("ps-8x-innovation", COMPONENT)
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
                            uploadTarballToDownloadsTesting("ps-gated", "${BRANCH}")
                        } else {
                            uploadTarballToDownloadsTesting("ps", "${BRANCH}")
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
                label 'min-focal-x64'
            }
            steps {
                script {
                    if (env.FIPSMODE == 'YES') {
                        echo "The step is skipped"
                    } else {
                        echo "====> Build docker container"
                        cleanUpWS()
                        installCli("deb")
                        sh '''
                           sleep 1200
                        '''
                        unstash 'properties'
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
                            sudo apt-get install -y docker.io
                            sudo systemctl status docker
                            sudo apt-get install -y qemu binfmt-support qemu-user-static
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
                            sudo docker build -t perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 --platform="linux/amd64" .
                            sudo docker build -t perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 --platform="linux/arm64" -f Dockerfile.aarch64 .
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
                           PS_MAJOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}')
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
                           PS_MAJOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}')
                           echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                           PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                           sudo docker manifest push perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t perconalab/percona-server:${PS_RELEASE} perconalab/percona-server:${PS_RELEASE}.${RPM_RELEASE}
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
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: PRO build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
                }
            } 
            slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: Triggering Builds for Package Testing for ${BRANCH} - [${BUILD_URL}]")
            unstash 'properties'
            script {
                currentBuild.description = "Built on ${BRANCH}; path to packages: ${COMPONENT}/${AWS_STASH_PATH}"
                env.PS_REVISION = sh(returnStdout: true, script: "grep REVISION test/percona-server-8.0.properties | awk -F '=' '{ print\$2 }'").trim()
                sh "cat test/percona-server-8.0.properties"
                echo "Revision is: ${env.PS_REVISION}"
                echo "PS_RELEASE is: ${PS_RELEASE}"
                echo "PS_VERSION_SHORT_KEY is: ${PS_VERSION_SHORT_KEY}"
                echo "Value is : ${PS_VERSION_SHORT}"
                echo "DOCKER account is : ${DOCKER_ACC}"

                if (env.product_to_test == 'PS80') {
                    echo "Running PS80-specific steps"
                } else if (env.product_to_test == 'PS84') {
                    echo "Running PS84-specific steps"
                } else {
                    echo "Running client test"
                }
                 if("${PS_VERSION_SHORT}"){
                    echo "Executing MINITESTS as VALID VALUES FOR PS8_RELEASE_VERSION:${PS_VERSION_SHORT}"
                    echo "Checking for the Github Repo VERSIONS file changes..."
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
                    sh """
                        set -x
                        git clone https://jenkins-pxc-cd:$TOKEN@github.com/Percona-QA/package-testing.git
                        cd package-testing
                        git config user.name "jenkins-pxc-cd"
                        git config user.email "it+jenkins-pxc-cd@percona.com"
                        git checkout master
                        echo "${PS_VERSION_SHORT} is the VALUE!!@!"
                        export RELEASE_VER_VAL="${PS_VERSION_SHORT}"
                        if [[ "\$RELEASE_VER_VAL" =~ ^PS8[0-9]{1}\$ ]]; then
                            echo "\$RELEASE_VER_VAL is a valid version"
                            OLD_REV=\$(cat VERSIONS | grep ${PS_VERSION_SHORT}_REV | cut -d '=' -f2- )
                            echo "OLD_REV is : \${OLD_REV}"
                            OLD_VER=\$(cat VERSIONS | grep ${PS_VERSION_SHORT}_VER | cut -d '=' -f2- )
                            echo "OLD_VER is : \${OLD_VER}"
                            sed -i s/${PS_VERSION_SHORT}_REV=\$OLD_REV/${PS_VERSION_SHORT}_REV='"'${PS_REVISION}'"'/g VERSIONS
                            sed -i s/${PS_VERSION_SHORT}_VER=\$OLD_VER/${PS_VERSION_SHORT}_VER='"'${PS_RELEASE}'"'/g VERSIONS
                            echo 

                        else
                            echo "INVALID PS8_RELEASE_VERSION VALUE: ${PS_VERSION_SHORT}"
                        fi
                        git diff
                        if [[ -z \$(git diff) ]]; then
                            echo "No changes"
                        else
                            echo "There are changes"
                            git add -A
                        git commit -m "Autocommit: add ${PS_REVISION} and ${PS_RELEASE} for ${PS_VERSION_SHORT} package testing VERSIONS file."
                            git push
                        fi
                    """
                    }
                    parallel(
                        "Start Minitests for PS": {
                             try {
                                package_tests_ps80(minitestNodes)
                                echo "Minitests completed successfully. Triggering next stages."
                                slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: minitest sucessfully run for ${BRANCH} - [${BUILD_URL}]")
                                echo "TRIGGERING THE PACKAGE TESTING JOB!!!"
                                build job: 'ps-package-testing-molecule', propagate: false, wait: false, parameters: [string(name: 'product_to_test', value: "${product_to_test}"),string(name: 'install_repo', value: "testing"),string(name: 'action_to_test', value: "install"),string(name: 'check_warnings', value: "yes"),string(name: 'install_mysql_shell', value: "no")]
                                echo "Trigger PMM_PS Github Actions Workflow"
                                withCredentials([string(credentialsId: 'Github_Integration', variable: 'Github_Integration')]) {
                                    sh """
                                    curl -i -v -X POST \
                                    -H "Accept: application/vnd.github.v3+json" \
                                    -H "Authorization: token ${Github_Integration}" \
                                    "https://api.github.com/repos/Percona-Lab/qa-integration/actions/workflows/PMM_PS.yaml/dispatches" \
                                    -d '{"ref":"main","inputs":{"ps_version":"${PS_RELEASE}"}}'
                                    """ 
                                    }
                                slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: PMM sucessfully run for ${BRANCH} - [${BUILD_URL}]")
                            } catch (err) {
                                    echo " Minitests block failed: ${err}"
                                    currentBuild.result = 'FAILURE'
                                    throw err
                                }
                        },
                        "Start docker job": {
                            try {
                                docker_test()
                                echo "DOCKER images run successfully."
                            }   catch (err) {
                                echo "Docker test block failed: ${err}"
                                currentBuild.result = 'FAILURE'
                                throw err
                            }
                            }
                    )          
                }    
                        else{
                            error "Skipping MINITESTS and Other Triggers as invalid RELEASE VERSION FOR THIS JOB"
                            slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: Skipping MINITESTS and Other Triggers as invalid RELEASE VERSION FOR THIS JOB ${BRANCH} - [${BUILD_URL}]")
                        }
                    }
                        deleteDir()
        }
        failure {
            slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${BRANCH}"
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                if (env.FIPSMODE == 'YES') {
                    currentBuild.description = "PRO -> Built on ${BRANCH} - packages [${COMPONENT}/${AWS_STASH_PATH}]"
                } else {
                    currentBuild.description = "Built on ${BRANCH} - packages [${COMPONENT}/${AWS_STASH_PATH}]"
                }
            }
            deleteDir()
        }
    }
}