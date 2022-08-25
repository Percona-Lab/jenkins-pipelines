void build(String IMAGE_PREFIX){
    sh """
        cd ./source/
        if [ ${IMAGE_PREFIX} = pxc5.7 ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX} -f percona-xtradb-cluster-5.7/Dockerfile percona-xtradb-cluster-5.7
            docker build --build-arg DEBUG=1 --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX}-debug -f percona-xtradb-cluster-5.7/Dockerfile percona-xtradb-cluster-5.7
        elif [ ${IMAGE_PREFIX} = pxc8.0 ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX} -f percona-xtradb-cluster-8.0/Dockerfile percona-xtradb-cluster-8.0
            docker build --build-arg DEBUG=1 --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX}-debug -f percona-xtradb-cluster-8.0/Dockerfile percona-xtradb-cluster-8.0
        elif [ ${IMAGE_PREFIX} = proxysql ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX} -f proxysql/Dockerfile proxysql
        elif [ ${IMAGE_PREFIX} = pxc5.7-backup ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX} -f percona-xtradb-cluster-5.7-backup/Dockerfile percona-xtradb-cluster-5.7-backup
        elif [ ${IMAGE_PREFIX} = pxc8.0-backup ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX} -f percona-xtradb-cluster-8.0-backup/Dockerfile percona-xtradb-cluster-8.0-backup
        elif [ ${IMAGE_PREFIX} = haproxy ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX} -f haproxy/Dockerfile haproxy
        elif [ ${IMAGE_PREFIX} = logcollector ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX} -f fluentbit/Dockerfile fluentbit
        fi
    """
}
void checkImageForDocker(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            IMAGE_NAME='percona-xtradb-cluster-operator'
            TrivyLog="$WORKSPACE/trivy-hight-\$IMAGE_NAME-${IMAGE_PREFIX}.xml"
            wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl

            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @junit.tpl -o \$TrivyLog --ignore-unfixed  --timeout 10m --exit-code 0 --severity HIGH,CRITICAL perconalab/\$IMAGE_NAME:main-${IMAGE_PREFIX}
            "
        """
    }
}
void pushImageToDocker(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            sg docker -c "
                if [ ! -d ~/.docker/trust/private ]; then
                    mkdir -p /home/ec2-user/.docker/trust/private
                    cp "${docker_key}" ~/.docker/trust/private/
                fi

                docker login -u '${USER}' -p '${PASS}'
                export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                docker trust sign perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX}
                docker push perconalab/percona-xtradb-cluster-operator:main-${IMAGE_PREFIX}
                docker logout
            "
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
                    sudo sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                """
                stash includes: "cloud/**", name: "cloud"
            }
        }
        stage('Build pxc docker images') {
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
                    build('pxc5.7-backup')
                }
                retry(3) {
                    build('pxc8.0-backup')
                }
                retry(3) {
                    build('proxysql')
                }
                retry(3) {
                    build('pxc5.7')
                }
                retry(3) {
                    build('pxc8.0')
                }
                retry(3) {
                    build('haproxy')
                }
                retry(3) {
                    build('logcollector')
                }
            }
        }
        stage('Push Images to Docker registry') {
            steps {
                pushImageToDocker('pxc5.7')
                pushImageToDocker('pxc8.0')
                pushImageToDocker('pxc5.7-debug')
                pushImageToDocker('pxc8.0-debug')
                pushImageToDocker('proxysql')
                pushImageToDocker('pxc5.7-backup')
                pushImageToDocker('pxc8.0-backup')
                pushImageToDocker('haproxy')
                pushImageToDocker('logcollector')
            }
        }
       stage('Trivy Checks') {
            parallel {
                stage('pxc5.7'){
                    steps {
                        checkImageForDocker('pxc5.7')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pxc5.7.xml"
                        }
                    }
                }
                stage('pxc8.0'){
                    steps {
                        checkImageForDocker('pxc8.0')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pxc8.0.xml"
                        }
                    }
                }
                stage('pxc5.7-debug'){
                    steps {
                        checkImageForDocker('pxc5.7-debug')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pxc5.7-debug.xml"
                        }
                    }
                }
                stage('pxc8.0-debug'){
                    steps {
                        checkImageForDocker('pxc8.0-debug')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pxc8.0-debug.xml"
                        }
                    }
                }
                stage('proxysql'){
                    steps {
                        checkImageForDocker('proxysql')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-proxysql.xml"
                        }
                    }
                }
                stage('pxc5.7-backup'){
                    steps {
                        checkImageForDocker('pxc5.7-backup')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pxc5.7-backup.xml"
                        }
                    }
                }
                stage('pxc8.0-backup'){
                    steps {
                        checkImageForDocker('pxc8.0-backup')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pxc8.0-backup.xml"
                        }
                    }
                }
                stage('haproxy'){
                    steps {
                        checkImageForDocker('haproxy')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-haproxy.xml"
                        }
                    }
                }
                stage('logcollector'){
                    steps {
                        checkImageForDocker('logcollector')
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-logcollector.xml"
                        }
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
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PXC docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PXC docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
