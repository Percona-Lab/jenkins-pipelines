pipeline {
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
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
                    sudo rm -rf source
                    ./cloud/local/checkout
                """
                stash includes: "source/**", name: "sourceFILES"
            }
        }
        
        stage('Build docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        cd ./source/
                        sg docker -c "
                            docker login -u '${USER}' -p '${PASS}'
                            RHEL=1 ./e2e-tests/build
                            docker logout
                        "
                        sudo rm -rf ./build
                    '''
                }
            }
        }

        stage('Push docker image to dockerhub') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
                    sh '''
                        sg docker -c "
                            mkdir -p /home/ec2-user/.docker/trust/private
                            cp "${docker_key}" ~/.docker/trust/private/

                            docker login -u '${USER}' -p '${PASS}'
                            export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                            docker trust sign perconalab/percona-xtradb-cluster-operator:master
                            docker push perconalab/percona-xtradb-cluster-operator:master
                            docker logout
                        "
                    '''
                }
            }
        }
        stage('Push docker image to RHEL registry') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'scan.connect.redhat.com-pxc-operator', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        GIT_FULL_COMMIT=\$(git rev-parse HEAD)
                        GIT_SHORT_COMMIT=\${GIT_FULL_COMMIT:0:7}
                        IMAGE_ID=\$(docker images -q perconalab/percona-xtradb-cluster-operator:master)
                        IMAGE_NAME='percona-xtradb-cluster-operator'
                        IMAGE_TAG="master-\$GIT_SHORT_COMMIT"
                        if [ -n "\${IMAGE_ID}" ]; then
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}' scan.connect.redhat.com
                                docker tag \${IMAGE_ID} scan.connect.redhat.com/ospid-f1113c97-aabd-410b-a15e-7f013dee2aa7/\$IMAGE_NAME:\$IMAGE_TAG
                                docker push scan.connect.redhat.com/ospid-f1113c97-aabd-410b-a15e-7f013dee2aa7/\$IMAGE_NAME:\$IMAGE_TAG
                                docker logout
                            "
                        fi 
                    '''
                }
            }
        }
        stage('Check PXC docker image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        IMAGE_NAME='percona-xtradb-cluster-operator'
                        TrityHightLog="$WORKSPACE/trivy-hight-pxc.log"
                        TrityCriticaltLog="$WORKSPACE/trivy-critical-pxc.log"

                        sg docker -c "
                            docker login -u '${USER}' -p '${PASS}'
                            /usr/local/bin/trivy -q image -o \$TrityHightLog --timeout 5m0s --ignore-unfixed --exit-code 0 --severity HIGH  perconalab/\$IMAGE_NAME:master
                            /usr/local/bin/trivy -q image -o \$TrityCriticaltLog --timeout 5m0s --ignore-unfixed --exit-code 1 --severity CRITICAL  perconalab/\$IMAGE_NAME:master
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
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*-pxc.log', allowEmptyArchive: true
            deleteDir()
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PXC image failed. Please check the log ${BUILD_URL}"
        }
    }
}
