tests=[]

void prepareNode() {
    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    sh """
        sudo curl -s -L -o /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
        kubectl version --client --output=yaml

        curl -fsSL https://get.helm.sh/helm-v3.12.3-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.35.1/yq_linux_amd64 > /usr/local/bin/yq"
        sudo chmod +x /usr/local/bin/yq

        sudo sh -c "curl -s -L https://github.com/jqlang/jq/releases/download/jq-1.6/jq-linux64 > /usr/local/bin/jq"
        sudo chmod +x /usr/local/bin/jq

        sudo curl -sLo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo chmod +x /usr/local/bin/minikube

        curl -fsSL https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_amd64.tar.gz | tar -xzf -
        ./krew-linux_amd64 install krew
        export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"

        kubectl krew install assert

        # v0.17.0 kuttl version
        kubectl krew install --manifest-url https://raw.githubusercontent.com/kubernetes-sigs/krew-index/336ef83542fd2f783bfa2c075b24599e834dcc77/plugins/kuttl.yaml
        echo \$(kubectl kuttl --version) is installed
    """

    USED_PLATFORM_VER="$PLATFORM_VER"
    echo "USED_PLATFORM_VER=$PLATFORM_VER"

    echo "=========================[ Cloning the sources ]========================="
    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf source
        cloud/local/checkout $GIT_REPO $GIT_BRANCH
    """

    stash includes: "source/**", name: "sourceFILES"

    script {
        GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
        PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$USED_PLATFORM_VER-$CLUSTER_WIDE-$OPERATOR_IMAGE-$IMAGE_MYSQL-$IMAGE_ORCHESTRATOR-$IMAGE_ROUTER-$IMAGE_BACKUP-$IMAGE_TOOLKIT-$IMAGE_HAPROXY-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER | md5sum | cut -d' ' -f1", , returnStdout: true).trim()
    }
}

void dockerBuildPush() {
    unstash "sourceFILES"
    echo "=========================[ Building and Pushing the operator Docker image ]========================="
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            if [[ "$OPERATOR_IMAGE" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source
                sg docker -c "
                    docker buildx create --use
                    docker login -u '$USER' -p '$PASS'
                    export IMAGE=perconalab/percona-server-mysql-operator:$GIT_BRANCH
                    e2e-tests/build
                    docker logout
                "
                sudo rm -rf build
            fi
        """
    }
}

void initTests() {
    echo "=========================[ Initializing the tests ]========================="

    echo "Populating tests into the tests array!"
    def testList = "$TEST_LIST"
    def suiteFileName = "source/e2e-tests/$TEST_SUITE"

    if (testList.length() != 0) {
        suiteFileName = 'source/e2e-tests/run-custom.csv'
        sh """
            echo -e "$testList" > $suiteFileName
            echo "Custom test suite contains following tests:"
            cat $suiteFileName
        """
    }

    def records = readCSV file: suiteFileName

    for (int i=0; i<records.size(); i++) {
        tests.add(["name": records[i][0], "cluster": "NA", "result": "skipped", "time": "0"])
    }

    echo "Marking passed tests in the tests map!"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        if ("$IGNORE_PREVIOUS_RUN" == "NO") {
            sh """
                aws s3 ls s3://percona-jenkins-artifactory/$JOB_NAME/$GIT_SHORT_COMMIT/ || :
            """

            for (int i=0; i<tests.size(); i++) {
                def testName = tests[i]["name"]
                def file="$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$USED_PLATFORM_VER-$PS_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH"
                def retFileExists = sh(script: "aws s3api head-object --bucket percona-jenkins-artifactory --key $JOB_NAME/$GIT_SHORT_COMMIT/$file >/dev/null 2>&1", returnStatus: true)

                if (retFileExists == 0) {
                    tests[i]["result"] = "passed"
                }
            }
        } else {
            sh """
                aws s3 rm "s3://percona-jenkins-artifactory/$JOB_NAME/$GIT_SHORT_COMMIT/" --recursive --exclude "*" --include "*-$PARAMS_HASH" || :
            """
        }
    }

    withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
        sh """
            cp $CLOUD_SECRET_FILE source/e2e-tests/conf/cloud-secret.yml
        """
    }
}

void clusterRunner(String cluster) {
    sh """
        export CHANGE_MINIKUBE_NONE_USER=true
        /usr/local/bin/minikube start --kubernetes-version $USED_PLATFORM_VER --cpus=6 --memory=28G
    """

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

            timeout(time: 90, unit: 'MINUTES') {
                sh """
                    cd source

                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=ps-operator
                    [[ "$OPERATOR_IMAGE" ]] && export IMAGE=$OPERATOR_IMAGE || export IMAGE=perconalab/percona-server-mysql-operator:$GIT_BRANCH
                    export IMAGE_MYSQL=$IMAGE_MYSQL
                    export IMAGE_ORCHESTRATOR=$IMAGE_ORCHESTRATOR
                    export IMAGE_ROUTER=$IMAGE_ROUTER
                    export IMAGE_HAPROXY=$IMAGE_HAPROXY
                    export IMAGE_BACKUP=$IMAGE_BACKUP
                    export IMAGE_TOOLKIT=$IMAGE_TOOLKIT
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER

                    sudo rm -rf /tmp/hostpath-provisioner/*

                    export KUBECONFIG=~/.kube/config
                    export PATH="\$HOME/.krew/bin:\$PATH"

                    kubectl kuttl test --config ./e2e-tests/kuttl.yaml --test "^$testName\$"
                """
            }
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$USED_PLATFORM_VER-$PS_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
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

void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch $FILE_NAME
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/$GIT_SHORT_COMMIT
            aws s3 ls \$S3_PATH/$FILE_NAME || :
            aws s3 cp --quiet $FILE_NAME \$S3_PATH/$FILE_NAME || :
        """
    }
}

TestsReport = '<testsuite name=\\"PS-MiniKube\\">\n'
void makeReport() {
    echo "=========================[ Generating Test Report ]========================="
    for (int i=0; i<tests.size(); i++) {
        def testResult = tests[i]["result"]
        def testTime = tests[i]["time"]
        def testName = tests[i]["name"]

        TestsReport = TestsReport + '<testcase name=\\"' + testName + '\\" time=\\"' + testTime + '\\"><'+ testResult +'/></testcase>\n'
    }
    TestsReport = TestsReport + '</testsuite>\n'
}


pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        PS_TAG = sh(script: "[[ \"$IMAGE_MYSQL\" ]] && echo $IMAGE_MYSQL | awk -F':' '{print \$2}' || echo main", , returnStdout: true).trim()
    }
    parameters {
        choice(
            choices: ['run-minikube.csv', 'run-distro.csv'],
            description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.',
            name: 'TEST_SUITE')
        text(
            defaultValue: '',
            description: 'List of tests to run separated by new line',
            name: 'TEST_LIST')
        choice(
            choices: 'NO\nYES',
            description: 'Ignore passed tests in previous run (run all)',
            name: 'IGNORE_PREVIOUS_RUN'
        )
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mysql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mysql-operator',
            description: 'percona/percona-server-mysql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'Kubernetes Version',
            name: 'PLATFORM_VER',
            trim: true)
        choice(
            choices: 'YES\nNO',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
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
            description: 'PMM client image: perconalab/pmm-client:dev-latest',
            name: 'IMAGE_PMM_CLIENT')
        string(
            defaultValue: '',
            description: 'PMM server image: perconalab/pmm-server:dev-latest',
            name: 'IMAGE_PMM_SERVER')
    }
    agent {
         label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare node') {
            // agent { label 'docker' }
            steps {
                prepareNode()
            }
        }
        stage('Docker Build and Push') {
            // agent { label 'docker' }
            steps {
                dockerBuildPush()
            }
        }
        stage('Init tests') {
            // agent { label 'docker' }
            steps {
                initTests()
            }
        }
        stage('Run Tests') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            // agent { label 'docker-32gb' }
            steps {
                unstash "sourceFILES"
                clusterRunner('cluster1')
            }
            post {
                always {
                    sh """
                        /usr/local/bin/minikube delete || true
                        sudo docker system prune --volumes -af
                        sudo rm -rf *
                    """
                }
            }
        }
    }

    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
            sh """
                echo "$TestsReport" > TestsReport.xml
            """
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml'

            sh """
                /usr/local/bin/minikube delete || true
                sudo docker system prune --volumes -af
                sudo rm -rf *
            """
        }
    }
}