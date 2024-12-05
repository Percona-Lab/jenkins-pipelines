library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'master'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    options {
        disableConcurrentBuilds()
    }
    triggers { 
        cron('0 0 * * 0') 
    }
    stages {
        stage ('Run trivy analyzer') {
            matrix {
                agent {
                    label "docker"
                }
                axes {
                    axis {
                        name 'PSMDB_VERSION'
                        values '6.0', '7.0'
                    }
                }
                stages {
                    stage ('Run tests') {
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
                                /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona/percona-server-mongodb:${PSMDB_VERSION}
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
                                sh """
                                    docker kill \$(docker ps -a -q) || true
                                    docker rm \$(docker ps -a -q) || true
                                    docker rmi -f \$(docker images -q | uniq) || true
                                    sudo rm -rf ./*
                                """
                            }
                        }
                    }
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
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: Testing PSMDB docker images for CVE - succeed")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: Testing PSMDB docker images for CVE - some issues found: [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: Testing PSMDB docker images for CVE - unexpected failure: [${BUILD_URL}]")
        }
    }
}
