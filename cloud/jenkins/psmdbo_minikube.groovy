import groovy.transform.Field

@Field def tests = []
@Field def release_versions = "source/e2e-tests/release_versions"

String getParam(String paramName, String keyName = null) {
    keyName = keyName ?: paramName

    def param = sh(script: "grep -iE '^\\s*$keyName=' $release_versions | cut -d = -f 2 | tr -d \'\"\'| tail -1", returnStdout: true).trim()
    if ("$param") {
        echo "$paramName=$param (from params file)"
    } else {
        error("$keyName not found in params file $release_versions")
    }
    return param
}

void prepareNode() {
    checkout(scm)
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tools.gitResetWorkspace()
    libraries.tools.gitClone(
        branch: GIT_BRANCH,
        repo: 'https://github.com/percona/percona-server-mongodb-operator'
    )

    if ("$PILLAR_VERSION" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="
        IMAGE_OPERATOR = IMAGE_OPERATOR ?: getParam("IMAGE_OPERATOR")
        IMAGE_MONGOD = IMAGE_MONGOD ?: getParam("IMAGE_MONGOD", "IMAGE_MONGOD${PILLAR_VERSION}")
        IMAGE_BACKUP = IMAGE_BACKUP ?: getParam("IMAGE_BACKUP")
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: getParam("IMAGE_PMM_CLIENT")
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: getParam("IMAGE_PMM_SERVER")
        IMAGE_PMM3_CLIENT = IMAGE_PMM3_CLIENT ?: getParam("IMAGE_PMM3_CLIENT")
        IMAGE_PMM3_SERVER = IMAGE_PMM3_SERVER ?: getParam("IMAGE_PMM3_SERVER")
        IMAGE_LOGCOLLECTOR = IMAGE_LOGCOLLECTOR ?: getParam("IMAGE_LOGCOLLECTOR")
        IMAGE_SEARCH = IMAGE_SEARCH ?: getParam("IMAGE_SEARCH")
        if ("$PLATFORM_VER".toLowerCase() == "rel") {
            PLATFORM_VER = getParam("PLATFORM_VER", "MINIKUBE_${PLATFORM_VER}")
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    libraries.dependencies.install()
    libraries.dependencies.installAzureCLI()
    libraries.dependencies.installUv()
    libraries.dependencies.syncPythonDeps()
    libraries.azure.auth()

    sh """
        sudo curl -sLo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo chmod +x /usr/local/bin/minikube
    """

    if ("$IMAGE_MONGOD") {
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.displayName = "#" + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER " + "$IMAGE_MONGOD".split(":")[1] + " $cw"
    }

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MONGOD-$IMAGE_BACKUP-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER-$IMAGE_LOGCOLLECTOR-$IMAGE_SEARCH | md5sum | cut -d' ' -f1", returnStdout: true).trim()
}

void dockerBuildPush() {
    echo "=========================[ Building and Pushing the operator Docker image ]========================="
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh '''
            if [[ "$IMAGE_OPERATOR" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source
                sg docker -c '
                    docker buildx create --use
                    echo "$PASS" | docker login -u "$USER" --password-stdin
                    export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
                    DOCKER_DEFAULT_PLATFORM=linux/amd64,linux/arm64 e2e-tests/build
                    docker logout
                '
                sudo rm -rf build
            fi
        '''
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
    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
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

    withCredentials([file(credentialsId: 'cloud-secret-file-psmdb', variable: 'CLOUD_SECRET_FILE')]) {
        sh '''
            cp $CLOUD_SECRET_FILE source/e2e-tests/conf/cloud-secret.yml
        '''
    }
}

void clusterRunner(String cluster) {
    sh """
        export CHANGE_MINIKUBE_NONE_USER=true
        minikube start --kubernetes-version $PLATFORM_VER --cpus=6 --memory=28G --force
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
        def testsLib = load('cloud/common/vars/tests.groovy')
        try {
            echo "The $testName test was started !"
            tests[TEST_ID]["result"] = "failure"

            timeout(time: 90, unit: 'MINUTES') {
                def testVars = testsLib.buildPsmdbTestVariables(
                    cluster_name: 'minikube',
                    skip_kubeconfig: true,
                    debug_tests: DEBUG_TESTS,
                    cluster_wide: CLUSTER_WIDE,
                    default_operator_image: "perconalab/percona-server-mongodb-operator:${GIT_BRANCH}",
                    images: [
                        IMAGE_OPERATOR    : IMAGE_OPERATOR,
                        IMAGE_MONGOD      : IMAGE_MONGOD,
                        IMAGE_BACKUP      : IMAGE_BACKUP,
                        IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
                        IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
                        IMAGE_PMM3_CLIENT : IMAGE_PMM3_CLIENT,
                        IMAGE_PMM3_SERVER : IMAGE_PMM3_SERVER,
                        IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR,
                        IMAGE_SEARCH      : IMAGE_SEARCH
                    ]
                )
                def exports = testsLib.getExportedVariablesForTests(testVars, 'cluster1')
                def testCmd = testsLib.defineTestCommand(testVars, testName)
                sh """
                    cd source

                    ${exports}

                    sudo rm -rf /tmp/hostpath-provisioner/*
                    mkdir -p e2e-tests/logs e2e-tests/reports
                    bash -o pipefail <<BASH
                    {
                        ${testCmd}
                    } 2>&1 | tee e2e-tests/logs/${testName}.log
BASH
                """
            }
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
            try {
                testsLib.pushLogFile(testName, [gitShortCommit: GIT_SHORT_COMMIT])
            } catch (logErr) {
                echo "Warning: failed to push log for $testName: ${logErr}"
            }
            echo "The $testName test was finished!"
        }
    }
}

void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
            touch $FILE_NAME
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/$GIT_SHORT_COMMIT
            aws s3 ls \$S3_PATH/$FILE_NAME || :
            aws s3 cp --quiet $FILE_NAME \$S3_PATH/$FILE_NAME || :
        """
    }
}

void makeReport() {
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tests.makeReport(tests, [
        job_name         : JOB_NAME,
        git_branch       : GIT_BRANCH,
        git_short_commit : GIT_SHORT_COMMIT,
        platform_version : PLATFORM_VER,
        cluster_wide     : CLUSTER_WIDE,
        images: [
            IMAGE_OPERATOR    : IMAGE_OPERATOR,
            IMAGE_MONGOD      : IMAGE_MONGOD,
            IMAGE_BACKUP      : IMAGE_BACKUP,
            IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
            IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
            IMAGE_PMM3_CLIENT : IMAGE_PMM3_CLIENT,
            IMAGE_PMM3_SERVER : IMAGE_PMM3_SERVER,
            IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR,
            IMAGE_SEARCH      : IMAGE_SEARCH
        ]
    ])
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        DB_TAG = sh(script: "[[ \"$IMAGE_MONGOD\" ]] && echo $IMAGE_MONGOD | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-minikube.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '80', '83', '70', '60'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mongodb-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'Minikube kubernetes version. If set to rel, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongod8.0')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-backup')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'ex: perconalab/fluentbit:main-logcollector')
        string(name: 'IMAGE_SEARCH', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongot')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Run tests with debug')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 6, unit: 'HOURS')
        copyArtifactPermission('psmdb-operator-latest-scheduler');
    }
    stages {
        stage('Prepare Node') {
            steps {
                prepareNode()
            }
        }
        stage('Docker Build and Push') {
            steps {
                dockerBuildPush()
            }
        }
        stage('Init Tests') {
            steps {
                initTests()
            }
        }
        stage('Run Tests') {
            options {
                timeout(time: 90, unit: 'MINUTES')
            }
            steps {
                clusterRunner('cluster1')
            }
        }
    }
    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()

            script {
                try {
                    def sendJobSlack = load "cloud/common/sendJobSlackNotification.groovy"
                    sendJobSlack.call(
                        tests: tests,
                        gitBranch: GIT_BRANCH,
                        platformVer: PLATFORM_VER,
                        clusterWide: CLUSTER_WIDE,
                        image: IMAGE_MONGOD,
                        operatorImage: IMAGE_OPERATOR
                    )

                } catch (err) {
                    echo "Slack helper load/call failed: ${err}"
                }
            }

            sh """
                minikube delete || true
            """
            deleteDir()
        }
    }
}
