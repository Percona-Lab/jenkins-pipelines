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
        string(defaultValue: 'release-8.0.28-19', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version', name: 'DEB_RELEASE')
        choice(
            choices: 'NO\nYES',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'testing\nexperimental\nrelease',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases-ci\n#releases',
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
                            if [ ${PS_MAJOR_RELEASE} != "80" ]; then
                                sed -i "s/ENV MYSQL_SHELL_VERSION.*/ENV MYSQL_SHELL_VERSION ${MYSQL_SHELL_RELEASE}-${RPM_RELEASE}/g" Dockerfile
                            fi
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
            deleteDir()
        }
    }
}
