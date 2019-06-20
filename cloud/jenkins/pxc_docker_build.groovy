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
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout
                '''
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
                            ./e2e-tests/build
                            docker logout
                        "
                        sudo rm -rf ./build
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
    }

    post {
        always {
            deleteDir()
        }
    }
}
