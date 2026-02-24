tests=[]
clusters=[]
release_versions="source/e2e-tests/release_versions"

String getParam(String paramName, String keyName = null) {
    keyName = keyName ?: paramName

    param = sh(script: "grep -iE '^\\s*$keyName=' $release_versions | cut -d = -f 2 | tr -d \'\"\'| tail -1", returnStdout: true).trim()
    if ("$param") {
        echo "$paramName=$param (from params file)"
    } else {
        error("$keyName not found in params file $release_versions")
    }
    return param
}

void prepareSources() {
    echo "=========================[ Cloning the sources ]========================="
    git branch: 'cloud-slack-msg', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf source
        cloud/local/checkout $GIT_REPO $GIT_BRANCH
    """

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_PXC-$IMAGE_PROXY-$IMAGE_HAPROXY-$IMAGE_BACKUP-$IMAGE_LOGCOLLECTOR-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER | md5sum | cut -d' ' -f1", returnStdout: true).trim()
    CLUSTER_NAME = ("jenkins-" + JOB_NAME.replaceAll('_', '-') + "-" + GIT_SHORT_COMMIT).toLowerCase().trim()
}

void prepareAgent() {
    echo "=========================[ Installing tools on the Jenkins executor ]========================="

    sh """
        sudo curl -s -L -o /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
        kubectl version --client --output=yaml

        curl -fsSL https://get.helm.sh/helm-v3.19.3-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        sudo curl -fsSL https://github.com/mikefarah/yq/releases/download/v4.44.1/yq_linux_amd64 -o /usr/local/bin/yq && sudo chmod +x /usr/local/bin/yq
        sudo curl -fsSL https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux64 -o /usr/local/bin/jq && sudo chmod +x /usr/local/bin/jq

        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable pxb-84-lts
        sudo yum install -y percona-xtrabackup-84

        sudo tee /etc/yum.repos.d/google-cloud-sdk.repo << EOF
[google-cloud-cli]
name=Google Cloud CLI
baseurl=https://packages.cloud.google.com/yum/repos/cloud-sdk-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF
        sudo yum install -y google-cloud-cli google-cloud-cli-gke-gcloud-auth-plugin

        client_version=\$(curl -s https://api.github.com/repos/digitalocean/doctl/releases/latest | grep '"tag_name":' | cut -d '"' -f4 | sed 's/^v//')
        curl -sL "https://github.com/digitalocean/doctl/releases/download/v\$client_version/doctl-\$client_version-linux-amd64.tar.gz" | tar -xz && sudo mv doctl /usr/local/bin
        doctl version
    """
}

void initParams() {
    if ("$PILLAR_VERSION" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="
        IMAGE_OPERATOR = IMAGE_OPERATOR ?: getParam("IMAGE_OPERATOR")
        IMAGE_PXC = IMAGE_PXC ?: getParam("IMAGE_PXC", "IMAGE_PXC${PILLAR_VERSION}")
        IMAGE_PROXY = IMAGE_PROXY ?: getParam("IMAGE_PROXY")
        IMAGE_HAPROXY = IMAGE_HAPROXY ?: getParam("IMAGE_HAPROXY")
        IMAGE_BACKUP = IMAGE_BACKUP ?: getParam("IMAGE_BACKUP", "IMAGE_BACKUP${PILLAR_VERSION}")
        IMAGE_LOGCOLLECTOR = IMAGE_LOGCOLLECTOR ?: getParam("IMAGE_LOGCOLLECTOR")
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: getParam("IMAGE_PMM_CLIENT")
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: getParam("IMAGE_PMM_SERVER")
        IMAGE_PMM3_CLIENT = IMAGE_PMM3_CLIENT ?: getParam("IMAGE_PMM3_CLIENT")
        IMAGE_PMM3_SERVER = IMAGE_PMM3_SERVER ?: getParam("IMAGE_PMM3_SERVER")
        if ("$PLATFORM_VER".toLowerCase() == "min" || "$PLATFORM_VER".toLowerCase() == "max") {
            PLATFORM_VER = getParam("PLATFORM_VER", "DOKS_${PLATFORM_VER}")
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    if ("$PLATFORM_VER" == "latest") {
        withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
            PLATFORM_VER = sh(script: "doctl kubernetes options versions | awk 'NR==2 { print \$2 }'", returnStdout: true).trim()
            echo "Latest platform version is $PLATFORM_VER"
        }
    }

    if ("$IMAGE_PXC") {
        release = ("$PILLAR_VERSION" != "none") ? "RELEASE-" : ""
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.description = "$release$GIT_BRANCH-$PLATFORM_VER-$cw-" + "$IMAGE_PXC".split(":")[1]
    }
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
                    docker login -u '$USER' -p '$PASS'
                    export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                    make build-docker-image
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
            chmod 600 source/e2e-tests/conf/cloud-secret.yml
        """
    }
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

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("$CLUSTER_SUFFIX")

    withCredentials([string(credentialsId: 'DOKS_PROJECT_ID', variable: 'PROJECT'), string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX

            maxRetries=15
            exitCode=1
            cluster_version=\$(doctl kubernetes options versions | awk -v version=$PLATFORM_VER '\$2 == version { print \$1 }')

            while [[ \$exitCode != 0 && \$maxRetries > 0 ]]; do

                doctl kubernetes cluster create $CLUSTER_NAME-$CLUSTER_SUFFIX \
                    --region $DO_REGION \
                    --version \$cluster_version \
                    --node-pool "name=default-pool;size=s-2vcpu-4gb-amd;tag=worker;auto-scale=true;count=4;min-nodes=4;max-nodes=6" && \
                doctl kubernetes cluster kubeconfig save $CLUSTER_NAME-$CLUSTER_SUFFIX
                exitCode=\$?

                # Move cluster to a specific project to organize resources created by the test
                cluster_id="\$(doctl kubernetes cluster list --output json | jq -r --arg name $CLUSTER_NAME-$CLUSTER_SUFFIX '.[] | select(.name == \$name) | .id')"
                urn="do:kubernetes:\$cluster_id"
                doctl projects resources assign \$PROJECT --resource \$urn

                if [[ \$exitCode == 0 ]]; then break; fi
                (( maxRetries -- ))
                sleep 1

            done
            if [[ \$exitCode != 0 ]]; then exit \$exitCode; fi
        """
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
                withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
                    sh """
                        cd source

                        [[ "$DEBUG_TESTS" == "YES" ]] && export DEBUG_TESTS=1
                        [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=pxc-operator
                        [[ "$IMAGE_OPERATOR" ]] && export IMAGE=$IMAGE_OPERATOR || export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                        export IMAGE_PXC=$IMAGE_PXC
                        export IMAGE_PROXY=$IMAGE_PROXY
                        export IMAGE_HAPROXY=$IMAGE_HAPROXY
                        export IMAGE_BACKUP=$IMAGE_BACKUP
                        export IMAGE_LOGCOLLECTOR=$IMAGE_LOGCOLLECTOR
                        export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                        export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
                        export IMAGE_PMM3_CLIENT=$IMAGE_PMM3_CLIENT
                        export IMAGE_PMM3_SERVER=$IMAGE_PMM3_SERVER
                        export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix

                        e2e-tests/$testName/run
                    """
                }
            }
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$PLATFORM_VER-$DB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
            tests[TEST_ID]["result"] = "passed"
            return true
        }
        catch (exc) {
            echo "Error occurred while running test $testName: $exc"
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
            S3_PATH=s3://percona-jenkins-artifactory/$JOB_NAME/$GIT_SHORT_COMMIT
            aws s3 ls \$S3_PATH/$FILE_NAME || :
            aws s3 cp --quiet $FILE_NAME \$S3_PATH/$FILE_NAME || :
        """
    }
}

void makeReport() {
    echo "=========================[ Generating Test Report ]========================="
    testsReport = "<testsuite name=\"$JOB_NAME\">\n"
    for (int i = 0; i < tests.size(); i ++) {
        testsReport += '<testcase name="' + tests[i]["name"] + '" time="' + tests[i]["time"] + '"><'+ tests[i]["result"] +'/></testcase>\n'
    }
    testsReport += '</testsuite>\n'

    echo "=========================[ Generating Parameters Report ]========================="
    pipelineParameters = """
        testsuite name=$JOB_NAME
        IMAGE_OPERATOR=${IMAGE_OPERATOR ?: 'e2e_defaults'}
        IMAGE_PXC=${IMAGE_PXC ?: 'e2e_defaults'}
        IMAGE_PROXY=${IMAGE_PROXY ?: 'e2e_defaults'}
        IMAGE_HAPROXY=${IMAGE_HAPROXY ?: 'e2e_defaults'}
        IMAGE_BACKUP=${IMAGE_BACKUP ?: 'e2e_defaults'}
        IMAGE_LOGCOLLECTOR=${IMAGE_LOGCOLLECTOR ?: 'e2e_defaults'}
        IMAGE_PMM_CLIENT=${IMAGE_PMM_CLIENT ?: 'e2e_defaults'}
        IMAGE_PMM_SERVER=${IMAGE_PMM_SERVER ?: 'e2e_defaults'}
        IMAGE_PMM3_CLIENT=${IMAGE_PMM3_CLIENT ?: 'e2e_defaults'}
        IMAGE_PMM3_SERVER=${IMAGE_PMM3_SERVER ?: 'e2e_defaults'}
        PLATFORM_VER=$PLATFORM_VER
    """.trim().replaceAll('  ', '')

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([string(credentialsId: 'DOKS_PROJECT_ID', variable: 'PROJECT'), string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            doctl kubernetes cluster delete $CLUSTER_NAME-$CLUSTER_SUFFIX --force || true
        """
    }
    clusters.remove("$CLUSTER_SUFFIX")
}

pipeline {
    environment {
        DB_TAG = sh(script: "[[ \"$IMAGE_PXC\" ]] && echo $IMAGE_PXC | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
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
        choice(
            choices: 'NO\nYES',
            description: 'Ignore passed tests in previous run (run all)',
            name: 'IGNORE_PREVIOUS_RUN')
        choice(
            choices: 'none\n84\n80\n57',
            description: 'Implies release run.',
            name: 'PILLAR_VERSION')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'Digital Ocean kubernetes version. If set to min or max, value will be automatically taken from release_versions file.',
            name: 'PLATFORM_VER')
        choice(
            choices: 'YES\nNO',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-xtradb-cluster-operator:main',
            name: 'IMAGE_OPERATOR')
        string(
            defaultValue: '',
            description: 'PXC image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0',
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
            description: 'Backup image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PXC logcollector image: perconalab/percona-xtradb-cluster-operator:main-logcollector',
            name: 'IMAGE_LOGCOLLECTOR')
        string(
            defaultValue: '',
            description: 'PMM client image: perconalab/pmm-client:dev-latest',
            name: 'IMAGE_PMM_CLIENT')
        string(
            defaultValue: '',
            description: 'PMM server image: perconalab/pmm-server:dev-latest',
            name: 'IMAGE_PMM_SERVER')
        string(
            defaultValue: '',
            description: 'ex: perconalab/pmm-client:3-dev-latest',
            name: 'IMAGE_PMM3_CLIENT')
        string(
            defaultValue: '',
            description: 'ex: perconalab/pmm-server:3-dev-latest',
            name: 'IMAGE_PMM3_SERVER')
        string(
            defaultValue: 'nyc1',
            description: 'Digital ocean region to use for cluster',
            name: 'DO_REGION')
        choice(
            choices: 'NO\nYES',
            description: 'Run tests with debug',
            name: 'DEBUG_TESTS')
    }
    agent {
        label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('pxc-operator-latest-scheduler');
    }
    stages {
        stage('Prepare Node') {
            steps {
                script { deleteDir() }
                prepareSources()
                prepareAgent()
                initParams()
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
                timeout(time: 4, unit: 'HOURS')
            }
            parallel {
                stage('cluster1') {
                    steps {
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    steps {
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    steps {
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    steps {
                        clusterRunner('cluster4')
                    }
                }
                stage('cluster5') {
                    steps {
                        clusterRunner('cluster5')
                    }
                }
                stage('cluster6') {
                    steps {
                        clusterRunner('cluster6')
                    }
                }
                stage('cluster7') {
                    steps {
                        clusterRunner('cluster7')
                    }
                }
                stage('cluster8') {
                    steps {
                        clusterRunner('cluster8')
                    }
                }
            }
        }
    }
    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml,*.txt'

            script {
                def sendPxcSlack = load "vars/sendJobSlackNotification.groovy"
                if (sendPxcSlack != null) {
                    sendPxcSlack.call(
                    tests: tests,
                    channel: '#cloud-dev-ci',
                    gitBranch: GIT_BRANCH,
                    platformVer: PLATFORM_VER,
                    clusterWide: CLUSTER_WIDE,
                    pillarVersion: PILLAR_VERSION
                    )
                } else {
                    echo "sendJobSlackNotification.groovy load returned null, skipping Slack notification"
                }

                clusters.each { shutdownCluster(it) }
            }

            sh """
                sudo docker system prune --volumes -af
            """
            deleteDir()
        }
    }
}
