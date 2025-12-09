library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def minitestNodes = [  "min-bullseye-x64",
                       "min-bookworm-x64",
                       "min-centos-7-x64",
                       "min-ol-8-x64",
                       "min-focal-x64",
                       "min-jammy-x64",
                       "min-ol-9-x64"     ]

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
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
                            sed -i "s/ARG PXB_REPO=release/ARG PXB_REPO=${COMPONENT}/g" Dockerfile
                            sed -i "s/ARG PS_REPO=release/ARG PS_REPO=${COMPONENT}/g" Dockerfile
                            sed -i "s/ARG TOOLS_REPO=release/ARG TOOLS_REPO=${COMPONENT}/g" Dockerfile
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker build --provenance=false --no-cache -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --platform "linux/amd64" .
                                sudo docker build --provenance=false --no-cache -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --platform="linux/arm64" .
                            else
                                sudo docker pull perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 
                                sudo docker tag perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker pull perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 
                                sudo docker tag perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64
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
                                curl -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                                . ./XB_VERSION
                                if [ ${ORGANIZATION} != "percona" ]; then
                                    sudo docker push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                    sudo docker push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64

                                    sudo docker manifest create perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} \
                                        perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 \
                                        perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                    sudo docker manifest annotate perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                                    sudo docker manifest annotate perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --os linux --arch amd64
                                    sudo docker manifest inspect perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker manifest push perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}

                                    sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR} perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t perconalab/percona-xtrabackup:latest perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                               else
                                    sudo docker push percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                    sudo docker push percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64

                                    sudo docker manifest create percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} \
                                        percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 \
                                        percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                    sudo docker manifest annotate percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                                    sudo docker manifest annotate percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --os linux --arch amd64
                                    sudo docker manifest inspect percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker manifest push percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}

                                    sudo docker buildx imagetools create -t percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    sudo docker buildx imagetools create -t percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR} percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    if [ ${XB_VERSION_MINOR} = "0" ]; then
                                        sudo docker buildx imagetools create -t percona/percona-xtrabackup:latest percona/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}
                                    fi
                               fi
                            '''
                           }
                } // scripts
            } // steps
        } // stage
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
                // 🔹 Fetch XtraBackup Version
                echo "🔄 Fetching XtraBackup version..."
                sh '''
                    curl -O https://raw.githubusercontent.com/percona/percona-xtrabackup/${BRANCH}/XB_VERSION
                    cat XB_VERSION
                '''

                // ✅ Read the version file and parse it manually (CPS-compatible way)
                def versionEnv = [:]
                def versionContent = readFile('XB_VERSION')
                
                // 🔹 Manually split lines and process them
                versionContent.split("\n").each { line ->
                    def parts = line.split('=')
                    if (parts.length == 2) {
                        versionEnv[parts[0].trim()] = parts[1].trim()
                    }
                }

                // 🔹 Extract version components
                def XB_VERSION_MAJOR = versionEnv['XB_VERSION_MAJOR']
                def XB_VERSION_MINOR = versionEnv['XB_VERSION_MINOR']
                def XB_VERSION_PATCH = versionEnv['XB_VERSION_PATCH']
                def XB_VERSION_EXTRA = versionEnv['XB_VERSION_EXTRA']
                
                // 🔹 Install Trivy if not already installed
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
                def imageList = [
                    "perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-amd64",
                    "perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64"
                ]

                // 🔹 Scan images and store logs
                imageList.each { image ->
                    echo "🔍 Scanning ${image}..."
                    def result = sh(script: """#!/bin/bash
                        LANG=C.UTF-8 sudo trivy image --quiet \
                                         --format table \
                                         --timeout 10m0s \
                                         --ignore-unfixed \
                                         --exit-code 1 \
                                         --severity HIGH,CRITICAL ${image} | tee -a ${TRIVY_LOG}
                    """, returnStatus: true)

                    if (result != 0) {
                        error "❌ Trivy detected vulnerabilities in ${image}. See ${TRIVY_LOG} for details."
                    } else {
                        echo "✅ No critical vulnerabilities found in ${image}."
                    }
                }
            } catch (Exception e) {
                error "❌ Trivy scan failed: ${e.message}"
            }
        }
    }
    post {
        always {
            // 🔹 Archive the report
            archiveArtifacts artifacts: "${TRIVY_LOG}", allowEmptyArchive: true
        }
    }
}
/*
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
                                          --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL perconalab/percona-xtrabackup:${XB_VERSION_MAJOR}.${XB_VERSION_MINOR}.${XB_VERSION_PATCH}${XB_VERSION_EXTRA}.${RPM_RELEASE}-arm64 | tee -a trivy-hight-junit.xml
                        '''
                }
            }
        }
*/
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                currentBuild.description = "Built on ${BRANCH}"
            }
            deleteDir()
        }
    }
}
