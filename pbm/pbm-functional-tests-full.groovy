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
    parameters {
        string(name: 'PBM_BRANCH', defaultValue: 'main', description: 'PBM branch or commit')
        string(name: 'GO_VER', defaultValue: 'latest', description: 'GOLANG docker image for building PBM from sources')
        string(name: 'TESTING_BRANCH', defaultValue: 'main', description: 'psmdb-testing repo branch')
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PBM_BRANCH}"
                }
            }
        }
        stage ('Run tests') {
            matrix {
                agent {
                    label 'docker-32gb'
                }
                axes {
                    axis {
                        name 'TEST'
                        values 'logical', 'physical', 'incremental'
                    }
                    axis {
                        name 'PSMDB'
                        values '4.2', '4.4', '5.0', '6.0'
                    }
                }
                stages {
                    stage ('Run tests') {
                        steps {
                            sh """
                                docker kill \$(docker ps -a -q) || true
                                docker rm \$(docker ps -a -q) || true
                                docker rmi -f \$(docker images -q | uniq) || true
                                sudo rm -rf ./*
                                if [ ! -f "/usr/local/bin/docker-compose" ] ; then
                                    sudo curl -SL https://github.com/docker/compose/releases/download/v2.16.0/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose
                                    sudo chmod +x /usr/local/bin/docker-compose
                                fi
                            """ 
                            git poll: false, branch: params.TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
                            sh """
                                cd pbm-functional/pytest
                                PSMDB=percona/percona-server-mongodb:${PSMDB} docker-compose build
                                docker-compose up -d
                                docker-compose run test pytest -o junit_logging=system-out --capture=sys --junitxml=junit.xml -k ${TEST} || true
                                docker-compose down -v --remove-orphans
                            """
                        }
                        post {
                            always {
                                junit testResults: "**/junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
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
        success {
           slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: PBM ${PBM_BRANCH} - all tests passed [${BUILD_URL}testReport/]")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: PBM ${PBM_BRANCH} - some tests failed [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: PBM ${PBM_BRANCH} - unexpected failure [${BUILD_URL}]")
        }
    }
}
