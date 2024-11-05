
tests=[]

void verifyParams() {
    if ("$RELEASE_RUN" == "YES" && (!"$PILLAR_VERSION" && !"$IMAGE_MONGOD")){
        error("This is RELEASE_RUN. Either PILLAR_VERSION or IMAGE_MONGOD should be provided")
    }
}

void getImage(String IMAGE_NAME) {
    versions_file = "source/e2e-tests/release_images"
    IMAGE = """${sh(
        returnStdout: true,
        script: "cat ${versions_file} | egrep \"${IMAGE_NAME}=\" | cut -d = -f 2 | tr -d \'\"\' "
    ).trim()}"""
    if ("$IMAGE") {
        return "$IMAGE"
    }
    else {
        error("Empty image is returned for $IMAGE_NAME. Check PILLAR_VERSION or content of file with images")
    }
}

void checkoutSources() {
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

    echo "=========================[ Assigning images for release test ]========================="
    if ("$RELEASE_RUN" == "YES") {
        if ("$IMAGE_OPERATOR") {
            echo "IMAGE_OPERATOR was provided. Using image from job params $IMAGE_OPERATOR"}
        else {
            echo "IMAGE_OPERATOR was NOT provided. Will use file params!"
            IMAGE_OPERATOR = getImage("IMAGE_OPERATOR")
            echo "IMAGE_OPERATOR is $IMAGE_OPERATOR"
        }
        if ("$IMAGE_MONGOD") {
            echo "IMAGE_MONGOD was provided. Using image from job params $IMAGE_MONGOD"}
        else {
            echo "IMAGE_MONGOD was NOT provided. Will use file params!"
            IMAGE_MONGOD = getImage("IMAGE_MONGOD${PILLAR_VERSION}")
            echo "IMAGE_MONGOD is $IMAGE_MONGOD"
        }
        if ("$IMAGE_BACKUP") {
            echo "IMAGE_BACKUP was provided. Using image from job params $IMAGE_BACKUP"}
        else {
            echo "IMAGE_BACKUP was NOT provided. Will use file params!"
            IMAGE_BACKUP  =getImage("IMAGE_BACKUP")
            echo "IMAGE_BACKUP is $IMAGE_BACKUP"
        }
        if ("$IMAGE_PMM_CLIENT") {
            echo "IMAGE_PMM_CLIENT was provided. Using image from job params $IMAGE_PMM_CLIENT"}
        else {
            echo "IMAGE_PMM_CLIENT was NOT provided. Will use file params!"
            IMAGE_PMM_CLIENT = getImage("IMAGE_PMM_CLIENT")
            echo "IMAGE_PMM_CLIENT is $IMAGE_PMM_CLIENT"
        }
        if ("$IMAGE_PMM_SERVER") {
            echo "IMAGE_PMM_SERVER was provided. Using image from job params $IMAGE_PMM_SERVER"}
        else {
            echo "IMAGE_PMM_SERVER was NOT provided. Will use file params!"
            IMAGE_PMM_SERVER = getImage("IMAGE_PMM_SERVER")
            echo "IMAGE_PMM_SERVER is $IMAGE_PMM_SERVER"
        }
    } else {
        echo "This is not release run. Using params only!"
    }

    if ("$IMAGE_MONGOD") {
        currentBuild.description = "$GIT_BRANCH-$PLATFORM_VER-CW_$CLUSTER_WIDE-" + "$IMAGE_MONGOD".split(":")[1]
    }

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MONGOD-$IMAGE_BACKUP-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER | md5sum | cut -d' ' -f1", , returnStdout: true).trim()
}

void dockerBuildPush() {
    echo "=========================[ Building and Pushing the operator Docker image ]========================="
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            if [[ "$IMAGE_OPERATOR" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source
                sg docker -c "
                    docker buildx create --use
                    docker login -u '$USER' -p '$PASS'
                    export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
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
                def file="$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$PLATFORM_VER-$DB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH"
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

void installToolsOnNode() {
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
    """
}

void clusterRunner(String cluster) {
    sh """
        export CHANGE_MINIKUBE_NONE_USER=true
        /usr/local/bin/minikube start --kubernetes-version $PLATFORM_VER --cpus=6 --memory=28G
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

            sh """
                cd source

                export DEBUG_TESTS=1
                [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=psmdb-operator
                [[ "$IMAGE_OPERATOR" ]] && export IMAGE=$IMAGE_OPERATOR || export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
                export IMAGE_MONGOD=$IMAGE_MONGOD
                export IMAGE_BACKUP=$IMAGE_BACKUP
                export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER

                sudo rm -rf /tmp/hostpath-provisioner/*
                e2e-tests/$testName/run
            """
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$PLATFORM_VER-$DB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
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

TestsReport = '<testsuite name=\\"PSMDB-MiniKube\\">\n'
void makeReport() {
    echo "=========================[ Generating Test Report ]========================="
    for (int i=0; i<tests.size(); i++) {
        def testResult = tests[i]["result"]
        def testTime = tests[i]["time"]
        def testName = tests[i]["name"]

        TestsReport = TestsReport + '<testcase name=\\"' + testName + '\\" time=\\"' + testTime + '\\"><'+ testResult +'/></testcase>\n'
    }
    TestsReport = TestsReport + '</testsuite>\n'

    echo "=========================[ Generating Images Report ]========================="
    TestsImages = "testsuite name='PSMDB-MiniKube'\n" +\
                    "IMAGE_OPERATOR=$IMAGE_OPERATOR\n" +\
                    "IMAGE_MONGOD=$IMAGE_MONGOD\n" +\
                    "IMAGE_BACKUP=$IMAGE_BACKUP\n" +\
                    "IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT\n" +\
                    "IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER"
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        DB_TAG = sh(script: "[[ \"$IMAGE_MONGOD\" ]] && echo $IMAGE_MONGOD | awk -F':' '{print \$2}' || echo main", , returnStdout: true).trim()
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
        choice(
            choices: 'NO\nYES',
            description: 'Release run?',
            name: 'RELEASE_RUN'
        )
        string(
            defaultValue: '70',
            description: 'For RELEASE_RUN only. Major version like 70,60, etc',
            name: 'PILLAR_VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'Minikube kubernetes version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'NO\nYES',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mongodb-operator:main',
            name: 'IMAGE_OPERATOR')
        string(
            defaultValue: '',
            description: 'MONGOD image: perconalab/percona-server-mongodb-operator:main-mongod5.0',
            name: 'IMAGE_MONGOD')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-server-mongodb-operator:main-backup',
            name: 'IMAGE_BACKUP')
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
        label 'docker-32gb'
    }

    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
    }

    stages {
        stage('Checkout sources') {
            steps {
                checkoutSources()
            }
        }
        stage('Docker Build and Push') {
            steps {
                dockerBuildPush()
            }
        }
        stage('Init tests') {
            steps {
                initTests()
            }
        }
        stage('Run Tests') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                installToolsOnNode()
                clusterRunner('cluster1')
            }
        }
    }

    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
            sh """
                echo "$TestsReport" > TestsReport.xml
                echo "$TestsImages" > TestsImages.txt
            """
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml,*.txt'

            sh """
                /usr/local/bin/minikube delete || true
            """
            deleteDir()
        }
    }
}
