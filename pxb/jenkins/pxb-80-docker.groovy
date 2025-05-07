library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
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
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
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
            choices: 'testing\nlaboratory\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Build docker containers') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                script {
                    if (env.FIPSMODE == 'YES') {
                        echo "The step is skipped"
                    } else {
                        echo "====> Build docker containers"
                        sh '''
                            sudo apt-get -y install apparmor
                            sudo aa-status
                            sudo systemctl stop apparmor
                            sudo systemctl disable apparmor
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
                            sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                            curl -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                            . ./XB_VERSION
                            curl -O https://raw.githubusercontent.com/percona/percona-server/refs/heads/${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}/MYSQL_VERSION
                            . ./MYSQL_VERSION
                            rm -rf percona-docker
                            git clone https://github.com/percona/percona-docker
                            if [ \${MYSQL_VERSION_MINOR} = "0" ]; then
                                cd percona-docker/percona-xtrabackup-8.0
                            else
                                cd percona-docker/percona-xtrabackup-8.x
                            fi
                            sed -i "s/ENV XTRABACKUP_VERSION.*/ENV XTRABACKUP_VERSION ${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.1/g" Dockerfile
                            if [ \${MYSQL_VERSION_MINOR} = "0" ]; then
                                sed -i "s/pxb-80 testing/pxb-80 ${COMPONENT}/g" Dockerfile
                            else
                                sed -i "s/pxb-84-lts testing/pxb-84-lts ${COMPONENT}/g" Dockerfile
                            fi
                            sudo docker build --no-cache -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --platform "linux/amd64" .
                            sudo docker build --no-cache -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --platform="linux/arm64" .

                            sudo docker images
                        '''
                        withCredentials([
                            usernamePassword(credentialsId: 'hub.docker.com',
                            passwordVariable: 'PASS',
                            usernameVariable: 'USER'
                            )]) {
                            sh '''
                                echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                                curl -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                                . ./XB_VERSION
                                sudo docker push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64

                                sudo docker manifest create perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} \
                                    perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 \
                                    perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                sudo docker manifest annotate perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                                sudo docker manifest annotate perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --os linux --arch amd64
                                sudo docker manifest inspect perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}

                                sudo docker manifest push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}
                                sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:latest perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                            '''
                           }
                    } // if
                } // scripts
            } // steps
        } // stage
        stage('Check by trivy') {
            agent {
               label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
            }
            steps {
                catchError {
                        sh '''
                            curl -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                            . ./XB_VERSION
                            sudo apt-get update
                            sudo apt-get -y install wget apt-transport-https gnupg lsb-release
                            wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | sudo apt-key add -
                            echo deb https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main | sudo tee -a /etc/apt/sources.list.d/trivy.list
                            sudo apt-get update
                            sudo apt-get -y install trivy
                            sudo trivy -q image --format table \
                                          --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 | tee -a trivy-hight-junit.xml
                            sudo trivy -q image --format table \
                                          --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 | tee -a trivy-hight-junit.xml
                        '''
                }
            }
        }
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
