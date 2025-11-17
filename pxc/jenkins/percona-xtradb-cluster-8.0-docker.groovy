library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

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
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster.git',
            description: 'URL for percona-xtradb-cluster repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for percona-xtradb-cluster repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
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
               label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
            }
            steps {
                script {
                        echo "====> Build docker containers"
                        cleanUpWS()
                        sh '''
                            PXC_RELEASE=$(echo ${GIT_BRANCH} | sed 's/release-//g')
                            PXC_MAJOR_RELEASE=$(echo ${GIT_BRANCH} | sed "s/release-//g" | sed "s/\\.//g" | awk '{print substr($0, 0, 2)}')
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
                            curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                            . ./MYSQL_VERSION
                            git clone https://github.com/percona/percona-docker
                            cd percona-docker/percona-xtradb-cluster-${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}
                            sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PXC_TELEMETRY_VERSION.*/ENV PXC_TELEMETRY_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}-${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PXC_REPO .*/ENV PXC_REPO testing/g" Dockerfile
                            if [ ${PXC_MAJOR_RELEASE} != "80" ]; then
                                if [ ${PXC_MAJOR_RELEASE} != "84" ]; then
                                    sed -i "s/pxc-80/pxc-8x-innovation/g" Dockerfile
                                fi
                            fi
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker build --provenance=false --no-cache --platform "linux/amd64" -t ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64 .
                                sudo docker build --provenance=false --no-cache --platform "linux/amd64" --build-arg DEBUG=1 -t perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64 .
                            else
                                sudo docker pull perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker tag perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64 percona/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker pull perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64
                                sudo docker tag perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64 percona/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64
                            fi
                            sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PXC_TELEMETRY_VERSION.*/ENV PXC_TELEMETRY_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}-${RPM_RELEASE}/g" Dockerfile.aarch64
                            sed -i "s/ENV PXC_REPO .*/ENV PXC_REPO testing/g" Dockerfile.aarch64
                            if [ ${PXC_MAJOR_RELEASE} != "80" ]; then
                                if [ ${PXC_MAJOR_RELEASE} != "84" ]; then
                                    sed -i "s/pxc-80/pxc-8x-innovation/g" Dockerfile.aarch64
                                fi
                            fi
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker build --provenance=false --no-cache -t perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --platform="linux/arm64" -f Dockerfile.aarch64 .
                                sudo docker build --provenance=false --no-cache --build-arg DEBUG=1 -t perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64 --platform="linux/arm64" -f Dockerfile.aarch64 .
                            else
                                sudo docker pull perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                sudo docker tag perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64 percona/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                sudo docker pull perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64
                                sudo docker tag perconalab/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64 percona/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64
                            fi
                            if [ ${PXC_MAJOR_RELEASE} != "84" ]; then
                                cd ../percona-xtradb-cluster-8.0-backup
                            else
                                cd ../percona-xtradb-cluster-8.4-backup
                            fi
                            sed -i "s/ENV PXC_VERSION.*/ENV PXC_VERSION=${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                            sed -i "s/ENV PXC_REPO.*/ENV PXC_REPO=testing/g" Dockerfile
                            sed -i "s:yum/release:yum/testing:g" Dockerfile
                            if [ ${PXC_MAJOR_RELEASE} != "80" ]; then
                                #sed -i "s/ENV PXB_VERSION.*/ENV PXB_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                                sed -i "s/ENV PXB_VERSION.*/ENV PXB_VERSION 8.4.0-4.1/g" Dockerfile
                                sed -i "s/ENV PS_VERSION.*/ENV PS_VERSION ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}/g" Dockerfile
                                if [ ${PXC_MAJOR_RELEASE} != "84" ]; then
                                    sed -i "s/tools/pxb-8x-innovation/g" Dockerfile
                                    sed -i "s/ps-80/ps-8x-innovation/g" Dockerfile
                                    sed -i "s/pxc-80/pxc-8x-innovation/g" Dockerfile
                                else
                                    sed -i "s/tools/pxb-84-lts/g" Dockerfile
                                    sed -i "s/ps-80/ps-84-lts/g" Dockerfile
                                    sed -i "s/pxc-80/pxc-84-lts/g" Dockerfile
                                fi
                                sed -i "s/percona-xtrabackup-80/percona-xtrabackup-${PXC_MAJOR_RELEASE}/g" Dockerfile
                            fi
                            if [ ${ORGANIZATION} != "percona" ]; then
                                sudo docker build --provenance=false --no-cache --platform "linux/amd64" -t perconalab/percona-xtradb-cluster-operator:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}-pxc8.${MYSQL_VERSION_MINOR}-backup .
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
                                curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                                . ./MYSQL_VERSION
                                sudo docker push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64
                                sudo docker push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-amd64
                                sudo docker push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                                sudo docker push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-debug-arm64
                                if [ ${ORGANIZATION} != "percona" ]; then
                                    sudo docker push perconalab/percona-xtradb-cluster-operator:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}-pxc8.${MYSQL_VERSION_MINOR}-backup
                                fi
                            '''
                        }
                        sh '''
                           curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                           . ./MYSQL_VERSION
                           sudo docker manifest create ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE} \
                               ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64 \
                               ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64
                           sudo docker manifest annotate ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE} ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                           sudo docker manifest annotate ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE} ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64 --os linux --arch amd64
                           sudo docker manifest inspect ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                       '''
                       withCredentials([
                       usernamePassword(credentialsId: 'hub.docker.com',
                       passwordVariable: 'PASS',
                       usernameVariable: 'USER'
                       )]) {
                       sh '''
                           echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                           curl -O https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION
                           . ./MYSQL_VERSION
                           sudo docker manifest push ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH} ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR} ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                           sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-xtradb-cluster:latest ${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}
                       '''
                       }
                 }
            }
        }
stage('Check by trivy') {
    agent {
       label params.CLOUD == 'Hetzner' ? 'deb12-x64' : 'min-focal-x64'
    }
    environment {
        TRIVY_LOG = "trivy-high-junit.xml"
    }
    steps {
        script {
            try {
                echo "🔄 Fetching MySQL version..."

                // 🔹 Capture the file content directly from curl
                def mysqlVersion = sh(
                    script: "curl -s https://raw.githubusercontent.com/percona/percona-xtradb-cluster/${GIT_BRANCH}/MYSQL_VERSION",
                    returnStdout: true
                ).trim()

                echo "🔎 Raw MYSQL_VERSION: '${mysqlVersion}'"

                if (!mysqlVersion) {
                    error "❌ MYSQL_VERSION file is empty or not found!"
                }

                def versionMap = [:]
                mysqlVersion.split('\n').each { line ->
                    def (key, value) = line.tokenize('=')
                    versionMap[key.trim()] = value.trim().replaceAll('"', '')
                }

                def MYSQL_VERSION_MAJOR = versionMap['MYSQL_VERSION_MAJOR']
                def MYSQL_VERSION_MINOR = versionMap['MYSQL_VERSION_MINOR']
                def MYSQL_VERSION_PATCH = versionMap['MYSQL_VERSION_PATCH']
                def MYSQL_VERSION_EXTRA = versionMap['MYSQL_VERSION_EXTRA']
                def fullVersion = "${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}"

                echo "✅ Parsed MySQL version: ${fullVersion}"
                
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
                    "${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-amd64",
                    "${ORGANIZATION}/percona-xtradb-cluster:${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR}.${MYSQL_VERSION_PATCH}${MYSQL_VERSION_EXTRA}.${RPM_RELEASE}-arm64"
                ]

                // 🔹 Scan images and store logs
                imageList.each { image ->
                    echo "🔍 Scanning ${image}..."
                    def result = sh(script: """#!/bin/bash
                        sudo trivy image --quiet \
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
            } // try
        } // script
    } // steps
 } // stage
    }
    post {
        success {
            script {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} pushed to ${ORGANIZATION}")
            }
            deleteDir()
        }
        failure {
            script {
                slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH}]")
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                currentBuild.description = "Built on ${GIT_BRANCH} pushed to ${ORGANIZATION}"
            }
            deleteDir()
        }
    }
}
