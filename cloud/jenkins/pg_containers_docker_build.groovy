void build(String IMAGE_POSTFIX){
    sh """
        cd ./source/
        for PG_VER in 14 13 12; do
            docker build --no-cache --squash --build-arg PG_MAJOR=\${PG_VER} \
                -t perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX} \
                -f ./postgresql-containers/build/${IMAGE_POSTFIX}/Dockerfile ./postgresql-containers
        done
    """
}
void checkImageForDocker(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            sg docker -c '
                IMAGE_NAME='percona-postgresql-operator'
                docker login -u '${USER}' -p '${PASS}'
                for PG_VER in 14 13 12; do
                    TrityHightLog="$WORKSPACE/trivy-hight-\$IMAGE_NAME-ppg\${PG_VER}-${IMAGE_POSTFIX}.log"
                    TrityCriticaltLog="$WORKSPACE/trivy-critical-\$IMAGE_NAME-ppg\${PG_VER}-${IMAGE_POSTFIX}.log"
                    /usr/local/bin/trivy -o \$TrityHightLog --ignore-unfixed --exit-code 0 --severity HIGH --quiet \
                        perconalab/\$IMAGE_NAME:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}

                    /usr/local/bin/trivy -o \$TrityCriticaltLog --ignore-unfixed --exit-code 0 --severity CRITICAL --quiet \
                        perconalab/\$IMAGE_NAME:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}

                    if [ ! -s \$TrityHightLog ]; then
                        rm -rf \$TrityHightLog
                    fi

                    if [ ! -s \$TrityCriticaltLog ]; then
                        rm -rf \$TrityCriticaltLog
                    fi
                done
            '

        """
    }
}
void checkImageForCVE(String IMAGE_POSTFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),string(credentialsId: 'SYSDIG-API-KEY', variable: 'SYSDIG_API_KEY')]) {
        sh """
            IMAGE_NAME='percona-postgresql-operator'
            for PG_VER in 14 13 12; do
                docker run -v \$(pwd):/tmp/pgo --rm quay.io/sysdig/secure-inline-scan:2 perconalab/\$IMAGE_NAME:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX} --sysdig-token '${SYSDIG_API_KEY}' --sysdig-url https://us2.app.sysdig.com -r /tmp/pgo
            done
        """
    }
}
void pushImageToDocker(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
        sh """
            sg docker -c '
                if [ ! -d ~/.docker/trust/private ]; then
                    mkdir -p /home/ec2-user/.docker/trust/private
                    cp "${docker_key}" ~/.docker/trust/private/
                fi

                docker login -u '${USER}' -p '${PASS}'
                export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                for PG_VER in 14 13 12; do
                    docker trust sign perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}
                    docker push perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}
                done
                docker logout
            '
        """
    }
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-docker repository',
            name: 'GIT_PD_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-docker',
            description: 'percona/percona-docker repository',
            name: 'GIT_PD_REPO')
    }
    agent {
         label 'docker'
    }
    environment {
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                """
                stash includes: "cloud/**", name: "cloud"
            }
        }
        stage('Build PG database related docker images') {
            steps {
                sh '''
                    sudo rm -rf cloud
                '''
                unstash "cloud"
                sh """
                   sudo rm -rf source
                   export GIT_REPO=$GIT_PD_REPO
                   export GIT_BRANCH=$GIT_PD_BRANCH
                   ./cloud/local/checkout
                """
                retry(3) {
                    build('pgbackrest-repo')
                }
                retry(3) {
                    build('pgbackrest')
                }
                retry(3) {
                    build('pgbouncer')
                }
                retry(3) {
                    build('postgres-ha')
                }
                retry(3) {
                    build('pgbadger')
                }
            }
        }
        stage('Push Images to Docker registry') {
            steps {
                pushImageToDocker('pgbackrest-repo')
                pushImageToDocker('pgbackrest')
                pushImageToDocker('pgbouncer')
                pushImageToDocker('postgres-ha')
                pushImageToDocker('pgbadger')
            }
        }
        stage('Check Docker images') {
            steps {
                checkImageForDocker('pgbackrest-repo')
                checkImageForDocker('pgbackrest')
                checkImageForDocker('pgbouncer')
                checkImageForDocker('postgres-ha')
                checkImageForDocker('pgbadger')
                sh '''
                   CRITICAL=$(ls trivy-critical-*) || true
                   if [ -n "$CRITICAL" ]; then
                       exit 1
                   fi
                '''
            }
        }
        stage('Check PG Docker images for CVE') {
            steps {
                checkImageForCVE('pgbackrest-repo')
                checkImageForCVE('pgbackrest')
                checkImageForCVE('pgbouncer')
                checkImageForCVE('postgres-ha')
                checkImageForCVE('pgbadger')
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
            archiveArtifacts artifacts: '*.pdf', allowEmptyArchive: true
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PG docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
