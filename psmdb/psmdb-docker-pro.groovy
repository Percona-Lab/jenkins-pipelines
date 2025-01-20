library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def REPO_CI_PATH

pipeline {
    agent {
        label params.ARCH == 'x86_64' ? 'docker' : 'docker-64gb-aarch64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'PSMDB_REPO', choices: ['release','testing','experimental'], description: 'percona-release repo to take packages from')
        string(name: 'PSMDB_VERSION', defaultValue: '6.0.18-15', description: 'PSMDB version, for example: 6.0.18-15 or 7.0.14-8')
        string(name: 'TICKET_NAME', defaultValue: '', description: 'Target folder for docker image')
        choice(name: 'ARCH', choices: ['x86_64','aarch64'], description: 'Docker arch')
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
        stage ('Build image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'PSMDB_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                    sh """
                        MAJ_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "." '{print \$1"."\$2}')
                        echo \$MAJ_VER
                        MIN_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "-" '{print \$1}')
                        echo \$MIN_VER
                        git clone https://github.com/percona/percona-docker
                        cd percona-docker/percona-server-mongodb-\$MAJ_VER
                        sed -E "s|ENV PSMDB_VERSION (.+)|ENV PSMDB_VERSION ${params.PSMDB_VERSION}|" -i Dockerfile
                        sed -E "s|ENV PSMDB_REPO (.+)|ENV PSMDB_REPO ${params.PSMDB_REPO}|" -i Dockerfile
                        REPO_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "." '{print \$1\$2}')
                        OS_VER=\$(grep -P "ENV OS_VER" Dockerfile | cut -d " " -f 3)
                        echo \$OS_VER
                        OS_VER_NUM=\$(echo \$OS_VER | sed "s|el||")
                        echo \$OS_VER_NUM
                        PKG_URL="http://repo.percona.com/private/${USERNAME}-${PASSWORD}/psmdb-\$REPO_VER-pro/yum/${params.PSMDB_REPO}/\${OS_VER_NUM}/RPMS/${ARCH}/"
                        echo \$PKG_URL
                        sed -E "/curl -Lf -o \\/tmp\\/Percona-Server-MongoDB-server.rpm/d" -i Dockerfile
                        sed -E "/percona-server-mongodb-mongos-\${FULL_PERCONA_VERSION}/d" -i Dockerfile
                        sed -E "/percona-server-mongodb-tools-\${FULL_PERCONA_VERSION}/d" -i Dockerfile
                        sed -E "s|rpmkeys --checksig /tmp/Percona-Server-MongoDB-server.rpm|rpmkeys --checksig /tmp/*.rpm|" -i Dockerfile
                        sed -E "s|rpm -iv /tmp/Percona-Server-MongoDB-server.rpm --nodeps;|dnf -y install /tmp/Percona-Server-MongoDB-mongos-pro.rpm /tmp/Percona-Server-MongoDB-tools.rpm; rpm -iv /tmp/Percona-Server-MongoDB-server-pro.rpm --nodeps;|" -i Dockerfile
                        sed -E "s|rm -rf /tmp/Percona-Server-MongoDB-server.rpm|rm -rf /tmp/*.rpm|" -i Dockerfile
                        sed -E "s|server/LICENSE|server-pro/LICENSE|" -i Dockerfile

                        mkdir -p packages
                        curl -Lf -o ./packages/Percona-Server-MongoDB-server-pro.rpm \$PKG_URL/percona-server-mongodb-server-pro-${params.PSMDB_VERSION}.\$OS_VER.${ARCH}.rpm
                        curl -Lf -o ./packages/Percona-Server-MongoDB-mongos-pro.rpm \$PKG_URL/percona-server-mongodb-mongos-pro-${params.PSMDB_VERSION}.\$OS_VER.${ARCH}.rpm
                        curl -Lf -o ./packages/Percona-Server-MongoDB-tools.rpm \$PKG_URL/percona-server-mongodb-tools-${params.PSMDB_VERSION}.\$OS_VER.${ARCH}.rpm
                        sed -E '/^ARG PERCONA_TELEMETRY_DISABLE/a \\\nCOPY packages/*.rpm /tmp/' -i Dockerfile
                        cat Dockerfile
                        docker build . -t percona-server-mongodb-pro:${params.PSMDB_VERSION}
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
                    if [[ ${ARCH} == "x86_64" ]]; then
                        TRIVY_ARCH="64bit"
                    elif [[ ${ARCH} == "aarch64" ]]; then
                        TRIVY_ARCH="ARM64"
                    fi
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-\${TRIVY_ARCH}.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-\${TRIVY_ARCH}.tar.gz -C /usr/local/bin/
                    rm trivy_\${TRIVY_VERSION}_Linux-\${TRIVY_ARCH}.tar.gz
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    curl https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/docker/trivyignore -o ".trivyignore"
                    if [ ${params.PSMDB_REPO} = "release" ]; then
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-server-mongodb-pro:${params.PSMDB_VERSION}
                    else
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-server-mongodb-pro:${params.PSMDB_VERSION}
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
            post {
                always {
                    junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
        stage ('Push image to repo.ci') {
            steps {
                sh """
                    docker save -o percona-server-mongodb-pro-${params.PSMDB_VERSION}.tar percona-server-mongodb-pro:${params.PSMDB_VERSION}
                    gzip percona-server-mongodb-pro-${params.PSMDB_VERSION}.tar
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
    }
    post {
        always {
            sh """
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            """
            deleteDir()
        }
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} succeed")
            script {
                currentBuild.description = "Built on ${PSMDB_VERSION}. Path to packages: ${REPO_CI_PATH}"
            }
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} unstable - [${BUILD_URL}testReport/]")
            script {
                currentBuild.description = "Built on ${PSMDB_VERSION}. Path to packages: ${REPO_CI_PATH}"
            }
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} failed - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${PSMDB_VERSION}. Path to packages: ${REPO_CI_PATH}"
            }
        }
    }
}
