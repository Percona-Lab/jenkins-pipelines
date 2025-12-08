library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        choice(name: 'PCSM_REPO_CH', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PCSM_VERSION', defaultValue: '1.0.1-1', description: 'PCSM version')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab','DockerHub'], description: 'Target repo for docker image, use DockerHub for release only')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PCSM_REPO_CH}-${params.PCSM_VERSION}"
                }
            }
        }
        stage ('Build image') {
            steps {
                sh """
                    MIN_VER=\$(echo "${params.PCSM_VERSION}" | awk -F "-" '{print \$1}')
                    git clone https://github.com/percona/percona-docker
                    cd percona-docker/percona-clustersync-mongodb
                    sed -E "s/ENV PCSM_VERSION (.+)/ENV PCSM_VERSION ${params.PCSM_VERSION}/" -i Dockerfile
                    sed -E "s/ENV PCSM_REPO_CH (.+)/ENV PCSM_REPO_CH ${params.PCSM_REPO_CH}/" -i Dockerfile
                    docker build . -t percona-clustersync-mongodb 
                """
            }
        }
        stage ('Run trivy analyzer') {
            steps {
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    curl https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/docker/trivyignore -o ".trivyignore"
                    if [ "${params.PCSM_REPO_CH}" = "release" ]; then
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-clustersync-mongodb
                    else
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-clustersync-mongodb
                    fi
               """
            }
            post {
                always {
                    junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
        stage ('Push images to perconalab') {
            when {
                environment name: 'TARGET_REPO', value: 'PerconaLab'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MIN_VER=\$(echo "${params.PCSM_VERSION}" | awk -F "-" '{print \$1}')
                         docker tag percona-clustersync-mongodb perconalab/percona-clustersync-mongodb:\$MIN_VER-amd64
                         docker push perconalab/percona-clustersync-mongodb:\$MIN_VER-amd64
                     """
                }
            }
        }
        stage ('Push images to official percona docker registry') {
            when {
                environment name: 'TARGET_REPO', value: 'DockerHub'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MIN_VER=\$(echo "${params.PCSM_VERSION}" | awk -F "-" '{print \$1}')
                         docker tag percona-clustersync-mongodb percona/percona-clustersync-mongodb:\$MIN_VER-amd64
                         docker push percona/percona-clustersync-mongodb:\$MIN_VER-amd64
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
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: Building of PCSM ${PCSM_VERSION} repo ${PCSM_REPO_CH} succeed")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: Building of PCSM ${PCSM_VERSION} repo ${PCSM_REPO_CH} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: Building of PCSM ${PCSM_VERSION} repo ${PCSM_REPO_CH} failed - [${BUILD_URL}]")
        }
    }
}

