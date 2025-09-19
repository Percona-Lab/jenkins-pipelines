library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'launcher-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        string(name: 'PBM_BRANCH', defaultValue: 'main', description: 'PBM branch')
        string(name: 'PSMDB', defaultValue: 'percona/percona-server-mongodb', description: 'PSMDB docker image')
        string(name: 'GO_VER', defaultValue: 'bookworm', description: 'GOLANG docker image for building PBM from sources')
        choice(name: 'instance', choices: ['docker-x64','docker-aarch64'], description: 'Instance type for running tests')
        string(name: 'TESTING_BRANCH', defaultValue: 'main', description: 'psmdb-testing repo branch')
        string(name: 'PYTEST_PARAMS', defaultValue: '', description: 'Extra args passed to pytest')
        booleanParam(name: 'ADD_JENKINS_MARKED_TESTS', defaultValue: false, description: 'Include tests with jenkins marker')
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
                    label "${params.instance}"
                }
                axes {
                    axis {
                        name 'TEST'
                        values 'logical', 'physical', 'incremental', 'external', 'load'
                    }
                }
                stages {
                    stage ('Run tests') {
                        steps {
                            withCredentials([string(credentialsId: 'olexandr_zephyr_token', variable: 'ZEPHYR_TOKEN'),
                            string(credentialsId: 'KMS_ID', variable: 'KMS_ID'),
                            file(credentialsId: 'PBM-AWS-S3', variable: 'PBM_AWS_S3_YML'),
                            file(credentialsId: 'PBM-GCS-S3', variable: 'PBM_GCS_S3_YML'),
                            file(credentialsId: 'PBM-GCS-HMAC-S3', variable: 'PBM_GCS_HMAC_S3_YML'),
                            file(credentialsId: 'PBM-AZURE', variable: 'PBM_AZURE_YML')]) {
                                sh """
                                    docker kill \$(docker ps -a -q) || true
                                    docker rm \$(docker ps -a -q) || true
                                    docker rmi -f \$(docker images -q | uniq) || true
                                    sudo rm -rf ./*

                                    if [ ! -f "/usr/local/bin/docker-compose" ] ; then
                                        if [ ${params.instance} = "docker-aarch64" ]; then
                                            sudo curl -SL https://github.com/docker/compose/releases/download/v2.16.0/docker-compose-linux-aarch64 -o /usr/local/bin/docker-compose
                                        else
                                            sudo curl -SL https://github.com/docker/compose/releases/download/v2.16.0/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose
                                        fi
                                        sudo chmod +x /usr/local/bin/docker-compose
                                    fi

                                    git clone https://github.com/Percona-QA/psmdb-testing
                                    cd psmdb-testing
                                    git checkout ${params.TESTING_BRANCH}

                                    cd pbm-functional/pytest
                                    cp $PBM_AWS_S3_YML ./conf/pbm/aws.yaml
                                    cp $PBM_GCS_S3_YML ./conf/pbm/gcs.yaml
                                    cp $PBM_GCS_HMAC_S3_YML ./conf/pbm/gcs_hmac.yaml
                                    cp $PBM_AZURE_YML ./conf/pbm/azure.yaml
                                    if [ "${ADD_JENKINS_MARKED_TESTS}" = "true" ]; then JENKINS_FLAG="--jenkins"; else JENKINS_FLAG=""; fi
                                    docker-compose build
                                    docker-compose up -d
                                    if [ -n "${params.PYTEST_PARAMS}" ]; then
                                        FULL_EXPR="${TEST} and ${params.PYTEST_PARAMS}"
                                    else
                                        FULL_EXPR="${TEST}"
                                    fi
                                    KMS_ID="${KMS_ID}" docker-compose run test pytest -s --junitxml=junit.xml \$JENKINS_FLAG -k "\$FULL_EXPR" || true
                                    docker-compose down -v --remove-orphans
                                    curl -H "Content-Type:multipart/form-data" -H "Authorization: Bearer ${ZEPHYR_TOKEN}" -F "file=@junit.xml;type=application/xml" 'https://api.zephyrscale.smartbear.com/v2/automations/executions/junit?projectKey=PBM' -F 'testCycle={"name":"${JOB_NAME}-${BUILD_NUMBER}","customFields": { "PBM branch": "${PBM_BRANCH}","PSMDB docker image": "${PSMDB}","instance": "${instance}"}};type=application/json' -i || true
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
