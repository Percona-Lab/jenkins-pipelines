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
        string(name: 'MLINK_BRANCH', defaultValue: 'main', description: 'Mongo Link Branch')
        string(name: 'GO_VER', defaultValue: 'latest', description: 'GOLANG docker image for building PBM from sources')
        choice(name: 'instance', choices: ['docker-64gb','docker-64gb-aarch64'], description: 'Ec2 instance type for running tests')
        string(name: 'PSMDB_TESTING_BRANCH', defaultValue: 'main', description: 'psmdb-testing repo branch')
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.MLINK_BRANCH}"
                }
            }
        }
        stage ('Run tests') {
            matrix {
                agent {
                    label "${params.instance}"
                }
                axes {
                    axis {
                        name 'MONGODB_IMAGE'
                        values 'percona/percona-server-mongodb:6.0'
//                        , 'percona/percona-server-mongodb:7.0', 'percona/percona-server-mongodb:8.0'
                    }
                }
                stages {
                    stage ('Run tests') {
                        steps {
                            withCredentials([string(credentialsId: 'olexandr_zephyr_token', variable: 'ZEPHYR_TOKEN')]) {
                                sh """
                                    docker kill \$(docker ps -a -q) || true
                                    docker rm \$(docker ps -a -q) || true
                                    docker rmi -f \$(docker images -q | uniq) || true
                                    sudo rm -rf ./*
                                    if [ ! -f "/usr/local/bin/docker-compose" ] ; then
                                        if [ ${params.instance} = "docker-64gb-aarch64" ]; then
                                            sudo curl -SL https://github.com/docker/compose/releases/download/v2.16.0/docker-compose-linux-aarch64 -o /usr/local/bin/docker-compose
                                        else 
                                            sudo curl -SL https://github.com/docker/compose/releases/download/v2.16.0/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose
                                        fi
                                        sudo chmod +x /usr/local/bin/docker-compose
                                    fi
                                """

                                dir('psmdb-testing') {
                                    git poll: false, branch: params.PSMDB_TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
                                }

                                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GIT_TOKEN')]) {
                                    dir('percona-mongolink') {
                                        git url: "https://x-access-token:${GIT_TOKEN}@github.com/Percona-Lab/percona-mongolink.git",
                                                branch: params.MLINK_BRANCH,
                                                poll: false
                                    }
                                }

                                sh """
                                    cd psmdb-testing/mlink
                                    docker-compose build
                                    docker-compose up -d
                                    docker-compose run test pytest -v -s --junitxml=junit.xml || true
                                    docker-compose down -v --remove-orphans
                                    curl -H "Content-Type:multipart/form-data" -H "Authorization: Bearer ${ZEPHYR_TOKEN}" -F "file=@junit.xml;type=application/xml" 'https://api.zephyrscale.smartbear.com/v2/automations/executions/junit?projectKey=PML' -F 'testCycle={"name":"${JOB_NAME}-${BUILD_NUMBER}","customFields": { "Mongo Link branch": "${MLINK_BRANCH}","PSMDB docker image": "${MONGODB_IMAGE}","instance": "${instance}"}};type=application/json' -i || true
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
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: PML ${MLINK_BRANCH} - all tests passed")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: PML ${MLINK_BRANCH} - some tests failed [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: PML ${MLINK_BRANCH} - unexpected failure [${BUILD_URL}]")
        }
    }
}
