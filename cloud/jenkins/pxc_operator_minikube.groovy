tests=[]

void IsRunTestsInClusterWide() {
    if ("${params.CLUSTER_WIDE}" == "YES") {
        env.OPERATOR_NS = 'pxc-operator'
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
            def file="${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-${testName}-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}"
            def retFileExists = sh(script: "aws s3api head-object --bucket percona-jenkins-artifactory --key ${JOB_NAME}/${GIT_SHORT_COMMIT}/${file} >/dev/null 2>&1", returnStatus: true)

            if (retFileExists == 0) {
                tests[i]["result"] = "passed"
            }
        }
    }
}

TestsReport = '<testsuite name=\\"PXC\\">\n'
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
    for (int i=0; i<tests.size(); i++) {
        if (tests[i]["result"] == "skipped") {
            tests[i]["result"] = "failure"
            tests[i]["cluster"] = cluster
            runTest(i)
        }
    }
}

void runTest(Integer TEST_ID) {
    def retryCount = 0
    def testName = tests[TEST_ID]["name"]

    waitUntil {
        def timeStart = new Date().getTime()
        try {
            echo "The $testName test was started !"
            tests[TEST_ID]["result"] = "failure"

            sh """
                cd ./source
                if [ -n "${PXC_OPERATOR_IMAGE}" ]; then
                    export IMAGE=${PXC_OPERATOR_IMAGE}
                else
                    export IMAGE=perconalab/percona-xtradb-cluster-operator:${env.GIT_BRANCH}
                fi

                if [ -n "${IMAGE_PXC}" ]; then
                    export IMAGE_PXC=${IMAGE_PXC}
                fi

                if [ -n "${IMAGE_PROXY}" ]; then
                    export IMAGE_PROXY=${IMAGE_PROXY}
                fi

                if [ -n "${IMAGE_HAPROXY}" ]; then
                    export IMAGE_HAPROXY=${IMAGE_HAPROXY}
                fi

                if [ -n "${IMAGE_BACKUP}" ]; then
                    export IMAGE_BACKUP=${IMAGE_BACKUP}
                fi

                if [ -n "${IMAGE_PMM}" ]; then
                    export IMAGE_PMM=${IMAGE_PMM}
                fi

                if [ -n "${IMAGE_LOGCOLLECTOR}" ]; then
                    export IMAGE_LOGCOLLECTOR=${IMAGE_LOGCOLLECTOR}
                fi

                if [ -n "${IMAGE_PMM_SERVER_REPO}" ]; then
                    export IMAGE_PMM_SERVER_REPO=${IMAGE_PMM_SERVER_REPO}
                fi

                if [ -n "${IMAGE_PMM_SERVER_TAG}" ]; then
                    export IMAGE_PMM_SERVER_TAG=${IMAGE_PMM_SERVER_TAG}
                fi

                sudo rm -rf /tmp/hostpath-provisioner/*
                ./e2e-tests/$testName/run
            """
            pushArtifactFile("${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-$testName-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}")
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

void installRpms() {
    sh """
        cat <<EOF > /tmp/kubernetes.repo
[kubernetes]
name=Kubernetes
baseurl=https://packages.cloud.google.com/yum/repos/kubernetes-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/yum-key.gpg https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF
        sudo mv /tmp/kubernetes.repo /etc/yum.repos.d/

        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum clean all || true
        sudo yum install -y percona-xtrabackup-80 jq kubectl socat
    """
}
pipeline {
    parameters {
        choice(
            choices: ['run-minikube.csv', 'run-release.csv', 'run-distro.csv'],
            description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.',
            name: 'TEST_SUITE')
        text(
            defaultValue: '',
            description: 'List of tests to run separated by new line',
            name: 'TEST_LIST')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
        choice(
            choices: 'NO\nYES',
            description: 'Run tests with cluster wide',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-xtradb-cluster-operator:main',
            name: 'PXC_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'PXC image: perconalab/percona-xtradb-cluster-operator:main-pxc5.7',
            name: 'IMAGE_PXC')
        string(
            defaultValue: '',
            description: 'PXC proxy image: perconalab/percona-xtradb-cluster-operator:main-proxysql',
            name: 'IMAGE_PROXY')
        string(
            defaultValue: '',
            description: 'PXC haproxy image: perconalab/percona-xtradb-cluster-operator:main-haproxy',
            name: 'IMAGE_HAPROXY')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-xtradb-cluster-operator:main-pxc5.7-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/percona-xtradb-cluster-operator:main-pmm',
            name: 'IMAGE_PMM')
        string(
            defaultValue: '',
            description: 'PXC logcollector image: perconalab/percona-xtradb-cluster-operator:main-logcollector',
            name: 'IMAGE_LOGCOLLECTOR')
        string(
            defaultValue: '',
            description: 'PMM server image repo: perconalab/pmm-server',
            name: 'IMAGE_PMM_SERVER_REPO')
        string(
            defaultValue: '',
            description: 'PMM server image tag: dev-latest',
            name: 'IMAGE_PMM_SERVER_TAG')
        string(
            defaultValue: 'latest',
            description: 'Kubernetes Version',
            name: 'PLATFORM_VER',
            trim: true)
    }
    agent {
         label 'micro-amazon'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
    }
    environment {
        CLEAN_NAMESPACE = 1
        PXC_TAG = sh(script: "if [ -n \"\${IMAGE_PXC}\" ]; then echo ${IMAGE_PXC} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()
    }
    stages {
        stage('Prepare') {
            agent { label 'docker' }
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
                stash includes: "source/**", name: "sourceFILES", useDefaultExcludes: false
                script {
                    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
                }
                initTests()
            }
        }

        stage('Build docker image') {
            agent { label 'docker' }
            steps {
                sh """
                    sudo rm -rf source
                """
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        if [ -n "${PXC_OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, PXC operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                                ./e2e-tests/build
                                docker logout
                            "
                            sudo rm -rf ./build
                        fi
                    '''
                }
            }
        }
        stage('Tests') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            agent { label 'docker-32gb' }
                steps {
                    IsRunTestsInClusterWide()

                    sh """
                        sudo yum install -y conntrack
                        sudo usermod -aG docker $USER
                        if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                            rm -rf $HOME/google-cloud-sdk
                            curl https://sdk.cloud.google.com | bash
                        fi

                        source $HOME/google-cloud-sdk/path.bash.inc
                        gcloud components install alpha
                        gcloud components install kubectl

                        curl -s https://get.helm.sh/helm-v3.9.4-linux-amd64.tar.gz \
                            | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                        sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.27.2/yq_linux_amd64 > /usr/local/bin/yq"
                        sudo chmod +x /usr/local/bin/yq
                        sudo curl -Lo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
                        sudo chmod +x /usr/local/bin/minikube
                        /usr/local/bin/minikube start --kubernetes-version ${PLATFORM_VER} --cpus=6 --memory=28G
                    """

                    unstash "sourceFILES"
                    withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
                        sh """
                           cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                        """
                    }

                    installRpms()
                    clusterRunner('cluster1')
            }
            post {
                always {
                    sh """
                        /usr/local/bin/minikube delete || true
                        sudo rm -rf $HOME/google-cloud-sdk
                        sudo rm -rf ./*
                    """
                    deleteDir()
                }
            }
        }
        stage('Make report') {
            steps {
                echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
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
            sh """
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            """
            deleteDir()
        }
    }
}
