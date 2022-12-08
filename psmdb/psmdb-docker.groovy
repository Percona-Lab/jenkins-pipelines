library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
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
        choice(name: 'LATEST', choices: ['no','yes'], description: 'Tag image as latest')
        choice(name: 'DEBUG', choices: ['no','yes'], description: 'Additionally build debug image')
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
                sh """
                    MAJ_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "." '{print \$1"."\$2}')
                    echo \$MAJ_VER
                    MIN_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "-" '{print \$1}')
                    echo \$MIN_VER
                    git clone https://github.com/percona/percona-docker
                    cd percona-docker/percona-server-mongodb-\$MAJ_VER
                    sed -E "s/ENV PSMDB_VERSION (.+)/ENV PSMDB_VERSION ${params.PSMDB_VERSION}/" -i Dockerfile
                    sed -E "s/ENV PSMDB_REPO (.+)/ENV PSMDB_REPO ${params.PSMDB_REPO}/" -i Dockerfile
                    docker build . -t percona-server-mongodb 
                    if [ ${params.DEBUG} = "yes" ]; then
                       sed -E "s/FROM percona(.+)/FROM percona-server-mongodb/" -i Dockerfile.debug
                       docker build . -f Dockerfile.debug -t percona-server-mongodb-debug
                    fi
                    """
            }
        }
        stage ('Run trivy analyzer') {
            steps {
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                    if [ ${params.PSMDB_REPO} = "release" ]; then
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-server-mongodb
                    else
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-server-mongodb
                    fi
               """
            }
            post {
                always {
                    junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
        stage ('Push image to aws ecr') {
            when {
                environment name: 'PSMDB_REPO', value: 'experimental'
            }
            steps {
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                     sh """
                         curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                         if [ -f "/usr/bin/yum" ] ; then sudo yum install -y unzip ; else sudo apt-get update && apt-get -y install unzip ; fi
                         unzip -o awscliv2.zip
                         sudo ./aws/install
                         aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/e7j3v3n0
                         docker tag percona-server-mongodb public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-${params.PSMDB_VERSION}
                         docker push public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-${params.PSMDB_VERSION}
                         if [ ${params.DEBUG} = "yes" ]; then
                            docker tag percona-server-mongodb-debug public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-${params.PSMDB_VERSION}-debug
                            docker push public.ecr.aws/e7j3v3n0/psmdb-build:psmdb-${params.PSMDB_VERSION}-debug
                         fi
                     """
                }
            }
        }
        stage ('Push images to perconalab') {
            when {
                environment name: 'PSMDB_REPO', value: 'testing'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "." '{print \$1"."\$2}')
                         MIN_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "-" '{print \$1}')
                         docker tag percona-server-mongodb perconalab/percona-server-mongodb:${params.PSMDB_VERSION}
                         docker push perconalab/percona-server-mongodb:${params.PSMDB_VERSION}
                         docker tag percona-server-mongodb perconalab/percona-server-mongodb:\$MIN_VER
                         docker push perconalab/percona-server-mongodb:\$MIN_VER
                         docker tag percona-server-mongodb perconalab/percona-server-mongodb:\$MAJ_VER
                         docker push perconalab/percona-server-mongodb:\$MAJ_VER 
                         if [ ${params.LATEST} = "yes" ]; then
                             docker tag percona-server-mongodb perconalab/percona-server-mongodb:latest
                             docker push perconalab/percona-server-mongodb:latest
                         fi
                         if [ ${params.DEBUG} = "yes" ]; then
                             docker tag percona-server-mongodb-debug perconalab/percona-server-mongodb:${params.PSMDB_VERSION}-debug
                             docker push perconalab/percona-server-mongodb:${params.PSMDB_VERSION}-debug
                         fi
                     """
                }
            }
        }
        stage ('Push images to official percona docker registry') {
            when {
                environment name: 'PSMDB_REPO', value: 'release'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "." '{print \$1"."\$2}')
                         MIN_VER=\$(echo ${params.PSMDB_VERSION} | awk -F "-" '{print \$1}')
                         docker tag percona-server-mongodb percona/percona-server-mongodb:${params.PSMDB_VERSION}
                         docker push percona/percona-server-mongodb:${params.PSMDB_VERSION}
                         docker tag percona-server-mongodb percona/percona-server-mongodb:\$MIN_VER
                         docker push percona/percona-server-mongodb:\$MIN_VER
                         docker tag percona-server-mongodb percona/percona-server-mongodb:\$MAJ_VER
                         docker push percona/percona-server-mongodb:\$MAJ_VER
                         if [ ${params.LATEST} = "yes" ]; then
                             docker tag percona-server-mongodb percona/percona-server-mongodb:latest
                             docker push percona/percona-server-mongodb:latest
                         fi
                         if [ ${params.DEBUG} = "yes" ]; then
                             docker tag percona-server-mongodb-debug percona/percona-server-mongodb:${params.PSMDB_VERSION}-debug
                             docker push percona/percona-server-mongodb:${params.PSMDB_VERSION}-debug
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
            slackNotify("#opensource-psmdb", "#00FF00", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} succeed")
        }
        unstable {
            slackNotify("#opensource-psmdb", "#F6F930", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#opensource-psmdb", "#FF0000", "[${JOB_NAME}]: Building of PSMDB ${PSMDB_VERSION} repo ${PSMDB_REPO} failed - [${BUILD_URL}]")
        }
    }
}
