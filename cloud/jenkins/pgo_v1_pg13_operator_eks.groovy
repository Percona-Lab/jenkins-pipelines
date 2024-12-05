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

TestsReport = '<testsuite  name=\\"PGO\\">\n'
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
            GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
            testsReportMap[TEST_NAME] = 'failure'
            PPG_TAG = sh(script: "if [ -n \"\${PGO_POSTGRES_HA_IMAGE}\" ] ; then echo ${PGO_POSTGRES_HA_IMAGE} | awk -F':' '{print \$2}' | grep -oE '[A-Za-z0-9\\.]+-ppg[0-9]{2}' ; else echo 'main-ppg13'; fi", , returnStdout: true).trim()

            popArtifactFile("${params.GIT_BRANCH}-$GIT_SHORT_COMMIT-$TEST_NAME-$PPG_TAG")

            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd'], file(credentialsId: 'eks-conf-file', variable: 'EKS_CONF_FILE')]) {
                sh """
                    if [ -f "${params.GIT_BRANCH}-$GIT_SHORT_COMMIT-$TEST_NAME-$PPG_TAG" ]; then
                        echo Skip $TEST_NAME test
                    else
                        cd ./source
                        if [ -n "${PG_VERSION}" ]; then
                            export PG_VER=${PG_VERSION}
                        fi
                        if [ -n "${PGO_OPERATOR_IMAGE}" ]; then
                            export IMAGE_OPERATOR=${PGO_OPERATOR_IMAGE}
                        else
                            export IMAGE_OPERATOR=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-postgres-operator
                        fi

                        if [ -n "${PGO_APISERVER_IMAGE}" ]; then
                            export IMAGE_APISERVER=${PGO_APISERVER_IMAGE}
                        else
                            export IMAGE_APISERVER=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-apiserver
                        fi

                        if [ -n "${PGO_EVENT_IMAGE}" ]; then
                            export IMAGE_PGOEVENT=${PGO_EVENT_IMAGE}
                        else
                            export IMAGE_PGOEVENT=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-event
                        fi

                        if [ -n "${PGO_RMDATA_IMAGE}" ]; then
                            export IMAGE_RMDATA=${PGO_RMDATA_IMAGE}
                        else
                            export IMAGE_RMDATA=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-rmdata
                        fi

                        if [ -n "${PGO_SCHEDULER_IMAGE}" ]; then
                            export IMAGE_SCHEDULER=${PGO_SCHEDULER_IMAGE}
                        else
                            export IMAGE_SCHEDULER=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-scheduler
                        fi

                        if [ -n "${PGO_DEPLOYER_IMAGE}" ]; then
                            export IMAGE_DEPLOYER=${PGO_DEPLOYER_IMAGE}
                        else
                            export IMAGE_DEPLOYER=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-deployer
                        fi

                        if [ -n "${PGO_PGBOUNCER_IMAGE}" ]; then
                            export IMAGE_PGBOUNCER=${PGO_PGBOUNCER_IMAGE}
                        fi

                        if [ -n "${PGO_POSTGRES_HA_IMAGE}" ]; then
                            export IMAGE_PG_HA=${PGO_POSTGRES_HA_IMAGE}
                            export PG_VER=\$(echo \${IMAGE_PG_HA} | grep -Eo 'ppg[0-9]+'| sed 's/ppg//g')
                        fi

                        if [ -n "${PGO_BACKREST_IMAGE}" ]; then
                            export IMAGE_BACKREST=${PGO_BACKREST_IMAGE}
                        fi

                        if [ -n "${PGO_BACKREST_REPO_IMAGE}" ]; then
                            export IMAGE_BACKREST_REPO=${PGO_BACKREST_REPO_IMAGE}
                        fi

                        if [ -n "${PGO_PGBADGER_IMAGE}" ]; then
                            export IMAGE_PGBADGER=${PGO_PGBADGER_IMAGE}
                        fi

                        if [ -n "${PMM_SERVER_IMAGE_BASE}" ]; then
                            export IMAGE_PMM_SERVER_REPO=${PMM_SERVER_IMAGE_BASE}
                        fi

                        if [ -n "${PMM_SERVER_IMAGE_TAG}" ]; then
                            export IMAGE_PMM_SERVER_TAG=${PMM_SERVER_IMAGE_TAG}
                        fi

                        if [ -n "${PMM_CLIENT_IMAGE}" ]; then
                            export IMAGE_PMM=${PMM_CLIENT_IMAGE}
                        fi

                        export PATH=/home/ec2-user/.local/bin:$PATH
                        export KUBECONFIG=~/.kube/config

                        e2e-tests/$TEST_NAME/run
                    fi
                """
            }
            pushArtifactFile("${params.GIT_BRANCH}-$GIT_SHORT_COMMIT-$TEST_NAME-$PPG_TAG")
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

pipeline {
    parameters {
        string(
            defaultValue: '1.21',
            description: 'Kubernetes target version',
            name: 'KUBEVERSION')
        string(
            defaultValue: '1.x',
            description: 'Tag/Branch for percona/percona-postgresql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-postgresql-operator',
            description: 'percona-postgresql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '13',
            description: 'PG version',
            name: 'PG_VERSION')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-postgresql-operator:1-x-postgres-operator',
            name: 'PGO_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators API server image: perconalab/percona-postgresql-operator:1-x-pgo-apiserver',
            name: 'PGO_APISERVER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators event server image: perconalab/percona-postgresql-operator:1-x-pgo-event',
            name: 'PGO_EVENT_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators rmdata image: perconalab/percona-postgresql-operator:1-x-pgo-rmdata',
            name: 'PGO_RMDATA_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators scheduler image: perconalab/percona-postgresql-operator:1-x-pgo-scheduler',
            name: 'PGO_SCHEDULER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators deployer image: perconalab/percona-postgresql-operator:1-x-pgo-deployer',
            name: 'PGO_DEPLOYER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators pgBouncer image: perconalab/percona-postgresql-operator:main-ppg13-pgbouncer',
            name: 'PGO_PGBOUNCER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators postgres image: perconalab/percona-postgresql-operator:main-ppg13-postgres-ha',
            name: 'PGO_POSTGRES_HA_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators backrest utility image: perconalab/percona-postgresql-operator:main-ppg13-pgbackrest',
            name: 'PGO_BACKREST_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators backrest utility image: perconalab/percona-postgresql-operator:main-ppg13-pgbackrest-repo',
            name: 'PGO_BACKREST_REPO_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators pgBadger image: perconalab/percona-postgresql-operator:main-ppg13-pgbadger',
            name: 'PGO_PGBADGER_IMAGE')
        string(
            defaultValue: 'perconalab/pmm-server',
            description: 'PMM server image base: perconalab/pmm-server',
            name: 'PMM_SERVER_IMAGE_BASE')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM server image tag: dev-latest',
            name: 'PMM_SERVER_IMAGE_TAG')
        string(
            defaultValue: 'perconalab/pmm-client:dev-latest',
            description: 'PMM server image: perconalab/pmm-client:dev-latest',
            name: 'PMM_CLIENT_IMAGE')
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
                withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT')]) {
                    sh """
                        sudo curl -s -L -o /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
                        kubectl version --client --output=yaml

                        curl -fsSL https://get.helm.sh/helm-v3.12.3-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

                        sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/3.3.2/yq_linux_amd64 > /usr/local/bin/yq"
                        sudo chmod +x /usr/local/bin/yq

                        sudo sh -c "curl -s -L https://github.com/jqlang/jq/releases/download/jq-1.6/jq-linux64 > /usr/local/bin/jq"
                        sudo chmod +x /usr/local/bin/jq

                        curl -sL https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_\$(uname -s)_amd64.tar.gz | sudo tar -C /usr/local/bin -xzf - && sudo chmod +x /usr/local/bin/eksctl

                        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
                        sudo percona-release enable-only tools
                    """
                }
            }
        }
        stage('Build docker image') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE'), file(credentialsId: 'cloud-minio-secret-file', variable: 'CLOUD_MINIO_SECRET_FILE')]) {
                    sh '''
                        sudo git config --global --add safe.directory '*'
                        sudo git reset --hard
                        sudo git clean -xdf
                        sudo rm -rf source
                        ./cloud/local/checkout $GIT_REPO $GIT_BRANCH

                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                        cp $CLOUD_MINIO_SECRET_FILE ./source/e2e-tests/conf/cloud-secret-minio-gw.yml

                        if [ -n "${PGO_OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, PGO operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE_URI_BASE=perconalab/percona-postgresql-operator:$GIT_BRANCH
                                ./e2e-tests/build
                                docker logout
                            "
                            sudo rm -rf ./build
                        fi
                    '''
                }
            }
        }
        stage('Create EKS Infrastructure') {
            steps {
                sh '''
cat <<-EOF > cluster.yaml
# An example of ClusterConfig showing nodegroups with mixed instances (spot and on demand):
---
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
    name: eks-pgo-pg$PG_VERSION-cluster
    region: eu-west-3
    version: '$KUBEVERSION'

iam:
  withOIDC: true

addons:
- name: aws-ebs-csi-driver
  wellKnownPolicies:
    ebsCSIController: true

nodeGroups:
    - name: ng-1
      minSize: 3
      maxSize: 5
      instancesDistribution:
        maxPrice: 0.15
        instanceTypes: ["m5.xlarge", "m5.2xlarge"] # At least two instance types should be specified
        onDemandBaseCapacity: 0
        onDemandPercentageAboveBaseCapacity: 50
        spotInstancePools: 2
      tags:
        'iit-billing-tag': 'jenkins-eks'
        'delete-cluster-after-hours': '10'
        'team': 'cloud'
        'product': 'pgv1-operator'
EOF
                '''

                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                     sh """
                         export PATH=/home/ec2-user/.local/bin:$PATH
                         eksctl create cluster -f cluster.yaml
                         kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user="\$(aws sts get-caller-identity|jq -r '.Arn')"
                     """
                }
                stash includes: 'cluster.yaml', name: 'cluster_conf'
            }
        }
        stage('Run Tests') {
            environment {
                CLEAN_NAMESPACE = 1
            }
            steps {
                runTest('init-deploy')
                runTest('scaling')
                runTest('recreate')
                runTest('affinity')
                runTest('monitoring')
                runTest('self-healing')
                runTest('operator-self-healing')
                runTest('demand-backup')
                runTest('scheduled-backup')
                runTest('upgrade')
                runTest('smart-update')
                runTest('version-service')
                runTest('users')
                runTest('ns-mode')
                runTest('data-migration-gcs')
                runTest('clone-cluster')
                runTest('tls-check')
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
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    unstash 'cluster_conf'
                    sh '''
                        eksctl delete addon --name aws-ebs-csi-driver --cluster eks-pgo-pg$PG_VERSION-cluster --region eu-west-3
                        eksctl delete cluster -f cluster.yaml --wait --force
                    '''
                }

            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf *
            '''
            deleteDir()
        }
    }
}
