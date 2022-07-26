void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch ${FILE_NAME}
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git -C source describe --always --dirty)
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME) {
    echo "Try to get $FILE_NAME file from S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git -C source describe --always --dirty)
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
        """
    }
}

TestsReport = '<testsuite name=\\"PSMDB\\">\n'
testsReportMap = [:]
void makeReport() {
    for ( test in testsReportMap ) {
        TestsReport = TestsReport + "<testcase name=\\\"${test.key}\\\"><${test.value}/></testcase>\n"
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void runTest(String TEST_NAME) {
    def retryCount = 0
    waitUntil {
        try {
            echo "The $TEST_NAME test was started!"

            GIT_SHORT_COMMIT = sh(script: 'git -C source describe --always --dirty', , returnStdout: true).trim()
            VERSION = "${env.GIT_BRANCH}-$GIT_SHORT_COMMIT"
            testsReportMap[TEST_NAME] = 'failure'
            MDB_TAG = sh(script: "if [ -n \"\${IMAGE_MONGOD}\" ] ; then echo ${IMAGE_MONGOD} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()

            popArtifactFile("$VERSION-$TEST_NAME-$MDB_TAG")

            sh """
                if [ -f "$VERSION-$TEST_NAME-$MDB_TAG" ]; then
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

                    source $HOME/google-cloud-sdk/path.bash.inc
                    ./e2e-tests/$TEST_NAME/run
                fi
            """
            pushArtifactFile("$VERSION-$TEST_NAME-$MDB_TAG")
            testsReportMap[TEST_NAME] = 'passed'
            return true
        }
        catch (exc) {
            if (retryCount >= 2) {
                currentBuild.result = 'FAILURE'
                return true
            }
            retryCount++
            return false
        }
    }

    echo "The $TEST_NAME test was finished!"
}
void installRpms() {
    sh """
        sudo yum install -y jq | true
    """
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mongodb-operator:main',
            name: 'PSMDB_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'MONGOD image: perconalab/percona-server-mongodb-operator:main-mongod4.0',
            name: 'IMAGE_MONGOD')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-server-mongodb-operator:main-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/percona-server-mongodb-operator:main-pmm',
            name: 'IMAGE_PMM')
    }
    environment {
        TF_IN_AUTOMATION = 'true'
        RHEL_USER = credentials('RHEL-USER')
        RHEL_PASSWORD = credentials('RHEL-PASSWD')
    }
    agent {
         label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '10', artifactNumToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                sh """
                    wget https://releases.hashicorp.com/terraform/0.11.14/terraform_0.11.14_linux_amd64.zip
                    unzip terraform_0.11.14_linux_amd64.zip
                    sudo mv terraform /usr/local/bin/ && rm terraform_0.11.14_linux_amd64.zip
                """
                installRpms()
                sh '''
                    if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                        rm -rf $HOME/google-cloud-sdk
                        curl https://sdk.cloud.google.com | bash
                    fi

                    source $HOME/google-cloud-sdk/path.bash.inc
                    gcloud components update kubectl
                    gcloud version

                    curl -s https://get.helm.sh/helm-v3.2.3-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                    curl -s -L https://github.com/openshift/origin/releases/download/v3.11.0/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 --wildcards -zxvpf - '*/oc'

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/3.3.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq
                '''

            }
        }
        stage('Build docker image') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
                    sh '''
                        # sudo is needed for better node recovery after compilation failure
                        # if building failed on compilation stage directory will have files owned by docker user
                        sudo sudo git config --global --add safe.directory '*'
                        sudo git reset --hard
                        sudo git clean -xdf
                        sudo rm -rf source
                        ./cloud/local/checkout $GIT_REPO $GIT_BRANCH

                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml

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
        stage('Create AWS Infrastructure') {
            steps {
                git branch: 'main', url: 'https://github.com/Percona-Lab/k8s-lab'
                    sh """
                        # sudo is needed for better node recovery after compilation failure
                        # if building failed on compilation stage directory will have files owned by docker user
                        sudo git reset --hard
                        sudo git clean -xdf
                    """

                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-key-pub', variable: 'AWS_NODES_KEY_PUB')]) {
                     sshagent(['aws-openshift-key']) {
                         sh """
                            pushd ./aws-openshift-automation
                                make infrastructure
                                sleep 400
                            popd
                    """
                    }
               }

            }
        }
        stage('Install and conigure Openshift') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-key-pub', variable: 'AWS_NODES_KEY_PUB')]) {
                     sshagent(['aws-openshift-key']) {
                         sh """
                            pushd ./aws-openshift-automation
                                make openshift
                            popd
                         """
                    }
                    retry(3) {
                         sh """
                            pushd ./aws-openshift-automation
                                sleep 120
                                oc login \$(terraform output master-url) --insecure-skip-tls-verify=true -u=real-admin -p=123
                            popd
                        """
                    }
                }
            }
        }
        stage('E2E Scaling') {
            steps {
                runTest('init-deploy')
                runTest('limits')
                runTest('scaling')
                runTest('security-context')
                runTest('smart-update')
                runTest('version-service')
                runTest('rs-shard-migration')
                runTest('data-sharded')
            }
        }
        stage('E2E Basic Tests') {
            steps {
                runTest('one-pod')
                runTest('arbiter')
                runTest('service-per-pod')
                runTest('liveness')
                runTest('users')
                runTest('cross-site-sharded')
           }
        }
        stage('E2E Backups') {
            steps {
                runTest('upgrade')
                runTest('upgrade-consistency')
                runTest('demand-backup')
                runTest('demand-backup-sharded')
                runTest('scheduled-backup')
                runTest('upgrade-sharded')
                runTest('pitr')
                runTest('pitr-sharded')
            }
        }
        stage('Make report') {
            steps {
                makeReport()
                sh """
                    echo "${TestsReport}" > TestsReport.xml
                """
                step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
                archiveArtifacts '*.xml'
            }
        }
    }
    post {
        always {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-key-pub', variable: 'AWS_NODES_KEY_PUB')]) {
                     sshagent(['aws-openshift-key']) {
                         sh """
                            pushd ./aws-openshift-automation
                                make unregister-rhel-subscription || true
                                make destroy
                            popd
                         """
                     }
                }
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
