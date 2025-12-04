library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        choice(name: 'PCSM_REPO_CH', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PCSM_VERSION', defaultValue: '1.0.1-1', description: 'PCSM version')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab','DockerHub'], description: 'Target repo for docker image, use DockerHub for release only')
        choice(name: 'LATEST', choices: ['no','yes'], description: 'Tag image as latest')
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
                    MAJ_VER=\$(echo ${params.PCSM_VERSION} | awk -F "." '{print \$1}')
                    echo \$MAJ_VER
                    MIN_VER=\$(echo ${params.PCSM_VERSION} | awk -F "-" '{print \$1}')
                    echo \$MIN_VER
                    git clone -b pcsm_docker https://github.com/percona/percona-docker
                    cd percona-docker/percona-cluster-sync-mongodb
                    sed -E "s/ENV PCSM_VERSION (.+)/ENV PCSM_VERSION ${params.PCSM_VERSION}/" -i Dockerfile.aarch64
                    sed -E "s/ENV PCSM_REPO_CH (.+)/ENV PCSM_REPO_CH ${params.PCSM_REPO_CH}/" -i Dockerfile.aarch64
                    docker build . -t percona-cluster-sync-mongodb -f Dockerfile.aarch64
                    """
            }
        }
        stage ('Run trivy analyzer') {
            steps {
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-ARM64.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-ARM64.tar.gz -C /usr/local/bin/
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    curl https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/docker/trivyignore -o ".trivyignore"
                    if [ ${params.PCSM_REPO_CH} = "release" ]; then
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-cluster-sync-mongodb
                    else
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-cluster-sync-mongodb
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
                         MAJ_VER=\$(echo ${params.PCSM_VERSION} | awk -F "." '{print \$1}')
                         MIN_VER=\$(echo ${params.PCSM_VERSION} | awk -F "-" '{print \$1}')
                         docker tag percona-cluster-sync-mongodb perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-arm64
                         docker push perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-arm64
                         docker tag percona-cluster-sync-mongodb perconalab/percona-cluster-sync-mongodb:\$MIN_VER-arm64
                         docker push perconalab/percona-cluster-sync-mongodb:\$MIN_VER-arm64

                         docker manifest create perconalab/percona-cluster-sync-mongodb:\$MIN_VER \
                            perconalab/percona-cluster-sync-mongodb:\$MIN_VER-amd64 \
                            perconalab/percona-cluster-sync-mongodb:\$MIN_VER-arm64
                         docker manifest annotate perconalab/percona-cluster-sync-mongodb:\$MIN_VER \
                            perconalab/percona-cluster-sync-mongodb:\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-cluster-sync-mongodb:\$MIN_VER \
                            perconalab/percona-cluster-sync-mongodb:\$MIN_VER-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-cluster-sync-mongodb:\$MIN_VER
                         docker manifest push perconalab/percona-cluster-sync-mongodb:\$MIN_VER

                         docker manifest create perconalab/percona-cluster-sync-mongodb:\$MAJ_VER \
                            perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-amd64 \
                            perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-arm64
                         docker manifest annotate perconalab/percona-cluster-sync-mongodb:\$MAJ_VER \
                            perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-cluster-sync-mongodb:\$MAJ_VER \
                            perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-cluster-sync-mongodb:\$MAJ_VER
                         docker manifest push perconalab/percona-cluster-sync-mongodb:\$MAJ_VER

                         if [ ${params.LATEST} = "yes" ]; then
                           docker manifest create perconalab/percona-cluster-sync-mongodb:latest \
                              perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-amd64 \
                              perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-arm64
                           docker manifest annotate perconalab/percona-cluster-sync-mongodb:latest \
                              perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                           docker manifest annotate perconalab/percona-cluster-sync-mongodb:latest \
                              perconalab/percona-cluster-sync-mongodb:\$MAJ_VER-amd64 --os linux --arch amd64
                           docker manifest inspect perconalab/percona-cluster-sync-mongodb:latest
                           docker manifest push perconalab/percona-cluster-sync-mongodb:latest
			 fi
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
                         MAJ_VER=\$(echo ${params.PCSM_VERSION} | awk -F "." '{print \$1}')
                         MIN_VER=\$(echo ${params.PCSM_VERSION} | awk -F "-" '{print \$1}')
                         docker tag percona-cluster-sync-mongodb percona/percona-cluster-sync-mongodb:\$MAJ_VER-arm64
                         docker push percona/percona-cluster-sync-mongodb:\$MAJ_VER-arm64
                         docker tag percona-cluster-sync-mongodb percona/percona-cluster-sync-mongodb:\$MIN_VER-arm64
                         docker push percona/percona-cluster-sync-mongodb:\$MIN_VER-arm64

                         docker manifest create percona/percona-cluster-sync-mongodb:\$MIN_VER \
                            percona/percona-cluster-sync-mongodb:\$MIN_VER-amd64 \
                            percona/percona-cluster-sync-mongodb:\$MIN_VER-arm64
                         docker manifest annotate percona/percona-cluster-sync-mongodb:\$MIN_VER \
                            percona/percona-cluster-sync-mongodb:\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-cluster-sync-mongodb:\$MIN_VER \
                            percona/percona-cluster-sync-mongodb:\$MIN_VER-amd64 --os linux --arch amd64
                         docker manifest inspect percona/percona-cluster-sync-mongodb:\$MIN_VER
                         docker manifest push percona/percona-cluster-sync-mongodb:\$MIN_VER

                         docker manifest create percona/percona-cluster-sync-mongodb:\$MAJ_VER \
                            percona/percona-cluster-sync-mongodb:\$MAJ_VER-amd64 \
                            percona/percona-cluster-sync-mongodb:\$MAJ_VER-arm64
                         docker manifest annotate percona/percona-cluster-sync-mongodb:\$MAJ_VER \
                            percona/percona-cluster-sync-mongodb:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate percona/percona-cluster-sync-mongodb:\$MAJ_VER \
                            percona/percona-cluster-sync-mongodb:\$MAJ_VER-amd64 --os linux --arch amd64
                         docker manifest inspect percona/percona-cluster-sync-mongodb:\$MAJ_VER
                         docker manifest push percona/percona-cluster-sync-mongodb:\$MAJ_VER

                         if [ ${params.LATEST} = "yes" ]; then
                           docker manifest create percona/percona-cluster-sync-mongodb:latest \
                              percona/percona-cluster-sync-mongodb:\$MAJ_VER-amd64 \
                              percona/percona-cluster-sync-mongodb:\$MAJ_VER-arm64
                           docker manifest annotate percona/percona-cluster-sync-mongodb:latest \
                              percona/percona-cluster-sync-mongodb:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                           docker manifest annotate percona/percona-cluster-sync-mongodb:latest \
                              percona/percona-cluster-sync-mongodb:\$MAJ_VER-amd64 --os linux --arch amd64
                           docker manifest inspect percona/percona-cluster-sync-mongodb:latest
                           docker manifest push percona/percona-cluster-sync-mongodb:latest
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
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: Building of PCSM ${PCSM_VERSION} for ARM, repo ${PCSM_REPO_CH} succeed")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: Building of PCSM ${PCSM_VERSION} for ARM, repo ${PCSM_REPO_CH} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: Building of PCSM ${PCSM_VERSION} for ARM, repo ${PCSM_REPO_CH} failed - [${BUILD_URL}]")
        }
    }
}

