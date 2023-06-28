tests=[]
clusters=[]

void IsRunTestsInClusterWide() {
    if ("${params.CLUSTER_WIDE}" == "YES") {
        env.OPERATOR_NS = 'psmdb-operator'
    }
}

void createCluster(String CLUSTER_SUFFIX){
    clusters.add("${CLUSTER_SUFFIX}")

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE')]) {
        sh """
            platform_version=`echo "\${params.PLATFORM_VER}" | awk -F. '{ printf("%d%03d%03d%03d\\n", \$1,\$2,\$3,\$4); }';`
            version=`echo "4.12.0" | awk -F. '{ printf("%d%03d%03d%03d\\n", \$1,\$2,\$3,\$4); }';`
            if [ \$platform_version -ge \$version ];then
                POLICY="additionalTrustBundlePolicy: Proxyonly"
                NETWORK_TYPE="OVNKubernetes"
            else
                POLICY=""
                NETWORK_TYPE="OpenShiftSDN"
            fi
            mkdir -p openshift/${CLUSTER_SUFFIX}
cat <<-EOF > ./openshift/${CLUSTER_SUFFIX}/install-config.yaml
\$POLICY
apiVersion: v1
baseDomain: cd.percona.com
compute:
- architecture: amd64
  hyperthreading: Enabled
  name: worker
  platform:
    aws:
      type: m5.2xlarge
  replicas: 3
controlPlane:
  architecture: amd64
  hyperthreading: Enabled
  name: master
  platform: {}
  replicas: 1
metadata:
  creationTimestamp: null
  name: openshift4-par-psmdb-jenkins-${CLUSTER_SUFFIX}
networking:
  clusterNetwork:
  - cidr: 10.128.0.0/14
    hostPrefix: 23
  machineNetwork:
  - cidr: 10.0.0.0/16
  networkType: \$NETWORK_TYPE
  serviceNetwork:
  - 172.30.0.0/16
platform:
  aws:
    region: eu-west-3
    userTags:
      iit-billing-tag: openshift
      delete-cluster-after-hours: 8
      team: cloud
      product: psmdb-operator
      
publish: External
EOF
            cat $OPENSHIFT_CONF_FILE >> ./openshift/${CLUSTER_SUFFIX}/install-config.yaml
        """

        sshagent(['aws-openshift-41-key']) {
            sh """
                /usr/local/bin/openshift-install create cluster --dir=./openshift/${CLUSTER_SUFFIX}
                export KUBECONFIG=./openshift/${CLUSTER_SUFFIX}/auth/kubeconfig

            """
        }
    }
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
        sshagent(['aws-openshift-41-key']) {
            sh """
                source $HOME/google-cloud-sdk/path.bash.inc
                export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig
                for namespace in \$(kubectl get namespaces --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
                    kubectl delete deployments --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete sts --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete replicasets --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete services --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete pods --all -n \$namespace --force --grace-period=0 || true
                done
                kubectl get svc --all-namespaces || true

                /usr/local/bin/openshift-install destroy cluster --dir=./openshift/$CLUSTER_SUFFIX || true
            """
        }
    }

}

void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch ${FILE_NAME}
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${GIT_SHORT_COMMIT}
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void initTests() {
    echo "Populating tests into the tests array!"
    def testList = "${params.TEST_LIST}"
    def suiteFileName = "./source/e2e-tests/${params.TEST_SUITE}"

    if (testList.length() != 0) {
        suiteFileName = './source/e2e-tests/run-custom.csv'
        sh """
            echo -e "${testList}" > ${suiteFileName}
            echo "Custom test suite contains following tests:"
            cat ${suiteFileName}
        """
    }

    def records = readCSV file: suiteFileName

    for (int i=0; i<records.size(); i++) {
        tests.add(["name": records[i][0], "cluster": "NA", "result": "skipped", "time": "0"])
    }

    markPassedTests()
}

void markPassedTests() {
    echo "Marking passed tests in the tests map!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            aws s3 ls "s3://percona-jenkins-artifactory/${JOB_NAME}/${GIT_SHORT_COMMIT}/" || :
        """

        for (int i=0; i<tests.size(); i++) {
            def testName = tests[i]["name"]
            def file="${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-${testName}-${params.PLATFORM_VER}-$MDB_TAG-CW_${params.CLUSTER_WIDE}"
            def retFileExists = sh(script: "aws s3api head-object --bucket percona-jenkins-artifactory --key ${JOB_NAME}/${GIT_SHORT_COMMIT}/${file} >/dev/null 2>&1", returnStatus: true)

            if (retFileExists == 0) {
                tests[i]["result"] = "passed"
            }
        }
    }
}

TestsReport = '<testsuite name=\\"PSMDB\\">\n'
void makeReport() {
    for (int i=0; i<tests.size(); i++) {
        def testResult = tests[i]["result"]
        def testTime = tests[i]["time"]
        def testName = tests[i]["name"]

        TestsReport = TestsReport + '<testcase name=\\"' + testName + '\\" time=\\"' + testTime + '\\"><'+ testResult +'/></testcase>\n'
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void clusterRunner(String cluster) {
    def clusterCreated=0

    for (int i=0; i<tests.size(); i++) {
        if (tests[i]["result"] == "skipped") {
            tests[i]["result"] = "failure"
            tests[i]["cluster"] = cluster
            if (clusterCreated == 0) {
                createCluster(cluster)
                clusterCreated++
            }
            runTest(i)
        }
    }

    if (clusterCreated >= 1) {
        shutdownCluster(cluster)
    }
}

void runTest(Integer TEST_ID) {
    def retryCount = 0
    def testName = tests[TEST_ID]["name"]
    def clusterSuffix = tests[TEST_ID]["cluster"]

    waitUntil {
        def timeStart = new Date().getTime()
        try {
            echo "The $testName test was started on cluster $CLUSTER_NAME-$clusterSuffix !"
            tests[TEST_ID]["result"] = "failure"

            timeout(time: 90, unit: 'MINUTES') {
                sh """
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

                    if [ -n "${IMAGE_PMM_SERVER_REPO}" ]; then
                        export IMAGE_PMM_SERVER_REPO=${IMAGE_PMM_SERVER_REPO}
                    fi

                    if [ -n "${IMAGE_PMM_SERVER_TAG}" ]; then
                        export IMAGE_PMM_SERVER_TAG=${IMAGE_PMM_SERVER_TAG}
                    fi

                    source $HOME/google-cloud-sdk/path.bash.inc
                    export KUBECONFIG=$WORKSPACE/openshift/$clusterSuffix/auth/kubeconfig
                    oc whoami

                    ./e2e-tests/$testName/run
                """
            }
            pushArtifactFile("${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-$testName-${params.PLATFORM_VER}-$MDB_TAG-CW_${params.CLUSTER_WIDE}")
            tests[TEST_ID]["result"] = "passed"
            return true
        }
        catch (exc) {
            if (retryCount >= 1) {
                currentBuild.result = 'FAILURE'
                return true
            }
            retryCount++
            return false
        }
        finally {
            def timeStop = new Date().getTime()
            def durationSec = (timeStop - timeStart) / 1000
            tests[TEST_ID]["time"] = durationSec
            echo "The $testName test was finished!"
        }
    }
}

pipeline {
    environment {
        TF_IN_AUTOMATION = 'true'
        CLEAN_NAMESPACE = 1
        MDB_TAG = sh(script: "if [ -n \"\${IMAGE_MONGOD}\" ] ; then echo ${IMAGE_MONGOD} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()
    }
    parameters {
        choice(
            choices: ['run-release.csv', 'run-distro.csv'],
            description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.',
            name: 'TEST_SUITE')
        text(
            defaultValue: '',
            description: 'List of tests to run separated by new line',
            name: 'TEST_LIST')
        string(
            defaultValue: '4.10.30',
            description: 'OpenShift version to use',
            name: 'PLATFORM_VER')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        choice(
            choices: 'NO\nYES',
            description: 'Run tests with cluster wide',
            name: 'CLUSTER_WIDE')
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
        string(
            defaultValue: '',
            description: 'PMM server image repo: perconalab/pmm-server',
            name: 'IMAGE_PMM_SERVER_REPO')
        string(
            defaultValue: '',
            description: 'PMM server image tag: dev-latest',
            name: 'IMAGE_PMM_SERVER_TAG')
    }
    agent {
         label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
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
                    sudo sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                stash includes: "source/**", name: "sourceFILES"

                script {
                    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
                    CLUSTER_NAME = sh(script: "echo jenkins-par-psmdb-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
                }
                initTests()

                sh """
                    sudo yum install -y jq | true

                    wget https://releases.hashicorp.com/terraform/0.11.14/terraform_0.11.14_linux_amd64.zip
                    unzip terraform_0.11.14_linux_amd64.zip
                    sudo mv terraform /usr/local/bin/ && rm terraform_0.11.14_linux_amd64.zip
                """
                sh '''
                    if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                        rm -rf $HOME/google-cloud-sdk
                        curl https://sdk.cloud.google.com | bash
                    fi

                    source $HOME/google-cloud-sdk/path.bash.inc
                    gcloud components update kubectl
                    gcloud version

                    curl -s https://get.helm.sh/helm-v3.9.4-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.27.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq

                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-client-linux-$PLATFORM_VER.tar.gz \
                        | sudo tar -C /usr/local/bin --wildcards -zxvpf -
                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux-$PLATFORM_VER.tar.gz \
                        | sudo tar -C /usr/local/bin  --wildcards -zxvpf -
                '''
                withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
                    sh """
                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                    """
                }
            }
        }
        stage('Build docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
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
        stage('Create AWS Infrastructure') {
            steps {
                IsRunTestsInClusterWide()
            }
        }
        stage('Run tests') {
            parallel {
                stage('cluster1') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster4')
                    }
                }
                stage('cluster5') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster5')
                    }
                }
            }
        }
    }

    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
            sh """
                echo "${TestsReport}" > TestsReport.xml
            """
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml'

            script {
                clusters.each { shutdownCluster(it) }
            }

            sh """
                sudo docker system prune -fa
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            """
            deleteDir()
        }
    }
}
