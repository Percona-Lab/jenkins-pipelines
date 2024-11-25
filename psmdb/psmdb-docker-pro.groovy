library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'PSMDB_REPO', choices: ['release','testing','experimental'], description: 'percona-release repo to take packages from')
        string(name: 'PSMDB_VERSION', defaultValue: '6.0.18-15', description: 'PSMDB version, for example: 6.0.18-15 or 7.0.14-8')
        string(name: 'TICKET_NAME', defaultValue: '', description: 'Target folder for docker image')
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
                        sed -E "s|(psmdb-[0-9]{2})|\\1-pro|g" -i Dockerfile
                        sed -E 's:(percona-server-mongodb-(server|mongos)):\\1-pro:g' -i Dockerfile
                        sed -E "s|(enable psmdb-[0-9]{2}-pro)|\\1 --user_name='${USERNAME}' --repo_token='${PASSWORD}'|" -i Dockerfile
                        sed -E "s|(repo\\.percona\\.com/)(psmdb-[0-9]{2}-pro)|\\1private/'${USERNAME}'-'${PASSWORD}'/\\2|" -i Dockerfile
                        docker build . -t percona-server-mongodb-pro:${params.PSMDB_VERSION}
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
                    rm trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
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
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} failed - [${BUILD_URL}]")
        }
    }
}
