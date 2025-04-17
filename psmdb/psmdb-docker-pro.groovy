library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def REPO_CI_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        choice(name: 'PSMDB_REPO', choices: ['release','testing','experimental'], description: 'percona-release repo to take packages from')
        string(name: 'PSMDB_VERSION', defaultValue: '6.0.18-15', description: 'PSMDB version, for example: 6.0.18-15 or 7.0.14-8')
        choice(name: 'TARGET_REPO', choices: ['DockerHub','REPO.CI'], description: 'Target destination for docker image, use Percona for release only')
        string(name: 'TICKET_NAME', defaultValue: '', description: 'Target folder on repo.ci for docker image if TARGET_REPO is repo.ci')
        string(name: 'PD_BRANCH', defaultValue: '', description: 'Branch/commit from percona-docker repository to be used for building images')
        booleanParam(name: 'UPDATE_MAJ_REL_TAG', defaultValue: false, description: 'Update major release tag (6.0 for 6.0.20) in DockerHub')
        booleanParam(name: 'UPDATE_LATEST_TAG', defaultValue: false, description: 'Update \'latest\' tag in DockerHub')
        booleanParam(name: 'IGNORE_TRIVY', defaultValue: false, description: 'Push images despite failed trivy check, use with caution')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PSMDB_REPO}-${params.PSMDB_VERSION}"
                }
            }
        }
        stage('Prepare environment') {
            steps {
                script {
                    if (params.CLOUD == 'Hetzner') {
                        sh '''
                            sudo apt-get update
                            sudo apt-get install -y apt-transport-https ca-certificates curl gnupg-agent software-properties-common apparmor apparmor-utils
                            sudo systemctl stop apparmor
                            sudo systemctl disable apparmor
                            sudo mkdir -p /usr/libexec/docker/cli-plugins
                            LATEST=\$(curl -s https://api.github.com/repos/docker/buildx/releases/latest | grep '"tag_name":' | sed -E 's/.*"v([^"]+)".*/\\1/')
                            sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                            -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            sudo apt-get install -y qemu-system binfmt-support qemu-user-static
                            sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                        '''
                    } else {
                        sh '''
                            sudo yum install -y ca-certificates curl gnupg2 git qemu qemu-user-static || true
                            sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
                        '''
                    }
                }
            }
        }
        stage ('Build image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'PSMDB_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                    sh """
                        MAJ_VER=\$(echo "${params.PSMDB_VERSION}" | awk -F "." '{print \$1"."\$2}')
                        echo \$MAJ_VER
                        MIN_VER=\$(echo "${params.PSMDB_VERSION}" | awk -F "-" '{print \$1}')
                        echo \$MIN_VER
                        git clone https://github.com/percona/percona-docker
                        cd percona-docker/percona-server-mongodb-\$MAJ_VER
                        if [ -n "${params.PD_BRANCH}" ]; then
                            git checkout ${params.PD_BRANCH}
                        fi
                        for ARCH in x86_64 aarch64; do
                            FILENAME=\$(if [ \"\$ARCH\" = \"x86_64\" ]; then echo \"Dockerfile\"; else echo \"Dockerfile.aarch64\"; fi)
                            sed -E "s|ENV PSMDB_VERSION (.+)|ENV PSMDB_VERSION ${params.PSMDB_VERSION}|" -i "\$FILENAME"
                            sed -E "s|ENV PSMDB_REPO (.+)|ENV PSMDB_REPO ${params.PSMDB_REPO}|" -i "\$FILENAME"
                            REPO_VER=\$(echo "${params.PSMDB_VERSION}" | awk -F "." '{print \$1\$2}')
                            OS_VER=\$(grep -P "ENV OS_VER" Dockerfile | cut -d " " -f 3)
                            echo \$OS_VER
                            OS_VER_NUM=\$(echo \$OS_VER | sed "s|el||")
                            echo \$OS_VER_NUM
                            PKG_URL="http://repo.percona.com/private/${USERNAME}-${PASSWORD}/psmdb-\$REPO_VER-pro/yum/${params.PSMDB_REPO}/\${OS_VER_NUM}/RPMS/\${ARCH}/"
                            echo \$PKG_URL
                            sed -E "/curl -Lf -o \\/tmp\\/Percona-Server-MongoDB-server.rpm/d" -i "\$FILENAME"
                            sed -E "/percona-server-mongodb-mongos-\${FULL_PERCONA_VERSION}/d" -i "\$FILENAME"
                            sed -E "/percona-server-mongodb-tools-\${FULL_PERCONA_VERSION}/d" -i "\$FILENAME"
                            sed -E "s|mongosh |mongosh percona-telemetry-agent |" -i "\$FILENAME"
                            sed -E "s|rpmkeys --checksig /tmp/Percona-Server-MongoDB-server.rpm|rpmkeys --checksig /tmp/*.rpm|" -i "\$FILENAME"
                            sed -E "s|rpm -iv /tmp/Percona-Server-MongoDB-server.rpm --nodeps;|rpm -iv /tmp/Percona-Server-MongoDB-mongos-pro.rpm /tmp/Percona-Server-MongoDB-tools.rpm; rpm -iv /tmp/Percona-Server-MongoDB-server-pro.rpm --nodeps;|" -i "\$FILENAME"
                            sed -E "s|rm -rf /tmp/Percona-Server-MongoDB-server.rpm|rm -rf /tmp/*.rpm|" -i "\$FILENAME"
                            sed -E "s|server/LICENSE|server-pro/LICENSE|" -i "\$FILENAME"
                            mkdir -p packages
                            curl -Lf -o ./packages/Percona-Server-MongoDB-server-pro.rpm \$PKG_URL/percona-server-mongodb-server-pro-${params.PSMDB_VERSION}.\$OS_VER.\${ARCH}.rpm
                            curl -Lf -o ./packages/Percona-Server-MongoDB-mongos-pro.rpm \$PKG_URL/percona-server-mongodb-mongos-pro-${params.PSMDB_VERSION}.\$OS_VER.\${ARCH}.rpm
                            curl -Lf -o ./packages/Percona-Server-MongoDB-tools.rpm \$PKG_URL/percona-server-mongodb-tools-${params.PSMDB_VERSION}.\$OS_VER.\${ARCH}.rpm
                            sed -E '/^ARG PERCONA_TELEMETRY_DISABLE/a \\\nCOPY packages/*.rpm /tmp/' -i "\$FILENAME"
                            cat "\$FILENAME"
                            if [ "\$ARCH" = "x86_64" ]; then
                                docker build --no-cache -t percona-server-mongodb-pro-amd -f "\$FILENAME" .
                            else
                                docker build --no-cache -t percona-server-mongodb-pro-arm --platform="linux/arm64" -f "\$FILENAME" .
                            fi
                        done
                        rm -rf packages
                    """
                }
            }
        }
        stage ('Run trivy analyzer') {
            steps {
             script {
              retry(3) {
               try {
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    curl https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/docker/trivyignore -o ".trivyignore"
                    if [ "${params.IGNORE_TRIVY}" = "true" ]; then
                        /usr/local/bin/trivy -q image --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-server-mongodb-pro-arm
                        /usr/local/bin/trivy -q image --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-server-mongodb-pro-amd
                    else
                        /usr/local/bin/trivy -q image --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-server-mongodb-pro-arm
                        /usr/local/bin/trivy -q image --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-server-mongodb-pro-amd
                    fi
               """
               } catch (Exception e) {
                    echo "Attempt failed: ${e.message}"
                    sleep 15
                    throw e
               }
              }
             }
            }
        }
        stage ('Push image to repo.ci') {
            when {
                environment name: 'TARGET_REPO', value: 'REPO.CI'
            }
            steps {
                sh """
                    docker save -o percona-server-mongodb-pro-${params.PSMDB_VERSION}-amd64.tar percona-server-mongodb-pro-amd
                    docker save -o percona-server-mongodb-pro-${params.PSMDB_VERSION}-arm64.tar percona-server-mongodb-pro-arm
                    gzip percona-server-mongodb-pro-${params.PSMDB_VERSION}-amd64.tar
                    gzip percona-server-mongodb-pro-${params.PSMDB_VERSION}-arm64.tar
                    echo "UPLOAD/experimental/CUSTOM/${TICKET_NAME}/${JOB_NAME}/${BUILD_NUMBER}" > uploadPath
                """
                script {
                    REPO_CI_PATH = sh(returnStdout: true, script: "cat uploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                stash includes: '*.tar.*', name: 'docker.tarball'
                uploadTarball('docker')
            }
        }
        stage ('Push images to private percona docker registry') {
            when {
                environment name: 'TARGET_REPO', value: 'DockerHub'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        MAJ_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "." '{print \$1"."\$2}')
                        MIN_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "-" '{print \$1}')

                        docker tag percona-server-mongodb-pro-amd percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}-amd64
                        docker tag percona-server-mongodb-pro-arm percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}-arm64
                        docker push percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}-amd64
                        docker push percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}-arm64

                        docker tag percona-server-mongodb-pro-amd percona/percona-server-mongodb-pro:\$MIN_VER-amd64
                        docker tag percona-server-mongodb-pro-arm percona/percona-server-mongodb-pro:\$MIN_VER-arm64
                        docker push percona/percona-server-mongodb-pro:\$MIN_VER-amd64
                        docker push percona/percona-server-mongodb-pro:\$MIN_VER-arm64

                        docker manifest create percona/percona-server-mongodb-pro:${params.PSMDB_VERSION} \
                            percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}-amd64 \
                            percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}-arm64
                        docker manifest annotate percona/percona-server-mongodb-pro:${params.PSMDB_VERSION} \
                            percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}-arm64 --os linux --arch arm64 --variant v8
                        docker manifest annotate percona/percona-server-mongodb-pro:${params.PSMDB_VERSION} \
                            percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}-amd64 --os linux --arch amd64
                        docker manifest inspect percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}
                        docker manifest push percona/percona-server-mongodb-pro:${params.PSMDB_VERSION}

                        docker manifest create percona/percona-server-mongodb-pro:\$MIN_VER \
                            percona/percona-server-mongodb-pro:\$MIN_VER-amd64 \
                            percona/percona-server-mongodb-pro:\$MIN_VER-arm64
                        docker manifest annotate percona/percona-server-mongodb-pro:\$MIN_VER \
                            percona/percona-server-mongodb-pro:\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                        docker manifest annotate percona/percona-server-mongodb-pro:\$MIN_VER \
                            percona/percona-server-mongodb-pro:\$MIN_VER-amd64 --os linux --arch amd64
                        docker manifest inspect percona/percona-server-mongodb-pro:\$MIN_VER
                        docker manifest push percona/percona-server-mongodb-pro:\$MIN_VER

                        if [ ${params.UPDATE_MAJ_REL_TAG} = "true" ]; then
                            docker tag percona-server-mongodb-pro-amd percona/percona-server-mongodb-pro:\$MAJ_VER-amd64
                            docker tag percona-server-mongodb-pro-arm percona/percona-server-mongodb-pro:\$MAJ_VER-arm64
                            docker push percona/percona-server-mongodb-pro:\$MAJ_VER-amd64
                            docker push percona/percona-server-mongodb-pro:\$MAJ_VER-arm64

                            docker manifest create percona/percona-server-mongodb-pro:\$MAJ_VER \
                                percona/percona-server-mongodb-pro:\$MAJ_VER-amd64 \
                                percona/percona-server-mongodb-pro:\$MAJ_VER-arm64
                            docker manifest annotate percona/percona-server-mongodb-pro:\$MAJ_VER \
                                percona/percona-server-mongodb-pro:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate percona/percona-server-mongodb-pro:\$MAJ_VER \
                                percona/percona-server-mongodb-pro:\$MAJ_VER-amd64 --os linux --arch amd64
                            docker manifest inspect percona/percona-server-mongodb-pro:\$MAJ_VER
                            docker manifest push percona/percona-server-mongodb-pro:\$MAJ_VER
                        fi

                        if [ "${params.UPDATE_MAJ_REL_TAG}" = "true" ] && [ "${params.UPDATE_LATEST_TAG}" = "true" ]; then
                            docker manifest create percona/percona-server-mongodb-pro:latest \
                              percona/percona-server-mongodb-pro:\$MAJ_VER-amd64 \
                              percona/percona-server-mongodb-pro:\$MAJ_VER-arm64
                            docker manifest annotate percona/percona-server-mongodb-pro:latest \
                              percona/percona-server-mongodb-pro:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate percona/percona-server-mongodb-pro:latest \
                              percona/percona-server-mongodb-pro:\$MAJ_VER-amd64 --os linux --arch amd64
                            docker manifest inspect percona/percona-server-mongodb-pro:latest
                            docker manifest push percona/percona-server-mongodb-pro:latest
                        fi
                    """
                }
            }
        }
    }
    post {
        always {
            sh """
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            """
            deleteDir()
        }
}
}
