library changelog: false, identifier: "lib@PSMDB-1553_build_psmdb_pro_dockers", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/vorsel/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker-32gb'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'PSMDB_REPO', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PSMDB_VERSION', defaultValue: '6.0.2-1', description: 'PSMDB version')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab','AWS_ECR','DockerHub'], description: 'Target repo for docker image, use DockerHub for release only')
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
                        docker save -o percona-server-mongodb-pro-${params.PSMDB_VERSION}.tar percona-server-mongodb-pro:${params.PSMDB_VERSION}
                        gzip percona-server-mongodb-pro-${params.PSMDB_VERSION}.tar
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
            slackNotify("@alex.miroshnychenko", "#00FF00", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} succeed")
        }
        unstable {
            slackNotify("@alex.miroshnychenko", "#F6F930", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("@alex.miroshnychenko", "#FF0000", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} failed - [${BUILD_URL}]")
        }
    }
}
