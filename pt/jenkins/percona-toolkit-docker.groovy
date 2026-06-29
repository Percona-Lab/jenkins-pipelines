/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
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

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    parameters {
        choice(
            choices: [ 'Hetzner','AWS' ],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        choice(
            choices: 'perconalab\npercona',
            description: 'Organization on hub.docker.com',
            name: 'ORGANIZATION')
        string(defaultValue: 'https://github.com/percona/percona-docker', description: 'Dockerfiles source', name: 'REPO_DOCKER')
        string(defaultValue: 'main', description: 'Tag/Branch for percona-docker repository', name: 'REPO_DOCKER_BRANCH')
        string(defaultValue: '3.7.1', description: 'Percona Toolkit version', name: 'VERSION')
        string(defaultValue: '4', description: 'RPM release value', name: 'RPM_RELEASE')
        choice(
            choices: 'testing\nexperimental\nrelease',
            description: 'Repository component used to retrieve packages',
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
        timestamps()
    }
    stages {
        stage('Build docker containers') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                script {
                    echo "====> Build docker containers"
                    cleanUpWS()
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
                        rm -rf percona-docker
                        git clone ${REPO_DOCKER}
                        cd percona-docker
                        git checkout ${REPO_DOCKER_BRANCH}
                        cd percona-toolkit
                        sed -i "s/ENV PT_VERSION.*/ENV PT_VERSION ${VERSION}-${RPM_RELEASE}.el9/g" Dockerfile
                        sed -i "s/pt release/pt ${COMPONENT}/g" Dockerfile
                        sudo docker --version
                        if [ ${ORGANIZATION} != "percona" ]; then
                            sudo docker build --no-cache --platform "linux/amd64" -t perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-amd64 .
                            sudo docker build --no-cache -t perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-arm64 --platform="linux/arm64" -f Dockerfile .
                        else
                            sudo docker pull perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-amd64
                            sudo docker tag perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-amd64 percona/percona-toolkit:${VERSION}-${RPM_RELEASE}-amd64
                            sudo docker pull perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-arm64
                            sudo docker tag perconalab/percona-toolkit:${VERSION}-${RPM_RELEASE}-arm64 percona/percona-toolkit:${VERSION}-${RPM_RELEASE}-arm64
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
                        sudo docker push ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}-amd64
                        sudo docker push ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}-arm64
                   '''
                    }
                    sh '''
                        sudo docker manifest create --amend ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE} \
                            ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}-amd64 \
                            ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}-arm64
                        sudo docker manifest annotate ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE} ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}-arm64 --os linux --arch arm64 --variant v8
                        sudo docker manifest annotate ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE} ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}-amd64 --os linux --arch amd64
                        sudo docker manifest inspect ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}
                    '''
                    withCredentials([
                    usernamePassword(credentialsId: 'hub.docker.com',
                    passwordVariable: 'PASS',
                    usernameVariable: 'USER'
                    )]) {
                    sh '''
                        PT_MAJOR_VERSION=$(echo ${VERSION} | cut -d'.' -f1)
                        PT_MINOR_VERSION=$(echo ${VERSION} | cut -d'.' -f2)
                        echo "${PASS}" | sudo docker login -u "${USER}" --password-stdin
                        sudo docker manifest push ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}
                        sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-toolkit:${VERSION} ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}
                        sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-toolkit:${PT_MAJOR_VERSION}.${PT_MINOR_VERSION} ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}
                        sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-toolkit:${PT_MAJOR_VERSION} ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}
                        sudo docker buildx imagetools create -t ${ORGANIZATION}/percona-toolkit:latest ${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}
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
                        installTrivy(method: 'apt')

                        def imageList = [
                            "${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}-amd64",
                            "${ORGANIZATION}/percona-toolkit:${VERSION}-${RPM_RELEASE}-arm64"
                        ]
                        env.TRIVY_IMAGES = imageList.join(', ')

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

                            // 🟡 Mark build as unstable if vulnerabilities are found
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
                                unstable "⚠️ Trivy detected vulnerabilities in ${image}. See ${TRIVY_LOG} for details."
                            } else {
                                echo "✅ No critical vulnerabilities found in ${image}."
                            }
                        }
                    } catch (Exception e) {
                        unstable "⚠️ Trivy scan failed: ${e.message}"
                    } // try
                } // script
            } // steps
        } // stage
    }
    post {
        success {
            script {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "✅ ${ORGANIZATION == 'perconalab' ? '🧪 ' : '🦾 '}[${JOB_NAME}]: (${ORGANIZATION}) build has been finished successfully for ${VERSION} - [${BUILD_URL}]\nImages: ${env.TRIVY_IMAGES ?: 'N/A'}")
            }
            deleteDir()
        }
        unstable {
            script {
                slackNotify("${SLACKNOTIFY}", "#FFFF00", "⚠️ ${ORGANIZATION == 'perconalab' ? '🧪 ' : '🦾 '}[${JOB_NAME}]: (${ORGANIZATION}) build finished with warnings (Trivy) for ${VERSION} - [${BUILD_URL}]\nImages: ${env.TRIVY_IMAGES ?: 'N/A'}")
            }
            deleteDir()
        }
        failure {
            script {
                slackNotify("${SLACKNOTIFY}", "#FF0000", "❌ ${ORGANIZATION == 'perconalab' ? '🧪 ' : '🦾 '}[${JOB_NAME}]: (${ORGANIZATION}) build failed for ${VERSION} - [${BUILD_URL}]")
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                currentBuild.description = "Built on ${VERSION} for ${ORGANIZATION} organization"
            }
            deleteDir()
        }
    }
}
