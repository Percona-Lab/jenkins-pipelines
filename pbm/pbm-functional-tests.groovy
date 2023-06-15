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
        string(name: 'PBM_BRANCH', defaultValue: 'main', description: 'PBM branch')
        string(name: 'PSMDB', defaultValue: 'percona/percona-server-mongodb', description: 'PSMDB docker image')
        string(name: 'GO_VER', defaultValue: 'bullseye', description: 'GOLANG docker image for building PBM from sources')
        choice(name: 'JIRA_REPORT', choices: ['no','yes'], description: 'Send report to jira')
        string(name: 'TEST_CYCLE_NAME', defaultValue: 'test', description: 'Jira test cycle name')
        string(name: 'TESTING_BRANCH', defaultValue: 'main', description: 'psmdb-testing repo branch')
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PBM_BRANCH}-${params.PSMDB}"
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
                        values 'logical', 'physical', 'incremental', 'external'
                    }
                }
                stages {
                    stage ('Run tests') {
                        steps {
                            withCredentials([usernamePassword(credentialsId: 'JIRA_CREDENTIALS', passwordVariable: 'JIRA_PASSWORD', usernameVariable: 'JIRA_USERNAME')]) {
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
                                    docker-compose build
                                    docker-compose up -d
                                    if [ ${params.JIRA_REPORT} = "yes" ]; then
                                        export JIRA_SERVER=https://jira.percona.com 
                                        export JIRA_USERNAME=${JIRA_USERNAME} 
                                        export JIRA_PASSWORD=${JIRA_PASSWORD}
                                        echo "test_cycle_name=${params.TEST_CYCLE_NAME}" >> pytest.ini
                                        docker-compose run test pytest --adaptavist -s --junitxml=junit.xml -k ${TEST} || true
                                    else 
                                        docker-compose run test pytest -s --junitxml=junit.xml -k ${TEST} || true
                                    fi
                                    docker-compose down -v --remove-orphans
                                """
                                }
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
           slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: PBM ${PBM_BRANCH} with ${PSMDB} - all tests passed")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: PBM ${PBM_BRANCH} with ${PSMDB} - some tests failed [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: PBM ${PBM_BRANCH} with ${PSMDB} - unexpected failure [${BUILD_URL}]")
        }
    }
}
