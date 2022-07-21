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
                if [ ! -f junit.tpl ]; then
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                fi

                for PG_VER in 14 13 12; do
                    TrivyLog="$WORKSPACE/trivy-hight-\$IMAGE_NAME-ppg\${PG_VER}-${IMAGE_POSTFIX}.xml"
                    /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @junit.tpl -o \$TrivyLog --ignore-unfixed --timeout 20m --exit-code 0 \
                        --severity HIGH,CRITICAL perconalab/\$IMAGE_NAME:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}

                done
            '

        """
    }
}
void checkImageForCVE(String IMAGE_POSTFIX){
    try {
        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),string(credentialsId: 'SYSDIG-API-KEY', variable: 'SYSDIG_API_KEY')]) {
            sh """
                IMAGE_NAME='percona-postgresql-operator'
                docker run -v \$(pwd):/tmp/pgo --rm quay.io/sysdig/secure-inline-scan:2 perconalab/\$IMAGE_NAME:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} --sysdig-token '${SYSDIG_API_KEY}' --sysdig-url https://us2.app.sysdig.com -r /tmp/pgo
            """
        }
    } catch (error) {
        echo "${IMAGE_POSTFIX} has some CVE error(s)."
        currentBuild.result = 'FAILURE'
    }
}
void pushImageToDocker(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),
                      file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key'),
                      [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            sg docker -c '
                if [ ! -d ~/.docker/trust/private ]; then
                    mkdir -p /home/ec2-user/.docker/trust/private
                    cp "${docker_key}" ~/.docker/trust/private/
                fi

                docker login -u '${USER}' -p '${PASS}'
                aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $ECR
                export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                for PG_VER in 14 13 12; do
                    docker trust sign perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}
                    docker push perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}
                    docker tag perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX} $ECR/perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}
                    docker push $ECR/perconalab/percona-postgresql-operator:${GIT_PD_BRANCH}-ppg\${PG_VER}-${IMAGE_POSTFIX}
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
        ECR = "119175775298.dkr.ecr.us-east-1.amazonaws.com"
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
                    sudo git config --global --add safe.directory '*'
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
        stage('Trivy Checks') {
            parallel {
                stage('pgbackrest-repo'){
                    steps {
                        checkImageForDocker('pgbackrest-repo')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pgbackrest-repo.xml"
                        }
                    }
                }
                stage('pgbackrest'){
                    steps {
                        checkImageForDocker('pgbackrest')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pgbackrest.xml"
                        }
                    }
                }
                stage('pgbouncer'){
                    steps {
                        checkImageForDocker('pgbouncer')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pgbouncer.xml"
                        }
                    }
                }
                stage('postgres-ha'){
                    steps {
                        checkImageForDocker('postgres-ha')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-postgres-ha.xml"
                        }
                    }
                }
                stage('pgbadger'){
                    steps {
                        checkImageForDocker('pgbadger')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pgbadger.xml"
                        }
                    }
                }
            }
        }
        stage('Check PG Docker images for CVE') {
            parallel {
                stage('ppg12') {
                    steps {
                        checkImageForCVE('ppg12-pgbackrest-repo')
                        checkImageForCVE('ppg12-pgbackrest')
                        checkImageForCVE('ppg12-pgbouncer')
                        checkImageForCVE('ppg12-postgres-ha')
                        checkImageForCVE('ppg12-pgbadger')
                    }
                }
                stage('ppg13') {
                    steps {
                        checkImageForCVE('ppg13-pgbackrest-repo')
                        checkImageForCVE('ppg13-pgbackrest')
                        checkImageForCVE('ppg13-pgbouncer')
                        checkImageForCVE('ppg13-postgres-ha')
                        checkImageForCVE('ppg13-pgbadger')
                    }
                }
                stage('ppg14') {
                    steps {
                        checkImageForCVE('ppg14-pgbackrest-repo')
                        checkImageForCVE('ppg14-pgbackrest')
                        checkImageForCVE('ppg14-pgbouncer')
                        checkImageForCVE('ppg14-postgres-ha')
                        checkImageForCVE('ppg14-pgbadger')
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.pdf', allowEmptyArchive: true
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PG docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PG docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
