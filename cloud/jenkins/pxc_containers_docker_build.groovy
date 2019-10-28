void build(String IMAGE_PREFIX){
    sh """
        cd ./source/
        if [ ${IMAGE_PREFIX} = pxc ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} -f pxc-57/Dockerfile.k8s pxc-57
        elif [ ${IMAGE_PREFIX} = proxysql ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} -f proxysql/Dockerfile.k8s proxysql
        else
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} images/${IMAGE_PREFIX}-image
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
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
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
            description: 'Tag/Branch for Percona-Lab/percona-openshift repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/Percona-Lab/percona-openshift',
            description: 'Percona-Lab/percona-openshift repository',
            name: 'GIT_REPO')
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
                    sudo rm -rf source
                    ./cloud/local/checkout
                """
                stash includes: "cloud/**", name: "cloud"
                stash includes: "source/**", name: "sourceFILES"
            }
        }
        stage('Build docker images') {
            steps {
                unstash "sourceFILES"
                retry(3) {
                    build('backup')
                }
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
                    build('proxysql')
                }
                retry(3) {
                    build('pxc')
                }
            }
        }
        stage('Push Images to Docker registry') {
            steps {
                pushImageToDocker('pxc')
                pushImageToDocker('proxysql')
                pushImageToDocker('backup')
            }
        }
        stage('Push Images to RHEL registry') {
            steps {
                pushImageToRhel('pxc')
                pushImageToRhel('proxysql')
                pushImageToRhel('backup')
            }
        }
        stage('Check Docker images') {
            steps {
                checkImageForDocker('pxc')
                checkImageForDocker('proxysql')
                checkImageForDocker('backup')
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
