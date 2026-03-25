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
        choice(name: 'PPG_REPO', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PPG_VERSION', defaultValue: '16.13-1', description: 'PPG version')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab'], description: 'Target repo for docker custom image, use PerconaLab only')
        choice(name: 'LATEST', choices: ['no','yes'], description: 'Tag image as latest')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PPG_REPO}-${params.PPG_VERSION}-extras"
                }
            }
        }
        stage ('Build image') {
            steps {
                sh """
                    MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                    echo \$MAJ_VER
                    MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                    echo \$MIN_VER
                    export DOCKER_CLI_EXPERIMENTAL=enabled
                    sudo mkdir -p /usr/libexec/docker/cli-plugins/
                    sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-amd64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo systemctl restart docker
                    sudo apt-get install -y qemu-system binfmt-support qemu-user-static
                    sudo qemu-system-x86_64 --version
                    sudo lscpu | grep -q 'sse4_2' && grep -q 'popcnt' /proc/cpuinfo && echo "Supports x86-64-v2" || echo "Does NOT support x86-64-v2"
                    sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes

                    git clone https://github.com/Manika-Percona/percona-docker
                    cd percona-docker/percona-distribution-postgresql-\$MAJ_VER-extras
                    sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis
                    sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile-postgis
                    sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis
                    sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis
                    export DOCKER_BUILDKIT=1
                    docker build --platform=linux/amd64 --no-cache --provenance=false -t percona-distribution-postgresql-custom:\$MAJ_VER -f Dockerfile-postgis .
                    """
            }
        }
        stage ('Run trivy analyzer') {
            steps {
                installTrivy(method: 'binary', junitTpl: true)
                sh """
                    MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                    MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                    if [ ${params.PPG_REPO} = "release" ]; then
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL percona-distribution-postgresql-custom:\$MAJ_VER
                    else
                        /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-distribution-postgresql-custom:\$MAJ_VER
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
                         MAJ_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f1 -d'.')
                         MIN_VER=\$(echo ${params.PPG_VERSION} | cut -f1 -d'-' | cut -f2 -d'.')
                         docker tag percona-distribution-postgresql-custom:\$MAJ_VER perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}-amd64
                         docker push perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}-amd64
                         docker tag percona-distribution-postgresql-custom:\$MAJ_VER perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER-amd64
                         docker push perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER-amd64
                         docker tag percona-distribution-postgresql-custom:\$MAJ_VER perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-amd64
                         docker push perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-amd64
                         if [ ${params.LATEST} = "yes" ]; then
                            docker tag percona-distribution-postgresql-custom:\$MAJ_VER perconalab/percona-distribution-postgresql-custom:latest
                            docker push perconalab/percona-distribution-postgresql-custom:latest
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
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} repo ${PPG_REPO} succeed")
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} repo ${PPG_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} repo ${PPG_REPO} failed - [${BUILD_URL}]")
        }
    }
}
