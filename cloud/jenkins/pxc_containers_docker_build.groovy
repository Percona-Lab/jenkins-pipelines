void build(String IMAGE_PREFIX){
    sh """
        cd ./source/
        if [ ${IMAGE_PREFIX} = pxc5.7 ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} -f percona-xtradb-cluster-5.7/Dockerfile.k8s percona-xtradb-cluster-5.7
            docker build --build-arg DEBUG=1 --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX}-debug -f percona-xtradb-cluster-5.7/Dockerfile.k8s percona-xtradb-cluster-5.7
        elif [ ${IMAGE_PREFIX} = pxc8.0 ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} -f percona-xtradb-cluster-8.0/Dockerfile.k8s percona-xtradb-cluster-8.0
            docker build --build-arg DEBUG=1 --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX}-debug -f pxc-80/Dockerfile.k8s percona-xtradb-cluster-8.0
        elif [ ${IMAGE_PREFIX} = proxysql ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} -f proxysql/Dockerfile.k8s proxysql
        elif [ ${IMAGE_PREFIX} = pxc5.7-backup ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} -f percona-xtradb-cluster-5.7-backup/Dockerfile percona-xtradb-cluster-5.7-backup
        elif [ ${IMAGE_PREFIX} = pxc8.0-backup ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} -f percona-xtradb-cluster-8.0-backup/Dockerfile percona-xtradb-cluster-8.0-backup
        elif [ ${IMAGE_PREFIX} = haproxy ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} -f haproxy/Dockerfile haproxy
        fi
    """
}
void checkImageForDocker(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            IMAGE_NAME='percona-xtradb-cluster-operator'
            TrityHightLog="$WORKSPACE/trivy-hight-\$IMAGE_NAME-${IMAGE_PREFIX}.log"
            TrityCriticaltLog="$WORKSPACE/trivy-critical-\$IMAGE_NAME-${IMAGE_PREFIX}.log"

            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                /usr/local/bin/trivy -o \$TrityHightLog --ignore-unfixed --exit-code 0 --severity HIGH --quiet --auto-refresh perconalab/\$IMAGE_NAME:master-${IMAGE_PREFIX}
                /usr/local/bin/trivy -o \$TrityCriticaltLog --ignore-unfixed --exit-code 0 --severity CRITICAL --quiet --auto-refresh perconalab/\$IMAGE_NAME:master-${IMAGE_PREFIX}
            "

            if [ ! -s \$TrityHightLog ]; then
                rm -rf \$TrityHightLog
            fi

            if [ ! -s \$TrityCriticaltLog ]; then
                rm -rf \$TrityCriticaltLog
            fi
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
                docker trust sign perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX}
                docker push perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX}
                docker logout
            "
        """
    }
}
void pushImageToRhel(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'scan.connect.redhat.com-pxc-containers', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            GIT_FULL_COMMIT=\$(git rev-parse HEAD)
            GIT_SHORT_COMMIT=\${GIT_FULL_COMMIT:0:7}
            IMAGE_ID=\$(docker images -q perconalab/percona-xtradb-cluster-operator:master-\$IMAGE_PREFIX)
            IMAGE_NAME='percona-xtradb-cluster-operator'
            IMAGE_TAG="master-\$GIT_SHORT_COMMIT-\$IMAGE_PREFIX"
            if [ -n "\${IMAGE_ID}" ]; then
                sg docker -c "
                    docker login -u '${USER}' -p '${PASS}' scan.connect.redhat.com
                    docker tag \${IMAGE_ID} scan.connect.redhat.com/ospid-e6379026-6633-4c53-8477-c27d6e2bfc54/\$IMAGE_NAME:\$IMAGE_TAG
                    docker push scan.connect.redhat.com/ospid-e6379026-6633-4c53-8477-c27d6e2bfc54/\$IMAGE_NAME:\$IMAGE_TAG
                    docker logout
                "
            fi
        """
    }
}
pipeline {
    parameters {
        string(
            defaultValue: 'master',
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
            }
        }
        stage('Push Images to RHEL registry') {
            steps {
                pushImageToRhel('pxc5.7')
                pushImageToRhel('pxc8.0')
                pushImageToRhel('pxc5.7-debug')
                pushImageToRhel('pxc8.0-debug')
                pushImageToRhel('proxysql')
                pushImageToRhel('pxc5.7-backup')
                pushImageToRhel('pxc8.0-backup')
                pushImageToRhel('haproxy')
            }
        }
        stage('Check Docker images') {
            steps {
                checkImageForDocker('pxc5.7')
                checkImageForDocker('pxc8.0')
                checkImageForDocker('pxc5.7-debug')
                checkImageForDocker('pxc8.0-debug')
                checkImageForDocker('proxysql')
                checkImageForDocker('pxc5.7-backup')
                checkImageForDocker('pxc8.0-backup')
                checkImageForDocker('haproxy')
                sh '''
                   CRITICAL=$(ls trivy-critical-*) || true
                   if [ -n "$CRITICAL" ]; then
                       exit 1
                   fi
                '''
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PXC docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
