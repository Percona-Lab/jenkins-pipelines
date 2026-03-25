library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        choice(name: 'PPG_REPO', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'PPG_VERSION', defaultValue: '12.19-1', description: 'PPG version')
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
                    git clone https://github.com/Manika-Percona/percona-docker
                    cd percona-docker/percona-distribution-postgresql-\$MAJ_VER-extras
                    sed -E "s/ENV PPG_VERSION (.+)/ENV PPG_VERSION ${params.PPG_VERSION}/" -i Dockerfile-postgis.aarch64
                    sed -E "s/ENV PPG_REPO (.+)/ENV PPG_REPO ${params.PPG_REPO}/" -i Dockerfile-postgis.aarch64
                    sed -E "s/ENV PPG_MAJOR_VERSION (.+)/ENV PPG_MAJOR_VERSION \$MAJ_VER/" -i Dockerfile-postgis.aarch64
                    sed -E "s/ENV PPG_MINOR_VERSION (.+)/ENV PPG_MINOR_VERSION \$MIN_VER/" -i Dockerfile-postgis.aarch64
                    docker build --platform=linux/arm64 --no-cache -t percona-distribution-postgresql-custom -f Dockerfile-postgis.aarch64 .
                    """
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
                         docker tag percona-distribution-postgresql-custom perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}-arm64
                         docker tag percona-distribution-postgresql-custom perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER-arm64
                         docker tag percona-distribution-postgresql-custom perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-arm64
                         docker push perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}-arm64
                         docker push perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER-arm64
                         docker push perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-arm64

                         docker manifest create --amend perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION} \
                            perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}-amd64 \
                            perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}-arm64
                         docker manifest annotate perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION} \
                            perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION} \
                            perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}
                         docker manifest push perconalab/percona-distribution-postgresql-custom:${params.PPG_VERSION}

                         docker manifest create --amend perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER \
                            perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER-amd64 \
                            perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER-arm64
                         docker manifest annotate perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER \
                            perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER \
                            perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER
                         docker manifest push perconalab/percona-distribution-postgresql-custom:\$MAJ_VER.\$MIN_VER

                         docker manifest create --amend perconalab/percona-distribution-postgresql-custom:\$MAJ_VER \
                            perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-amd64 \
                            perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-arm64
                         docker manifest annotate perconalab/percona-distribution-postgresql-custom:\$MAJ_VER \
                            perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-distribution-postgresql-custom:\$MAJ_VER \
                            perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-distribution-postgresql-custom:\$MAJ_VER
                         docker manifest push perconalab/percona-distribution-postgresql-custom:\$MAJ_VER

                         if [ ${params.LATEST} = "yes" ]; then
                            docker manifest create --amend perconalab/percona-distribution-postgresql-custom:latest \
                               perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-amd64 \
                               perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-arm64
                            docker manifest annotate perconalab/percona-distribution-postgresql-custom:latest \
                               perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-arm64 --os linux --arch arm64 --variant v8
                            docker manifest annotate perconalab/percona-distribution-postgresql-custom:latest \
                               perconalab/percona-distribution-postgresql-custom:\$MAJ_VER-amd64 --os linux --arch amd64
                            docker manifest inspect perconalab/percona-distribution-postgresql-custom:latest
                            docker manifest push perconalab/percona-distribution-postgresql-custom:latest
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
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} for ARM, repo ${PPG_REPO} succeed")
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} fro ARM, repo ${PPG_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of PPG ${PPG_VERSION} for ARM, repo ${PPG_REPO} failed - [${BUILD_URL}]")
        }
    }
}
