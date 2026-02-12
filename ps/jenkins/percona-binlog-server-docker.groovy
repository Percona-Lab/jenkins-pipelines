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
        choice(
            choices: 'perconalab\npercona',
            description: 'Organization on hub.docker.com',
            name: 'ORGANIZATION')
        string(defaultValue: 'https://github.com/percona/percona-docker', description: 'Dockerfiles source', name: 'REPO_DOCKER')
        string(defaultValue: 'main', description: 'Tag/Branch for percona-docker repository', name: 'REPO_DOCKER_BRANCH')
        string(defaultValue: '0.1.0', description: 'PBS Version', name: 'VERSION')
        string(defaultValue: '1', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '1', description: 'DEB version', name: 'DEB_RELEASE')
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
                        sh '''
                            Dockerfile="Dockerfile"
                            sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common
                            sudo apt-get -y install apparmor
                            sudo aa-status
                            sudo systemctl stop apparmor
                            sudo systemctl disable apparmor
                            sudo apt-get install -y docker-ce docker-ce-cli containerd.io
                            export DOCKER_CLI_EXPERIMENTAL=enabled
                            sudo mkdir -p /usr/libexec/docker/cli-plugins/
                            sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            sudo apt-get install -y qemu-system binfmt-support qemu-user-static
                            sudo qemu-system-x86_64 --version
                            sudo lscpu | grep -q 'sse4_2' && grep -q 'popcnt' /proc/cpuinfo && echo "Supports x86-64-v2" || echo "Does NOT support x86-64-v2"
                            sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                            rm -rf percona-docker
                            git clone ${REPO_DOCKER}
                            cd percona-docker
                            git checkout ${REPO_DOCKER_BRANCH}
                            cd percona-binlog-server
                            sed -i "s/ENV PBS_VERSION.*/ENV PBS_VERSION ${VERSION}-${RPM_RELEASE}.el9/g" ${Dockerfile}
                            sudo docker --version
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker builder prune -af
                                sudo docker build --provenance=false -t perconalab/percona-binlog-server:${VERSION}-${RPM_RELEASE}-amd64 --progress plain --platform="linux/amd64" -f ${Dockerfile} .
                                sudo docker buildx build --provenance=false --platform linux/arm64 -t perconalab/percona-binlog-server:${VERSION}-${RPM_RELEASE}-arm64 --load -f ${Dockerfile} .
                            else
                                sudo docker pull perconalab/percona-binlog-server:${VERSION}-${RPM_RELEASE}-amd64
                                sudo docker tag perconalab/percona-binlog-server:${VERSION}-${RPM_RELEASE}-amd64 percona/percona-binlog-server:${VERSION}-${RPM_RELEASE}-amd64
                                sudo docker pull perconalab/percona-binlog-server:${VERSION}-${RPM_RELEASE}-arm64
                                sudo docker tag perconalab/percona-binlog-server:${VERSION}-${RPM_RELEASE}-arm64 percona/percona-binlog-server:${VERSION}-${RPM_RELEASE}-arm64
                            fi
                            sudo docker images
                        '''
                        withCredentials([
                        usernamePassword(credentialsId: 'hub.docker.com',
                        passwordVariable: 'PASS',
                        usernameVariable: 'USER'
                        )]) {
                        sh '''
                            echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                            sudo docker tag ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}-amd64 ${ORGANIZATION}/percona-server:${VERSION}-amd64
                            sudo docker push ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}-amd64
                            sudo docker push ${ORGANIZATION}/percona-server:${VERSION}-amd64
                            sudo docker tag ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}-arm64 ${ORGANIZATION}/percona-server:${VERSION}-arm64
                            sudo docker push ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}-arm64
                            sudo docker push ${ORGANIZATION}/percona-server:${VERSION}-arm64
                       '''
                       }
                       sh '''
                           sudo docker manifest create --amend ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE} \
                               ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}-amd64 \
                               ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}-arm64
                           sudo docker manifest annotate ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE} ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                           sudo docker manifest annotate ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE} ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}-amd64 --os linux --arch amd64
                           sudo docker manifest inspect ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}
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
                           sudo docker manifest push ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}
                           sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-server:${VERSION} ${ORGANIZATION}/percona-server:${VERSION}-${RPM_RELEASE}
                       '''
                       }
                }
            }
        }
    stage('Check by Trivy') {
        agent {
           label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
        }
        environment {
            TRIVY_LOG = "trivy-high-junit.xml"
        }
        steps {
            script {
                try {
                    // 🔹 Install Trivy if not present
                    sh '''
                        if ! command -v trivy &> /dev/null; then
                            echo "🔄 Installing Trivy..."
                            sudo apt-get update
                            sudo apt-get -y install wget apt-transport-https gnupg lsb-release
                            wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | sudo apt-key add -
                            echo deb https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main | sudo tee -a /etc/apt/sources.list.d/trivy.list
                            sudo apt-get update
                            sudo apt-get -y install trivy
                        else
                            echo "✅ Trivy is already installed."
                        fi
                    '''

                // 🔹 Define the image tags
                    def PS_RELEASE = "${BRANCH}".replace('release-', '')
                    def imageList = [
                        "${ORGANIZATION}/percona-server:${PS_RELEASE}.${RPM_RELEASE}-amd64",
                        "${ORGANIZATION}/percona-server:${PS_RELEASE}.${RPM_RELEASE}-arm64"
                    ]

                // 🔹 Scan images and store logs
                    imageList.each { image ->
                        echo "🔍 Scanning ${image}..."
                        def result = sh(script: """#!/bin/bash
                            set -e
                            sudo trivy image --quiet \
                                      --format table \
                                      --timeout 10m0s \
                                      --ignore-unfixed \
                                      --exit-code 1 \
                                      --scanners vuln \
                                      --severity HIGH,CRITICAL ${image}
                            echo "TRIVY_EXIT_CODE=\$?"
                        """, returnStatus: true)
                        echo "Actual Trivy exit code: ${result}"

                    // 🔴 Fail the build if vulnerabilities are found
                        if (result != 0) {
                            sh """
                            sudo trivy image --quiet \
                                         --format table \
                                         --timeout 10m0s \
                                         --ignore-unfixed \
                                         --exit-code 0 \
                                         --scanners vuln \
                                         --severity HIGH,CRITICAL ${image} | tee -a ${TRIVY_LOG}
                            """
                            error "❌ Trivy detected vulnerabilities in ${image}. See ${TRIVY_LOG} for details."
                        } else {
                            echo "✅ No critical vulnerabilities found in ${image}."
                        }
                    }
                } catch (Exception e) {
                    error "❌ Trivy scan failed: ${e.message}"
                } // try
            } // script
          } // steps
        } // stage
    }
    post {
        success {
            script {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: (${ORGANIZATION}) build has been finished successfully for ${BRANCH} - [${BUILD_URL}]")
            }
            deleteDir()
        }
        failure {
            slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: (${ORGANIZATION})build failed for ${BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                currentBuild.description = "Built on ${BRANCH} for ${ORGANIZATION} organization"
            }
            deleteDir()
        }
    }
}
