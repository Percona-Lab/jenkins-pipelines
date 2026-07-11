/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/adivinho/jenkins-pipelines.git'
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
          wget --header="Authorization: token ${TOKEN}" --header="Accept: application/vnd.github.v3.raw" -O ps_builder.sh \$(echo ${GIT_REPO} | sed -re 's|github.com|api.github.com/repos|; s|\\.git\$||')/contents/build-ps/percona-server-8.0_builder.sh?ref=${BRANCH}
          sed -i "s|git clone --depth 1 --branch \\\$BRANCH \\\"\\\$REPO\\\"|git clone --depth 1 --branch \\\$BRANCH \$(echo ${GIT_REPO}| sed -re 's|github.com|${TOKEN}@github.com|') percona-server|g" ps_builder.sh
          ls -la
          grep "git clone" ps_builder.sh
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
                  bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
          else
              docker run -u root --shm-size=16g --cap-add=SYS_NICE -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
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
                  bash -x ./ps_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} ${STAGE_PARAM}
                  "
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
                    export install_mysql_shell="${env.INSTALL_MYSQL_SHELL}"
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
def minitestNodes = [  "min-bullseye-x64",
                       "min-bookworm-x64",
                       "min-ol-8-x64",
                       "min-amazon-2-x64",
                       "min-jammy-x64",
                       "min-noble-x64",
                       "min-ol-9-x64" ,  
                         ]

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
                            export PS_REVISION="${PS_REVISION}"
                            export DOCKER_ACC="${DOCKER_ACC}"
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
                        """
                        installTrivy(method: 'binary', junitTpl: true)
                        sh """
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
                                export PS_REVISION="${PS_REVISION}"
                                export DOCKER_ACC="${DOCKER_ACC}"
                                echo "printing variables: \$DOCKER_ACC , \$PS_VERSION , \$PS_REVISION "
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
                        """
                        installTrivy(method: 'binary', junitTpl: true)
                        sh """
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
def BRANCH_NAME = env.BRANCH ?: "release-8.0.43-34"
def PS_RELEASE = BRANCH_NAME.replaceAll("release-", "")
def PS_VERSION_SHORT_KEY = PS_RELEASE.tokenize('.').take(2).join('.')
def PS_VERSION_SHORT = "PS${PS_VERSION_SHORT_KEY.replace('.', '')}"
def DOCKER_ACC = "perconalab"
product_to_test = (PS_VERSION_SHORT == 'PS84') ? 'ps_84' : 'ps_80'
env.PS_RELEASE = PS_RELEASE
env.PS_VERSION_SHORT_KEY = PS_VERSION_SHORT_KEY
env.PS_VERSION_SHORT = PS_VERSION_SHORT
env.DOCKER_ACC = DOCKER_ACC
env.product_to_test = product_to_test

void notifyBuildSuccess() {
    if (env.FIPSMODE == 'YES') {
        slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: PRO -> build finished successfully for ${BRANCH} - [${BUILD_URL}]")
    } else {
        slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build finished successfully for ${BRANCH} - [${BUILD_URL}]")
    }
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker-32gb'
    }
    environment {
        INSTALL_MYSQL_SHELL = "${params.install_mysql_shell ?: 'no'}"
    }
parameters {
    choice(
         choices: [ 'Hetzner','AWS' ],
         description: 'Cloud infra for build',
         name: 'CLOUD' )
        string(defaultValue: 'https://github.com/percona/percona-server-private.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'release-8.0.46-38', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version', name: 'DEB_RELEASE')
        choice(
            choices: 'ON\nOFF',
            description: 'Compile with ZenFS support?, only affects Ubuntu Hirsute',
            name: 'ENABLE_ZENFS')
        choice(
            choices: 'NO\nYES',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'testing\nlaboratory\nexperimental\nrelease',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases-ci\n#releases',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
        string(
            defaultValue: '',
            description: 'Comma-separated list of build stages to run (e.g. "Oracle Linux 9,Oracle Linux 9 ARM"). Leave empty to run all stages.',
            name: 'BUILD_STAGES')
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
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                installCli("rpm")
                script {
                            buildStage("ubuntu:focal", "--get_sources=1")
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
                script {
                    sh """
                        curl -s \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${BRANCH}/MYSQL_VERSION -o MYSQL_VERSION
                    """
                    env.MYSQL_VERSION_MAJOR = sh(returnStdout: true, script: "grep '^MYSQL_VERSION_MAJOR=' MYSQL_VERSION | cut -d= -f2").trim()
                    env.MYSQL_VERSION_MINOR = sh(returnStdout: true, script: "grep '^MYSQL_VERSION_MINOR=' MYSQL_VERSION | cut -d= -f2").trim()
                    echo "Detected Percona Server version: ${env.MYSQL_VERSION_MAJOR}.${env.MYSQL_VERSION_MINOR}"
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
                            buildStage("oraclelinux:8", "--build_src_rpm=1")
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
                            buildStage("ubuntu:focal", "--build_source_deb=1")
                        }

                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PS RPMs/DEBs/Binary tarballs') {
            steps {
                script {
                    ps80BuildMatrix(
                        cloud: params.CLOUD,
                        awsStashPath: AWS_STASH_PATH,
                        fipsMode: env.FIPSMODE,
                        onlyStages: params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    )
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

                script {
                    def rpmStages = [
                        'Oracle Linux 8', 'Centos 8 ARM', 'Oracle Linux 9', 'Oracle Linux 9 ARM',
                        'Oracle Linux 10', 'Oracle Linux 10 ARM', 'Amazon Linux 2023', 'Amazon Linux 2023 ARM'
                    ]
                    def requestedStages = params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    if (!requestedStages || requestedStages.any { rpmStages.contains(it) }) {
                        uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    }
                }
                script {
                    def debStages = [
                        'Ubuntu Jammy(22.04)', 'Ubuntu Noble(24.04)', 'Ubuntu Resolute(26.04)',
                        'Debian Bullseye(11)', 'Debian Bookworm(12)', 'Debian Trixie(13)',
                        'Ubuntu Jammy(22.04) ARM', 'Ubuntu Noble(24.04) ARM', 'Ubuntu Resolute(26.04) ARM',
                        'Debian Bullseye(11) ARM', 'Debian Bookworm(12) ARM', 'Debian Trixie(13) ARM'
                    ]
                    def requestedStages = params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    if (!requestedStages || requestedStages.any { debStages.contains(it) }) {
                        uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                    }
                }
                script {
                    def tarballStages = [
                        'Oracle Linux 8 binary tarball', 'Oracle Linux 8 debug tarball',
                        'Oracle Linux 9 tarball', 'Oracle Linux 9 ZenFS tarball', 'Oracle Linux 9 debug tarball',
                        'Ubuntu Focal(20.04) tarball', 'Ubuntu Focal(20.04) debug tarball',
                        'Ubuntu Jammy(22.04) tarball', 'Ubuntu Jammy(22.04) ZenFS tarball', 'Ubuntu Jammy(22.04) debug tarball'
                    ]
                    def requestedStages = params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    if (!requestedStages || requestedStages.any { tarballStages.contains(it) }) {
                        uploadTarballfromAWS(params.CLOUD, "tarball/", AWS_STASH_PATH, 'binary')
                    }
                }
            }
        }

        stage('Sign packages') {
            steps {
                script {
                    def rpmStages = [
                        'Oracle Linux 8', 'Centos 8 ARM', 'Oracle Linux 9', 'Oracle Linux 9 ARM',
                        'Oracle Linux 10', 'Oracle Linux 10 ARM', 'Amazon Linux 2023', 'Amazon Linux 2023 ARM'
                    ]
                    def debStages = [
                        'Ubuntu Jammy(22.04)', 'Ubuntu Noble(24.04)', 'Ubuntu Resolute(26.04)',
                        'Debian Bullseye(11)', 'Debian Bookworm(12)', 'Debian Trixie(13)',
                        'Ubuntu Jammy(22.04) ARM', 'Ubuntu Noble(24.04) ARM', 'Ubuntu Resolute(26.04) ARM',
                        'Debian Bullseye(11) ARM', 'Debian Bookworm(12) ARM', 'Debian Trixie(13) ARM'
                    ]
                    def requestedStages = params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    if (!requestedStages || requestedStages.any { rpmStages.contains(it) }) {
                        signRPM()
                    }
                    if (!requestedStages || requestedStages.any { debStages.contains(it) }) {
                        signDEB()
                    }
                }
            }
        }
        stage('Push to public repository') {
            steps {
                unstash 'properties'
                script {
                    sync2PrivateProdAutoBuild(params.CLOUD, "ps-80-eol", COMPONENT)
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            when {
                expression { !params.BUILD_STAGES || params.BUILD_STAGES.split(',').any { it.trim().toLowerCase().contains('tarball') } }
            }
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "ps-gated", "${BRANCH}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Build docker container') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'min-jammy-x64'
            }
            steps {
                script {
                    cleanUpWS()
                    installCli("deb")
                    unstash 'uploadPath'
                    def path_to_build = sh(returnStdout: true, script: "cat uploadPath").trim()
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com:${path_to_build}/binary/redhat/9/x86_64/*.rpm /tmp
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com:${path_to_build}/binary/redhat/9/aarch64/*.rpm /tmp
                            ls -la /tmp
                        """
                    }
                    sh '''
                        REPO_DOCKER="https://github.com/adivinho/percona-docker"
                        REPO_DOCKER_BRANCH="PXB-3744-Packaging-tasks-for-release-PXB-9.7.1-rc1"
                        PS_RELEASE=$(echo ${BRANCH} | sed 's/release-//g')
                        PS_MAJOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | awk '{print substr($0, 0, 4)}')
                        PS_MAJOR_MINOR_RELEASE=$(echo ${BRANCH} | sed "s/release-//g" | awk '{print substr($0, 0, 7)}' | sed "s/-//g")

                        sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                        sudo apt-get -y install apparmor
                        sudo aa-status
                        sudo systemctl stop apparmor
                        sudo systemctl disable apparmor
                        sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
                        sudo echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
                        sudo apt-get update
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

                        git clone ${REPO_DOCKER}
                        cd percona-docker/percona-server-8.0
                        git checkout ${REPO_DOCKER_BRANCH}
                        mv /tmp/*.rpm .
                        sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${PS_RELEASE}.${RPM_RELEASE}/g" Dockerfile-pro
                        sed -i "s/ENV PS_TELEMETRY_VERSION.*/ENV PS_TELEMETRY_VERSION ${PS_RELEASE}-${RPM_RELEASE}/g" Dockerfile-pro
                        sudo docker builder prune -af
                        sudo docker build --provenance=false -t percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 --progress plain --platform="linux/amd64" -f Dockerfile-pro .
                        sudo docker buildx build --provenance=false --platform linux/arm64 -t percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 --load -f Dockerfile.aarch64-pro .
                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 percona/percona-server:${PS_RELEASE}
                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 percona/percona-server:${PS_MAJOR_RELEASE}
                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 percona/percona-server:${PS_MAJOR_MINOR_RELEASE}
                        sudo docker images
                        sudo docker save -o percona-server-${PS_RELEASE}-${RPM_RELEASE}-amd64.docker.tar percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64 percona/percona-server:${PS_RELEASE} percona/percona-server:${PS_MAJOR_RELEASE} percona/percona-server:${PS_MAJOR_MINOR_RELEASE}

                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 percona/percona-server:${PS_RELEASE}
                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 percona/percona-server:${PS_MAJOR_RELEASE}
                        sudo docker tag percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 percona/percona-server:${PS_MAJOR_MINOR_RELEASE}
                        sudo docker images
                        sudo docker save -o percona-server-${PS_RELEASE}-${RPM_RELEASE}-arm64.docker.tar percona/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64 percona/percona-server:${PS_RELEASE} percona/percona-server:${PS_MAJOR_RELEASE} percona/percona-server:${PS_MAJOR_MINOR_RELEASE}

                        sudo addgroup admin
                        sudo useradd -m -s /bin/bash -g admin -G admin admin
                        sudo chown admin:admin percona-server-${PS_RELEASE}-${RPM_RELEASE}-amd64.docker.tar
                        sudo chown admin:admin percona-server-${PS_RELEASE}-${RPM_RELEASE}-arm64.docker.tar
                        sudo chmod a+r percona-server-${PS_RELEASE}-${RPM_RELEASE}-amd64.docker.tar
                        sudo chmod a+r percona-server-${PS_RELEASE}-${RPM_RELEASE}-arm64.docker.tar
                        ls -la
                    '''
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            cd percona-docker/percona-server-8.0
                            export PS_RELEASE=`echo ${BRANCH} | sed 's/release-//g'`
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} percona-server-\${PS_RELEASE}-${RPM_RELEASE}-amd64.docker.tar ${USER}@repo.ci.percona.com:${path_to_build}/binary/tarball
                            scp -o StrictHostKeyChecking=no -i ${KEY_PATH} percona-server-\${PS_RELEASE}-${RPM_RELEASE}-arm64.docker.tar ${USER}@repo.ci.percona.com:${path_to_build}/binary/tarball
                        """
                    }
               }
            }
        } 
    } 
    post {
        success {
            script {
                notifyBuildSuccess()
            }
        }

        failure {
            deleteDir()
        }

        always {
            sh 'sudo rm -rf ./*'
            script {
                currentBuild.description = "Build on ${BRANCH}"
            }
            deleteDir()
        }
    }
}
