void pushArtifactFile(String FILE_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git rev-parse --short HEAD)
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git rev-parse --short HEAD)
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
        """
    }
}
void runTest(String TEST_NAME) {
    GIT_SHORT_COMMIT = sh(script: 'git -C source describe --always --dirty', , returnStdout: true).trim()
    VERSION = "${env.GIT_BRANCH}-$GIT_SHORT_COMMIT"
     
    popArtifactFile("$VERSION-$TEST_NAME")
    sh """
        if [ -f "$VERSION-$TEST_NAME" ]; then
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

            source $HOME/google-cloud-sdk/path.bash.inc
            ./e2e-tests/$TEST_NAME/run
            touch $VERSION-$TEST_NAME
        fi
    """
    pushArtifactFile("$VERSION-$TEST_NAME")

    sh """
        rm -rf $VERSION-$TEST_NAME
    """
}
void installRpms() {
    sh """
        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y percona-xtrabackup-80 jq | true
    """
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
            defaultValue: 'master',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
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
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                stash includes: "source/**", name: "sourceFILES"

                installRpms()
                sh '''
                    if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                        rm -rf $HOME/google-cloud-sdk
                        curl https://sdk.cloud.google.com | bash
                    fi

                    source $HOME/google-cloud-sdk/path.bash.inc
                    gcloud components update kubectl
                    gcloud version

                    curl -s https://storage.googleapis.com/kubernetes-helm/helm-v2.14.0-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                    curl -s -L https://github.com/openshift/origin/releases/download/v3.11.0/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 --wildcards -zxvpf - '*/oc'
                '''

                echo "Log in to openshift"
                withCredentials([usernamePassword(credentialsId: 'OPENSHIFT_CREDENTIONALS', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        oc login https://api.cluster1.cd.percona.com:6443 --insecure-skip-tls-verify=true -u=$USER -p=$PASS
                    '''
                }
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
                            export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                            ./e2e-tests/build
                            docker logout
                        "
                        sudo rm -rf ./build
                    '''
                }
            }
        }
        stage('E2E Basic Tests') {
            steps {
                runTest('init-deploy')
                runTest('limits')
                runTest('monitoring')
                runTest('affinity')
           }
        }
        stage('E2E Scaling') {
            steps {
                runTest('scaling')
                runTest('scaling-proxysql')
            }
        }
        stage('E2E Backups') {
            steps {
                runTest('recreate')
                runTest('demand-backup')
                runTest('scheduled-backup')
            }
        }
    }

    post {
        always {
            sh '''
                oc logout
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
