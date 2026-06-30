def getPillarVersionKey(String pillarVersion) {
    return "$pillarVersion".replace('-postgis', '')
}

Boolean usePostgisImage(String pillarVersion) {
    return "$pillarVersion".endsWith('-postgis')
}

String getParam(String releaseVersions, String paramName, String keyName = null) {
    keyName = keyName ?: paramName

    def param = sh(script: "grep -iE '^\\s*$keyName=' $releaseVersions | cut -d = -f 2 | tr -d \'\"\'| tail -1", returnStdout: true).trim()
    if ("$param") {
        echo "$paramName=$param (from params file)"
    } else {
        error("$keyName not found in params file $releaseVersions")
    }
    return param
}

String initReleaseParams(Map cfg = [:]) {
    def releaseVersions = cfg.releaseVersions ?: "source/e2e-tests/release_versions"
    def pillarVersion = cfg.pillarVersion ?: env.PILLAR_VERSION
    def platformVer = cfg.platformVer ?: env.PLATFORM_VER
    def platformPrefix = cfg.platformPrefix

    if ("$pillarVersion" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="
        def pillarVersionKey = getPillarVersionKey(pillarVersion)
        def postgresImageKey = usePostgisImage(pillarVersion) ? "IMAGE_POSTGIS${pillarVersionKey}" : "IMAGE_POSTGRESQL${pillarVersionKey}"

        env.IMAGE_OPERATOR = env.IMAGE_OPERATOR ?: getParam(releaseVersions, "IMAGE_OPERATOR")
        env.IMAGE_POSTGRESQL = env.IMAGE_POSTGRESQL ?: getParam(releaseVersions, "IMAGE_POSTGRESQL", postgresImageKey)
        env.IMAGE_PGBOUNCER = env.IMAGE_PGBOUNCER ?: getParam(releaseVersions, "IMAGE_PGBOUNCER", "IMAGE_PGBOUNCER${pillarVersionKey}")
        env.IMAGE_BACKREST = env.IMAGE_BACKREST ?: getParam(releaseVersions, "IMAGE_BACKREST", "IMAGE_BACKREST${pillarVersionKey}")
        env.IMAGE_PMM_CLIENT = env.IMAGE_PMM_CLIENT ?: getParam(releaseVersions, "IMAGE_PMM_CLIENT")
        env.IMAGE_PMM_SERVER = env.IMAGE_PMM_SERVER ?: getParam(releaseVersions, "IMAGE_PMM_SERVER")
        env.IMAGE_PMM3_CLIENT = env.IMAGE_PMM3_CLIENT ?: getParam(releaseVersions, "IMAGE_PMM3_CLIENT")
        env.IMAGE_PMM3_SERVER = env.IMAGE_PMM3_SERVER ?: getParam(releaseVersions, "IMAGE_PMM3_SERVER")
        env.IMAGE_UPGRADE = env.IMAGE_UPGRADE ?: getParam(releaseVersions, "IMAGE_UPGRADE")

        if (platformPrefix && ("$platformVer".toLowerCase() == "min" || "$platformVer".toLowerCase() == "max")) {
            platformVer = getParam(releaseVersions, "PLATFORM_VER", "${platformPrefix}_${platformVer}")
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    return platformVer
}

void setBuildDescription(String platformVer, String imagePostgresql, String clusterWide, String gitBranch, String extraPlatformInfo = null) {
    imagePostgresql = imagePostgresql ?: env.IMAGE_POSTGRESQL
    if ("$imagePostgresql") {
        def cw = ("$clusterWide" == "YES") ? "CW" : "NON-CW"
        def platform = extraPlatformInfo ? "$platformVer-$extraPlatformInfo" : "$platformVer"
        currentBuild.displayName = "#" + currentBuild.number + " $gitBranch"
        currentBuild.description = "$platform " + "$imagePostgresql".split(":")[1] + " $cw"
    }
}

String setDbTag(String imagePostgresql) {
    env.DB_TAG = sh(script: "[[ \$IMAGE_POSTGRESQL ]] && echo \$IMAGE_POSTGRESQL | awk -F':' '{tag=\$2; sub(/-postgres\$/, \"\", tag); sub(/-[0-9]+\$/, \"\", tag); print tag}' || echo main-ppg18", returnStdout: true).trim()
    echo "DB_TAG is $DB_TAG"
    return env.DB_TAG
}

void prepareSources(String gitBranch, Boolean checkoutPipeline = true) {
    echo "=========================[ Cloning the sources ]========================="
    if (checkoutPipeline) {
        checkout(scm)
    }
    sh """
        git clone -b $gitBranch https://github.com/percona/percona-postgresql-operator.git source
    """
}

Map createHash(Map cfg = [:]) {
    def gitShortCommit = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    def hashValues = [
        cfg.gitBranch,
        gitShortCommit
    ] + (cfg.extraHashValues ?: []) + [
        cfg.platformVer,
        cfg.clusterWide,
        cfg.pgVer,
        cfg.imageOperator ?: env.IMAGE_OPERATOR,
        cfg.imagePostgresql ?: env.IMAGE_POSTGRESQL,
        cfg.imagePgbouncer ?: env.IMAGE_PGBOUNCER,
        cfg.imageBackrest ?: env.IMAGE_BACKREST,
        cfg.imagePmmClient ?: env.IMAGE_PMM_CLIENT,
        cfg.imagePmmServer ?: env.IMAGE_PMM_SERVER,
        cfg.imagePmm3Client ?: env.IMAGE_PMM3_CLIENT,
        cfg.imagePmm3Server ?: env.IMAGE_PMM3_SERVER,
        cfg.imageUpgrade ?: env.IMAGE_UPGRADE
    ]
    def paramsHash = sh(script: "echo ${hashValues.collect { it ?: '' }.join('-')} | md5sum | cut -d' ' -f1", returnStdout: true).trim()
    def jobName = cfg.jobName ?: env.JOB_NAME
    def clusterName = "jenkins-${jobName}-${gitShortCommit}".replaceAll('_', '-').toLowerCase().trim()

    return [gitShortCommit: gitShortCommit, paramsHash: paramsHash, clusterName: clusterName]
}

void prepareAgentBase(Map cfg = [:]) {
    def helmVersion = cfg.helmVersion ?: '3.20.0'
    def kuttlManifestUrl = cfg.kuttlManifestUrl ?: 'https://raw.githubusercontent.com/kubernetes-sigs/krew-index/c16c6269999a2c2558e4fdc25df6eced0ab3dc27/plugins/kuttl.yaml'

    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    sh """
        sudo curl -fsSL -o /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
        kubectl version --client --output=yaml

        curl -fsSL https://get.helm.sh/helm-v${helmVersion}-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        sudo curl -fsSL -o /usr/local/bin/yq https://github.com/mikefarah/yq/releases/download/v4.44.1/yq_linux_amd64 && sudo chmod +x /usr/local/bin/yq
        sudo curl -fsSL -o /usr/local/bin/jq https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux64 && sudo chmod +x /usr/local/bin/jq

        curl -fsSL https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_amd64.tar.gz | tar -xzf -
        ./krew-linux_amd64 install krew
        export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"

        kubectl krew install assert
        kubectl krew install --manifest-url ${kuttlManifestUrl}
        echo \$(kubectl kuttl --version) is installed
    """
}

void dockerBuildPush(String gitBranch) {
    echo "=========================[ Building and Pushing the operator Docker image ]========================="
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            if [[ "$IMAGE_OPERATOR" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source
                sg docker -c "
                    echo '$PASS' | docker login -u '$USER' --password-stdin
                    export IMAGE=perconalab/percona-postgresql-operator:$gitBranch
                    make build-docker-image
                    docker logout
                "
                sudo rm -rf build
            fi
        """
    }
}

void initTests(List tests, Map cfg = [:]) {
    echo "=========================[ Initializing the tests ]========================="

    echo "Populating tests into the tests array!"
    def testList = cfg.testList ?: ''
    def suiteFileName = "source/e2e-tests/${cfg.testSuite}"

    if (testList.length() != 0) {
        suiteFileName = 'source/e2e-tests/run-custom.csv'
        sh """
            echo -e "$testList" > $suiteFileName
            echo "Custom test suite contains following tests:"
            cat $suiteFileName
        """
    }

    def records = readCSV file: suiteFileName

    for (int i = 0; i < records.size(); i++) {
        tests.add(["name": records[i][0], "cluster": "NA", "result": "skipped", "time": "0"])
    }

    echo "Marking passed tests in the tests map!"
    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        if ("${cfg.ignorePreviousRun}" == "NO") {
            sh """
                aws s3 ls s3://percona-jenkins-artifactory/${cfg.jobName}/${cfg.gitShortCommit}/ || :
            """

            for (int i = 0; i < tests.size(); i++) {
                def testName = tests[i]["name"]
                def file="${cfg.gitBranch}-${cfg.gitShortCommit}-${testName}-${cfg.platformVer}-${cfg.dbTag}-CW_${cfg.clusterWide}-${cfg.paramsHash}"
                def retFileExists = sh(script: "aws s3api head-object --bucket percona-jenkins-artifactory --key ${cfg.jobName}/${cfg.gitShortCommit}/$file >/dev/null 2>&1", returnStatus: true)

                if (retFileExists == 0) {
                    tests[i]["result"] = "passed"
                }
            }
        } else {
            sh """
                aws s3 rm "s3://percona-jenkins-artifactory/${cfg.jobName}/${cfg.gitShortCommit}/" --recursive --exclude "*" --include "*-${cfg.paramsHash}" || :
            """
        }
    }

    withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE'), file(credentialsId: 'cloud-minio-secret-file', variable: 'CLOUD_MINIO_SECRET_FILE')]) {
        sh """
            cp $CLOUD_SECRET_FILE source/e2e-tests/conf/cloud-secret.yml
            chmod 600 source/e2e-tests/conf/cloud-secret.yml
            cp $CLOUD_MINIO_SECRET_FILE source/e2e-tests/conf/cloud-secret-minio-gw.yml
            chmod 600 source/e2e-tests/conf/cloud-secret-minio-gw.yml
        """
    }
    stash includes: "source/**,cloud/common/pgoPipelineCommon.groovy", name: "sourceFILES"
}

def getPipelineValue(def pipelineScript, String name) {
    try {
        return pipelineScript."$name"
    } catch (ignored) {
        return env[name]
    }
}

Map testConfig(def pipelineScript, Map provider = [:]) {
    return [
        testList: getPipelineValue(pipelineScript, 'TEST_LIST'),
        testSuite: getPipelineValue(pipelineScript, 'TEST_SUITE'),
        ignorePreviousRun: getPipelineValue(pipelineScript, 'IGNORE_PREVIOUS_RUN'),
        jobName: getPipelineValue(pipelineScript, 'JOB_NAME'),
        gitBranch: getPipelineValue(pipelineScript, 'GIT_BRANCH'),
        gitShortCommit: getPipelineValue(pipelineScript, 'GIT_SHORT_COMMIT'),
        platformVer: getPipelineValue(pipelineScript, 'PLATFORM_VER'),
        dbTag: getPipelineValue(pipelineScript, 'DB_TAG'),
        clusterWide: getPipelineValue(pipelineScript, 'CLUSTER_WIDE'),
        paramsHash: getPipelineValue(pipelineScript, 'PARAMS_HASH'),
        pgVer: getPipelineValue(pipelineScript, 'PG_VER'),
        imageOperator: getPipelineValue(pipelineScript, 'IMAGE_OPERATOR'),
        imagePostgresql: getPipelineValue(pipelineScript, 'IMAGE_POSTGRESQL'),
        imagePgbouncer: getPipelineValue(pipelineScript, 'IMAGE_PGBOUNCER'),
        imageBackrest: getPipelineValue(pipelineScript, 'IMAGE_BACKREST'),
        imagePmmClient: getPipelineValue(pipelineScript, 'IMAGE_PMM_CLIENT'),
        imagePmmServer: getPipelineValue(pipelineScript, 'IMAGE_PMM_SERVER'),
        imagePmm3Client: getPipelineValue(pipelineScript, 'IMAGE_PMM3_CLIENT'),
        imagePmm3Server: getPipelineValue(pipelineScript, 'IMAGE_PMM3_SERVER'),
        imageUpgrade: getPipelineValue(pipelineScript, 'IMAGE_UPGRADE'),
        clusterName: provider.clusterName ?: getPipelineValue(pipelineScript, 'CLUSTER_NAME'),
        kubeconfig: provider.kubeconfig,
        skipTestWarnings: getPipelineValue(pipelineScript, 'SKIP_TEST_WARNINGS'),
        extraParameters: provider.extraParameters ?: [:]
    ]
}

void clusterRunner(List tests, String cluster, Closure createClusterFn, Closure runTestFn, Closure shutdownClusterFn) {
    def clusterCreated = 0

    for (int i = 0; i < tests.size(); i++) {
        if (tests[i]["result"] == "skipped") {
            tests[i]["result"] = "failure"
            tests[i]["cluster"] = cluster
            if (clusterCreated == 0) {
                createClusterFn(cluster)
                clusterCreated++
            }
            runTestFn(i)
        }
    }

    if (clusterCreated >= 1) {
        shutdownClusterFn(cluster)
    }
}

void runTest(List tests, Integer testId, Map cfg = [:]) {
    def retryCount = 0
    def testName = tests[testId]["name"]
    def clusterSuffix = tests[testId]["cluster"]
    def imageOperator = cfg.imageOperator ?: env.IMAGE_OPERATOR ?: ''
    def imagePostgresql = cfg.imagePostgresql ?: env.IMAGE_POSTGRESQL ?: ''
    def imagePgbouncer = cfg.imagePgbouncer ?: env.IMAGE_PGBOUNCER ?: ''
    def imageBackrest = cfg.imageBackrest ?: env.IMAGE_BACKREST ?: ''
    def imagePmmClient = cfg.imagePmmClient ?: env.IMAGE_PMM_CLIENT ?: ''
    def imagePmmServer = cfg.imagePmmServer ?: env.IMAGE_PMM_SERVER ?: ''
    def imagePmm3Client = cfg.imagePmm3Client ?: env.IMAGE_PMM3_CLIENT ?: ''
    def imagePmm3Server = cfg.imagePmm3Server ?: env.IMAGE_PMM3_SERVER ?: ''
    def imageUpgrade = cfg.imageUpgrade ?: env.IMAGE_UPGRADE ?: ''
    def pgVer = cfg.pgVer ?: env.PG_VER ?: ''
    def kubeconfig = (cfg.kubeconfig ?: '').replace('$clusterSuffix', clusterSuffix)
    def skipTestWarnings = cfg.skipTestWarnings ?: env.SKIP_TEST_WARNINGS ?: ''

    waitUntil {
        def timeStart = new Date().getTime()
        try {
            echo "The $testName test was started on cluster ${cfg.clusterName}-$clusterSuffix !"
            tests[testId]["result"] = "failure"

            timeout(time: cfg.timeoutMinutes ?: 90, unit: 'MINUTES') {
                def body = {
                    sh """
                        cd source

                        [[ "${cfg.clusterWide}" == "YES" ]] && export OPERATOR_NS=pg-operator
                        [[ "${imageOperator}" ]] && export IMAGE=${imageOperator} || export IMAGE=perconalab/percona-postgresql-operator:${cfg.gitBranch}
                        export PG_VER=${pgVer}
                        if [[ "${imagePostgresql}" ]]; then
                            export IMAGE_POSTGRESQL=${imagePostgresql}
                            export PG_VER=\$(echo \$IMAGE_POSTGRESQL | sed -E 's/.*:(.*ppg)?([0-9]+).*/\\2/')
                        fi
                        export IMAGE_PGBOUNCER=${imagePgbouncer}
                        export IMAGE_BACKREST=${imageBackrest}
                        export IMAGE_PMM_CLIENT=${imagePmmClient}
                        export IMAGE_PMM_SERVER=${imagePmmServer}
                        export IMAGE_PMM3_CLIENT=${imagePmm3Client}
                        export IMAGE_PMM3_SERVER=${imagePmm3Server}
                        export IMAGE_UPGRADE=${imageUpgrade}
                        export KUBECONFIG=${kubeconfig}
                        export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"
                        export SKIP_TEST_WARNINGS=${skipTestWarnings}

                        kubectl kuttl test --config e2e-tests/kuttl.yaml --test "^$testName\$"
                    """
                }

                if (cfg.credentials) {
                    withCredentials(cfg.credentials) {
                        body()
                    }
                } else {
                    body()
                }
            }
            pushArtifactFile("${cfg.gitBranch}-${cfg.gitShortCommit}-${testName}-${cfg.platformVer}-${cfg.dbTag}-CW_${cfg.clusterWide}-${cfg.paramsHash}", cfg.jobName, cfg.gitShortCommit)
            tests[testId]["result"] = "passed"
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
            tests[testId]["time"] = durationSec
            echo "The $testName test was finished!"
        }
    }
}

void pushArtifactFile(String fileName, String jobName, String gitShortCommit) {
    echo "Push $fileName file to S3!"

    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
            touch $fileName
            S3_PATH=s3://percona-jenkins-artifactory/$jobName/$gitShortCommit
            aws s3 ls \$S3_PATH/$fileName || :
            aws s3 cp --quiet $fileName \$S3_PATH/$fileName || :
        """
    }
}

void makeReport(List tests, Map cfg = [:]) {
    echo "=========================[ Generating Test Report ]========================="
    def testsReport = "<testsuite name=\"${cfg.jobName}\">\n"
    for (int i = 0; i < tests.size(); i++) {
        testsReport += '<testcase name="' + tests[i]["name"] + '" time="' + tests[i]["time"] + '"><'+ tests[i]["result"] +'/></testcase>\n'
    }
    testsReport += '</testsuite>\n'

    echo "=========================[ Generating Parameters Report ]========================="
    def parameterLines = [
        "testsuite name=${cfg.jobName}",
        "PG_VER=${cfg.pgVer ?: 'e2e_defaults'}",
        "IMAGE_OPERATOR=${cfg.imageOperator ?: env.IMAGE_OPERATOR ?: 'e2e_defaults'}",
        "IMAGE_POSTGRESQL=${cfg.imagePostgresql ?: env.IMAGE_POSTGRESQL ?: 'e2e_defaults'}",
        "IMAGE_PGBOUNCER=${cfg.imagePgbouncer ?: env.IMAGE_PGBOUNCER ?: 'e2e_defaults'}",
        "IMAGE_BACKREST=${cfg.imageBackrest ?: env.IMAGE_BACKREST ?: 'e2e_defaults'}",
        "IMAGE_PMM_CLIENT=${cfg.imagePmmClient ?: env.IMAGE_PMM_CLIENT ?: 'e2e_defaults'}",
        "IMAGE_PMM_SERVER=${cfg.imagePmmServer ?: env.IMAGE_PMM_SERVER ?: 'e2e_defaults'}",
        "IMAGE_PMM3_CLIENT=${cfg.imagePmm3Client ?: env.IMAGE_PMM3_CLIENT ?: 'e2e_defaults'}",
        "IMAGE_PMM3_SERVER=${cfg.imagePmm3Server ?: env.IMAGE_PMM3_SERVER ?: 'e2e_defaults'}",
        "IMAGE_UPGRADE=${cfg.imageUpgrade ?: env.IMAGE_UPGRADE ?: 'e2e_defaults'}",
        "PLATFORM_VER=${cfg.platformVer}"
    ]
    (cfg.extraParameters ?: [:]).each { key, value ->
        parameterLines.add("${key}=${value}")
    }
    def pipelineParameters = parameterLines.join('\n')

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

void postCleanup(List tests, Map cfg = [:]) {
    echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
    makeReport(tests, cfg)

    junit testResults: '*.xml', healthScaleFactor: 1.0
    archiveArtifacts '*.xml,*.txt'

    try {
        def sendJobSlack = load "cloud/common/sendJobSlackNotification.groovy"
        def slackCfg = [
            tests: tests,
            gitBranch: cfg.gitBranch,
            platformVer: cfg.platformVer,
            clusterWide: cfg.clusterWide,
            image: cfg.imagePostgresql ?: env.IMAGE_POSTGRESQL,
            operatorImage: cfg.imageOperator ?: env.IMAGE_OPERATOR
        ] + (cfg.slack ?: [:])
        sendJobSlack.call(slackCfg)
    } catch (err) {
        echo "Slack helper load/call failed: ${err}"
    }

    if (cfg.clusters && cfg.shutdownClusterFn) {
        cfg.clusters.toList().each { cfg.shutdownClusterFn(it) }
    }

    sh """
        sudo docker system prune --volumes -af
        sudo rm -rf *
    """
    deleteDir()
}

void cleanupKubernetesNamespaces(String kubeconfig, Map cfg = [:]) {
    def pvcCleanup = cfg.deletePvcs ? 'kubectl delete pvc --all -n $namespace || true' : ':'

    sh """
        export KUBECONFIG=$kubeconfig
        for namespace in \$(kubectl get namespaces --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
            kubectl delete deployments --all -n \$namespace --force --grace-period=0 || true
            kubectl delete sts --all -n \$namespace --force --grace-period=0 || true
            kubectl delete replicasets --all -n \$namespace --force --grace-period=0 || true
            kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 || true
            kubectl delete services --all -n \$namespace --force --grace-period=0 || true
            kubectl delete pods --all -n \$namespace --force --grace-period=0 || true
            ${pvcCleanup}
        done
        kubectl get svc --all-namespaces || true
    """
}

return this
