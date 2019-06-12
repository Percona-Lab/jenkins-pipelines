void pushArtifactFile(String FILE_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git -C source rev-parse --short HEAD)
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git -C source rev-parse --short HEAD)
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
        """
    }
}
void runTest(String TEST_NAME) {
    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
    VERSION = "${env.GIT_BRANCH}-$GIT_SHORT_COMMIT"
    FILE_NAME = "$VERSION-$TEST_NAME-minikube"

    popArtifactFile("$FILE_NAME")
    sh """
        if [ -f "FILE_NAME" ]; then
            echo Skip $TEST_NAME test
        else
            cd ./source
            export IMAGE=perconalab/percona-xtradb-cluster-operator:${env.GIT_BRANCH}
            if [ -n "${IMAGE_PXC}" ]; then
                export IMAGE_PXC=${IMAGE_PXC}
            fi

            if [ -n "${IMAGE_PROXY}" ]; then
                export IMAGE_PROXY=${IMAGE_PROXY}
            fi

            ./e2e-tests/$TEST_NAME/run
            touch $FILE_NAME
        fi
    """
    pushArtifactFile("$FILE_NAME")

    sh """
        rm -rf $FILE_NAME
    """
}
void installRpms() {
    sh '''
        wget https://repo.percona.com/apt/percona-release_latest.$(lsb_release -sc)_all.deb
        sudo dpkg -i percona-release_latest.$(lsb_release -sc)_all.deb
        sudo percona-release setup ps80
        sudo apt-get update
        sudo apt-get install -y percona-xtrabackup-80 jq
    '''
}
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
        string(
            defaultValue: '',
            description: 'PXC image',
            name: 'IMAGE_PXC')
        string(
            defaultValue: '',
            description: 'PXC proxy image',
            name: 'IMAGE_PROXY')
    }
    agent {
         label 'micro-amazon' 
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            agent { label 'docker' }
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                stash includes: "source/**", name: "sourceFILES", useDefaultExcludes: false
            }
        }
        
        stage('Build docker image') {
            agent { label 'docker' }
            steps {
                sh '''
                    sudo rm -rf source
                '''
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        cd ./source/
                        sg docker -c "
                            docker login -u '${USER}' -p '${PASS}'
                            export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                            ./e2e-tests/build
                            docker logout
                        "
                        sudo rm -rf ./build
                    '''
                }
            }
        }
        stage('Tests') {
            agent { label 'virtualbox' }
                steps {
                    sh '''
                        curl -s https://storage.googleapis.com/kubernetes-helm/helm-v2.12.1-linux-amd64.tar.gz \
                            | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                        curl -s -L https://github.com/openshift/origin/releases/download/v3.11.0/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit.tar.gz \
                            | sudo tar -C /usr/local/bin --strip-components 1 --wildcards -zxvpf - '*/oc'

                        echo 'deb [signed-by=/usr/share/keyrings/cloud.google.gpg] http://packages.cloud.google.com/apt cloud-sdk main' | sudo tee -a /etc/apt/sources.list.d/google-cloud-sdk.list
                        curl https://packages.cloud.google.com/apt/doc/apt-key.gpg \
                            | sudo apt-key --keyring /usr/share/keyrings/cloud.google.gpg add -
                        sudo apt-get update
                        sudo apt-get -y install kubectl google-cloud-sdk

                        sudo curl -Lo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
                        sudo chmod +x /usr/local/bin/minikube
                        minikube start --vm-driver=virtualbox --dns-domain=percona.com --memory=4096 --cpus=3 --kubernetes-version v1.12.8
                    '''
                    
                    unstash "sourceFILES"
                    installRpms()
                    unstash "sourceFILES"
                    runTest('init-deploy')
                    runTest('limits')
                    runTest('monitoring')
                    runTest('affinity')
                    runTest('scaling')
                    runTest('scaling-proxysql')
                    runTest('recreate')
                    runTest('demand-backup')
                    runTest('scheduled-backup')
            }
        }
    }

    post {
        always {
            sh '''
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
