
void CreateCluster(String CLUSTER_PREFIX) {
    sh '''
        retry() {
            local max=\$1
            local delay=\$2
            shift 2 # cut delay and max args
            local n=1

            until \$@; do
                if [[ \$n -ge \$max ]]; then
                    echo "The command '$@' has failed after \$n attempts."
                    exit 1
                fi
                ((n++))
                sleep \$delay
            done
        }

        CLUSTER_ID="$(linode-cli lke cluster-create --version \$LKE_VERSION --label \$CLUSTER_NAME-''' + CLUSTER_PREFIX + ''' --region us-central --node_pools.count 3 --tags jenkins,\$CLUSTER_NAME-''' + CLUSTER_PREFIX + ''' --node_pools.type g6-standard-2 --json | jq '.[].id')"
        if [[ x\$CLUSTER_ID == "x" ]]; then
           echo "No cluster created. Exiting."
           exit 1
        fi
        retry 10 60 linode-cli lke kubeconfig-view \$CLUSTER_ID --json > /dev/null 2>&1
        linode-cli lke kubeconfig-view \$CLUSTER_ID --json | jq -r '.[].kubeconfig' | base64 -d > /tmp/\$CLUSTER_NAME-''' + CLUSTER_PREFIX + ''' 
        export KUBECONFIG=/tmp/\$CLUSTER_NAME-''' + CLUSTER_PREFIX + ''' 
    '''
}

void ShutdownCluster(String CLUSTER_PREFIX) {
    sh '''
        linode-cli lke cluster-delete $(linode-cli lke clusters-list --json | jq '.[] | select(.label == "'"${CLUSTER_NAME}-''' + CLUSTER_PREFIX + '''"'").id' )
    '''
}
void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch ${FILE_NAME}
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${env.GIT_SHORT_COMMIT}
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME) {
    echo "Try to get $FILE_NAME file from S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${env.GIT_SHORT_COMMIT}
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
        """
    }
}

testsResultsMap = [:]

void setTestsresults() {
    testsResultsMap.each { file ->
        pushArtifactFile("${file.key}")
    }
}

void runTest(String TEST_NAME, String CLUSTER_PREFIX) {
    try {
        echo "The $TEST_NAME test was started!"

        popArtifactFile("${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.LKE_VERSION}")

        sh """
            if [ -f "${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.LKE_VERSION}" ]; then
                echo Skip $TEST_NAME test
            else
                cd ./source
                if [ -n "${PSMDB_OPERATOR_IMAGE}" ]; then
                    export IMAGE=${PSMDB_OPERATOR_IMAGE}
                else
                    export IMAGE=perconalab/percona-server-mongodb-operator:${env.GIT_BRANCH}
                fi

                if [ -n "${IMAGE_MONGOD}" ]; then
                    export IMAGE_MONGOD=${IMAGE_MONGOD}
                fi

                if [ -n "${IMAGE_BACKUP}" ]; then
                    export IMAGE_BACKUP=${IMAGE_BACKUP}
                fi

                if [ -n "${IMAGE_PMM}" ]; then
                    export IMAGE_PMM=${IMAGE_PMM}
                fi

                export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_PREFIX}
                source $HOME/google-cloud-sdk/path.bash.inc
                ./e2e-tests/$TEST_NAME/run
            fi
        """
        pushArtifactFile("${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.LKE_VERSION}")
        testsResultsMap["${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.LKE_VERSION}"] = 'passed'
    }
    catch (exc) {
        currentBuild.result = 'FAILURE'
    }

    echo "The $TEST_NAME test was finished!"
}
void installRpms() {
    sh '''
        cat <<EOF > /tmp/kubernetes.repo
[kubernetes]
name=Kubernetes
baseurl=https://packages.cloud.google.com/yum/repos/kubernetes-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/yum-key.gpg https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF
        sudo mv /tmp/kubernetes.repo /etc/yum.repos.d
        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y percona-xtrabackup-80 jq python3-pip kubectl || true
    '''
}
pipeline {
    environment {
        CLOUDSDK_CORE_DISABLE_PROMPTS = 1
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '1.16',
            description: 'LKE version',
            name: 'LKE_VERSION')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mongodb-operator:master',
            name: 'PSMDB_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'MONGOD image: perconalab/percona-server-mongodb-operator:master-mongod4.0',
            name: 'IMAGE_MONGOD')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-server-mongodb-operator:master-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/percona-server-mongodb-operator:master-pmm',
            name: 'IMAGE_PMM')
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
                    sudo pip3 install --upgrade linode-cli

                    curl -s https://storage.googleapis.com/kubernetes-helm/helm-v2.16.1-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                '''
                withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE'), file(credentialsId: 'LINODE-CONFIG', variable: 'LKE_CLIENT_FILE')]) {
                    sh '''
                        mkdir ${HOME}/.config || true
                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                        cp ${LKE_CLIENT_FILE} ${HOME}/.config/linode-cli || true
                    '''
                }
            }
        }
        stage('Build docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        if [ -n "${PSMDB_OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, PSMDB operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
                                ./e2e-tests/build
                                docker logout
                            "
                            sudo rm -rf ./build
                        fi
                    '''
                }
            }
        }
        stage('Run Tests') {
            environment {
                CLOUDSDK_CORE_DISABLE_PROMPTS = 1
                GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
                VERSION = "${env.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}"
                CLUSTER_NAME = sh(script: "echo jenkins-psmdb-${GIT_SHORT_COMMIT} | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
            }
            parallel {
                stage('E2E Scaling') {
                    steps {
                        CreateCluster('scaling')
                        runTest('init-deploy', 'scaling')
                        runTest('limits', 'scaling')
                        runTest('scaling', 'scaling')
                        ShutdownCluster('scaling')
                   }
                }
                stage('E2E Basic Tests') {
                    steps {
                        CreateCluster('basic')
                        runTest('storage', 'basic')
                        runTest('monitoring', 'basic')
                        runTest('monitoring-2-0', 'basic')
                        runTest('arbiter', 'basic')
                        runTest('service-per-pod', 'basic')
                        ShutdownCluster('basic')
                    }
                }
                stage('E2E SelfHeal') {
                    steps {
                        CreateCluster('selfheal')
                        runTest('self-healing', 'selfheal')
                        runTest('operator-self-healing', 'selfheal')
                        runTest('one-pod', 'selfheal')
                        ShutdownCluster('selfheal')
                    }
                }
                stage('E2E Backups') {
                    steps {
                        CreateCluster('backups')
                        runTest('upgrade', 'backups')
                        runTest('upgrade-consistency', 'backups')
                        runTest('demand-backup', 'backups')
                        runTest('scheduled-backup', 'backups')
                        ShutdownCluster('backups')
                    }
                }
            }
        }
    }
    post {
        always {
            setTestsresults()
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
