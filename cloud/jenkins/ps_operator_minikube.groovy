tests=[]

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
            def file="${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-${testName}-${params.PLATFORM_VER}-$PS_TAG"
            def retFileExists = sh(script: "aws s3api head-object --bucket percona-jenkins-artifactory --key ${JOB_NAME}/${GIT_SHORT_COMMIT}/${file} >/dev/null 2>&1", returnStatus: true)

            if (retFileExists == 0) {
                tests[i]["result"] = "passed"
            }
        }
    }
}

TestsReport = '<testsuite name=\\"PS\\">\n'
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
                if [ -n "${OPERATOR_IMAGE}" ]; then
                    export IMAGE=${OPERATOR_IMAGE}
                else
                    export IMAGE=perconalab/percona-server-mysql-operator:${env.GIT_BRANCH}
                fi

                if [ -n "${IMAGE_MYSQL}" ]; then
                    export IMAGE_MYSQL=${IMAGE_MYSQL}
                fi

                if [ -n "${IMAGE_ORCHESTRATOR}" ]; then
                    export IMAGE_ORCHESTRATOR=${IMAGE_ORCHESTRATOR}
                fi

                if [ -n "${IMAGE_ROUTER}" ]; then
                    export IMAGE_ROUTER=${IMAGE_ROUTER}
                fi

                if [ -n "${IMAGE_BACKUP}" ]; then
                    export IMAGE_BACKUP=${IMAGE_BACKUP}
                fi

                if [ -n "${IMAGE_TOOLKIT}" ]; then
                    export IMAGE_TOOLKIT=${IMAGE_TOOLKIT}
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

                sudo rm -rf /tmp/hostpath-provisioner/*

                export KUBECONFIG=~/.kube/config
                export PATH="${HOME}/.krew/bin:$PATH"
                source $HOME/google-cloud-sdk/path.bash.inc

                kubectl kuttl test --config ./e2e-tests/kuttl.yaml --test "^$testName\$"
            """
            pushArtifactFile("${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-$testName-${params.PLATFORM_VER}-$PS_TAG")
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
            description: 'Tag/Branch for percona/percona-server-mysql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mysql-operator',
            description: 'percona/percona-server-mysql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mysql-operator:main',
            name: 'OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'MySQL image: perconalab/percona-server-mysql-operator:main-ps8.0',
            name: 'IMAGE_MYSQL')
        string(
            defaultValue: '',
            description: 'Orchestrator image: perconalab/percona-server-mysql-operator:main-orchestrator',
            name: 'IMAGE_ORCHESTRATOR')
        string(
            defaultValue: '',
            description: 'MySQL Router image: perconalab/percona-server-mysql-operator:main-router',
            name: 'IMAGE_ROUTER')
        string(
            defaultValue: '',
            description: 'XtraBackup image: perconalab/percona-server-mysql-operator:main-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'Toolkit image: perconalab/percona-server-mysql-operator:main-toolkit',
            name: 'IMAGE_TOOLKIT')
        string(
            defaultValue: '',
            description: 'HAProxy image: perconalab/percona-server-mysql-operator:main-haproxy',
            name: 'IMAGE_HAPROXY')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/pmm-client:dev-latest',
            name: 'IMAGE_PMM')
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
        PS_TAG = sh(script: "if [ -n \"\${IMAGE_MYSQL}\" ]; then echo ${IMAGE_MYSQL} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()
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
                sh '''
                    sudo rm -rf source
                '''
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        if [ -n "${OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE=perconalab/percona-server-mysql-operator:$GIT_BRANCH
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
                    sh '''
                        sudo yum install -y conntrack
                        sudo usermod -aG docker $USER

                        sudo yum install -y jq | true

                        if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                            rm -rf $HOME/google-cloud-sdk
                            curl https://sdk.cloud.google.com | bash
                        fi

                        source $HOME/google-cloud-sdk/path.bash.inc
                        gcloud components install alpha
                        gcloud components install kubectl

                        curl -s https://get.helm.sh/helm-v3.9.4-linux-amd64.tar.gz \
                            | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                        sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.29.1/yq_linux_amd64 > /usr/local/bin/yq"
                        sudo chmod +x /usr/local/bin/yq

						cd "$(mktemp -d)"
						OS="$(uname | tr '[:upper:]' '[:lower:]')"
						ARCH="$(uname -m | sed -e 's/x86_64/amd64/')"
						KREW="krew-${OS}_${ARCH}"
						curl -fsSLO "https://github.com/kubernetes-sigs/krew/releases/download/v0.4.2/${KREW}.tar.gz"
						tar zxvf "${KREW}.tar.gz"
						./"${KREW}" install krew
						export PATH="${KREW_ROOT:-$HOME/.krew}/bin:$PATH"
						kubectl krew install kuttl

                        sudo curl -Lo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
                        sudo chmod +x /usr/local/bin/minikube
                        /usr/local/bin/minikube start --kubernetes-version ${PLATFORM_VER} --cpus=6 --memory=28G
                    '''

                    unstash "sourceFILES"
                    withCredentials([file(credentialsId: 'cloud-secret-file-ps', variable: 'CLOUD_SECRET_FILE')]) {
                        sh """
                           cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                        """
                    }

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
