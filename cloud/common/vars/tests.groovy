void loadCloudSecret(String operator) {
    def credentialsId
    switch (operator) {
        case 'pg':
        case 'pxc':
            credentialsId = 'cloud-secret-file'
            break
        case 'ps':
            credentialsId = 'cloud-secret-file-ps'
            break
        case 'psmdb':
            credentialsId = 'cloud-secret-file-psmdb'
            break
        default:
            error("Unsupported operator for cloud secret credentials: ${operator}")
    }

    withCredentials([
        file(
            credentialsId: credentialsId,
            variable: 'CLOUD_SECRET_FILE'
        ),
        file(
            credentialsId: 'cloud-minio-secret-file', 
            variable: 'CLOUD_MINIO_SECRET_FILE'
        )
    ]) {
        sh '''
            cp "$CLOUD_SECRET_FILE" source/e2e-tests/conf/cloud-secret.yml
            chmod 600 source/e2e-tests/conf/cloud-secret.yml
            cp "$CLOUD_MINIO_SECRET_FILE" source/e2e-tests/conf/cloud-secret-minio-gw.yml
            chmod 600 source/e2e-tests/conf/cloud-secret-minio-gw.yml
        '''
    }
}

String getReleaseVersionsParam(String releaseVersions, String paramName, String keyName = null) {
    keyName = keyName ?: paramName

    def param = sh(
        script: "grep -iE '^\\s*${keyName}=' ${releaseVersions} | cut -d = -f 2 | tr -d '\\\"' | tail -1",
        returnStdout: true
    ).trim()

    if (!param) {
        error("${keyName} not found in params file ${releaseVersions}")
    }

    echo "${paramName}=${param} (from params file)"
    return param
}

String getClusterFullName(String clusterName, String clusterSuffix) {
    return "${clusterName}-${clusterSuffix}"
}

String imageTag(String image) {
    if (!image?.trim()) {
        return ""
    }

    def parts = image.tokenize(":")
    return parts.size() > 1 ? parts[-1] : ""
}

String getDbTag(Map testVariables) {
    def dbImage = [
        testVariables.images?.IMAGE_MONGOD,
        testVariables.images?.IMAGE_MYSQL,
        testVariables.images?.IMAGE_PXC,
        testVariables.images?.IMAGE_POSTGRESQL
    ].find { it?.trim() }

    return imageTag(dbImage)
}

String getDbVersion(Map testVariables) {
    return [
        testVariables.pillar_version,
        testVariables.db_version,
        testVariables.db_tag
    ].find { it?.toString()?.trim() && !it.toString().equalsIgnoreCase("none") } ?: ""
}

String getMinorPlatformVersion(String platformVersion) {
    def matcher = platformVersion =~ /v?(\d+\.\d+)/
    return matcher ? matcher[0][1] : platformVersion
}

String buildJobDescription(Map testVariables) {
    def cw = "${testVariables.cluster_wide}" == "YES" ? "CW" : "NON-CW"
    def arch = testVariables.platform_arch ?: ""

    return [
        getMinorPlatformVersion("${testVariables.platform_version}"),
        arch,
        getDbVersion(testVariables),
        cw
    ].findAll { it?.trim() }.join(" ")
}

void printTestVariables(Map testVariables) {
    def sensitivePattern = ~/(?i).*(password|secret|token|key|credential).*/
    def sanitized = testVariables.collectEntries { key, value ->
        if (key == "libraries") {
            return [(key): "<libraries>"]
        }

        if (key == "tests") {
            return [(key): "<${value?.size() ?: 0} tests>"]
        }

        if ("${key}" ==~ sensitivePattern) {
            return [(key): "<redacted>"]
        }

        if (value instanceof Map) {
            return [(key): value.collectEntries { nestedKey, nestedValue ->
                [(nestedKey): ("${nestedKey}" ==~ sensitivePattern ? "<redacted>" : nestedValue)]
            }]
        }

        return [(key): value]
    }

    echo "=========================[ Test variables ]========================="
    echo groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(sanitized))
}

String getReleaseParamName(String imageName, String pillarVersion, String operator, String ubiVersion = null) {
    if (operator?.equalsIgnoreCase("pg-operator") && pillarVersion.endsWith("-community")) {
        def pgVersion = pillarVersion.replace("-community", "")
        def communityImages = [
            IMAGE_POSTGRESQL: "IMAGE_POSTGRESQL${pgVersion}_${ubiVersion}_COMMUNITY",
            IMAGE_PGBOUNCER : "IMAGE_PGBOUNCER_COMMUNITY",
            IMAGE_BACKREST  : "IMAGE_PGBACKREST_COMMUNITY",
            IMAGE_UPGRADE   : "IMAGE_UPGRADE_${ubiVersion}_COMMUNITY"
        ]
        return communityImages[imageName] ?: imageName
    }

    def operatorImages = [
        "psmdb-operator": [
            IMAGE_MONGOD: "IMAGE_MONGOD${pillarVersion}"
        ],
        "ps-operator": [
            IMAGE_MYSQL: "IMAGE_MYSQL${pillarVersion}"
        ],
        "pxc-operator": [
            IMAGE_PXC   : "IMAGE_PXC${pillarVersion}",
            IMAGE_BACKUP: "IMAGE_BACKUP${pillarVersion}"
        ],
        "pg-operator": [
            IMAGE_POSTGRESQL: "IMAGE_POSTGRESQL${pillarVersion}",
            IMAGE_PGBOUNCER : "IMAGE_PGBOUNCER${pillarVersion}",
            IMAGE_BACKREST  : "IMAGE_BACKREST${pillarVersion}"
        ]
    ]

    if (operator?.equalsIgnoreCase("pg-operator") &&
        imageName == "IMAGE_POSTGRESQL" &&
        pillarVersion.endsWith("-postgis")) {
        return "IMAGE_POSTGIS${pillarVersion}"
    }

    return operatorImages[operator?.toLowerCase()]?.get(imageName) ?: imageName
}

Boolean isReleaseRun(Map testVariables) {
    return "${testVariables.pillar_version}" != "none"
}

void resolveReleaseRunParams(Map testVariables) {
    echo "=========================[ Getting parameters for release test ]========================="
    testVariables.platform_channel = "stable"
    echo "Forcing channel=stable, because it's a release run!"

    testVariables.images = resolveImages(testVariables)

    def supportedPlatforms = ["gke", "aks", "eks", "openshift", "doks", "rke2", "minikube"]
    if (!(testVariables.platform in supportedPlatforms)) {
        error("Unsupported platform: ${testVariables.platform}")
    }

    if (testVariables.platform_provider?.toLowerCase() == "rancher") {
        ["rancher_version": "RANCHER", "cert_manager_version": "CERT_MANAGER"].each { field, key ->
            if (!testVariables[field] || testVariables[field] == "latest") {
                testVariables[field] = getReleaseVersionsParam(testVariables.release_versions, key)
            }
        }
    }
}

void resolvePlatformVersion(Map testVariables) {
    def library = testVariables.libraries[testVariables.platform_provider]
    def platformVersion = testVariables.platform_version

    if (platformVersion?.toLowerCase() in ["min", "max"]) {
        platformVersion = getReleaseVersionsParam(
            testVariables.release_versions,
            "${testVariables.platform.toUpperCase()}_${platformVersion.toUpperCase()}"
        )
    } else if (platformVersion == "latest") {
        platformVersion = library.getLatestPlatformVersion(testVariables)

        testVariables.platform_version = platformVersion
        return
    }

    testVariables.platform_version = library.getPlatformVersion(platformVersion)
}

void resolveMachineType(Map testVariables) {
    if (testVariables.platform_arch && testVariables.platform_provider) {
        testVariables.machine_type = testVariables.libraries[testVariables.platform_provider].getMachineType(
            testVariables.platform_arch
        )
    }
}

Map prepareVersions(Map testVariables) {
    if (isReleaseRun(testVariables)) {
        resolveReleaseRunParams(testVariables)
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    resolvePlatformVersion(testVariables)
    resolveMachineType(testVariables)

    if (!testVariables.db_tag) {
        testVariables.db_tag = getDbTag(testVariables)
    }

    testVariables.git_short_commit = sh(
        script: 'git -C source rev-parse --short HEAD',
        returnStdout: true
    ).trim()

    testVariables.cluster_name = sh(
        script: "echo jenkins-${testVariables.job_name}-${testVariables.git_short_commit} | tr '[:upper:]' '[:lower:]'",
        returnStdout: true
    ).trim()

    testVariables.params_hash = buildParamsHash(testVariables)

    return testVariables
}

List loadTestList(String testList, String testSuite) {
    echo "=========================[ Loading tests ]========================="
    def suiteFileName = "source/e2e-tests/${testSuite}"

    if (testList?.trim()) {
        suiteFileName = "source/e2e-tests/run-custom.csv"

        writeFile file: suiteFileName, text: testList

        sh """
            echo "Custom test suite contains following tests:"
            cat ${suiteFileName}
        """
    }

    def tests = readCSV(file: suiteFileName).collect { record ->
        [
            name   : record[0],
            cluster: "NA",
            result : "skipped",
            time   : 0.0,
        ]
    }

    echo "Loaded ${tests.size()} tests:"
    echo tests.collect { " - ${it.name}" }.join('\n')

    return tests
}

void initTests(List tests, Map testVariables, Map config) {
    echo "=========================[ Initializing the tests ]========================="

    echo "Populating tests into the tests array!"
    def suiteFileName = "source/e2e-tests/${config.testSuite}"

    if (config.testList?.trim()) {
        suiteFileName = "source/e2e-tests/run-custom.csv"
        writeFile file: suiteFileName, text: config.testList
        sh """
            echo "Custom test suite contains following tests:"
            cat ${suiteFileName}
        """
    }

    readCSV(file: suiteFileName).each { record ->
        tests.add([name: record[0], cluster: "NA", result: "skipped", time: "0"])
    }

    echo "Marking passed tests in the tests map!"
    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        if (config.ignorePreviousRun == "NO") {
            sh """
                aws s3 ls s3://percona-jenkins-artifactory/${testVariables.job_name}/${testVariables.git_short_commit}/ || :
            """

            tests.each { test ->
                def file = artifactFileName(testVariables, test.name)
                def retFileExists = sh(
                    script: "aws s3api head-object --bucket percona-jenkins-artifactory --key ${testVariables.job_name}/${testVariables.git_short_commit}/${file} >/dev/null 2>&1",
                    returnStatus: true
                )
                if (retFileExists == 0) {
                    test.result = "passed"
                }
            }
        } else {
            sh """
                aws s3 rm "s3://percona-jenkins-artifactory/${testVariables.job_name}/${testVariables.git_short_commit}/" --recursive --exclude "*" --include "*-${testVariables.params_hash}" || :
            """
        }
    }

    withCredentials([file(credentialsId: config.cloudSecretCredentialId, variable: 'CLOUD_SECRET_FILE')]) {
        sh """
            cp \$CLOUD_SECRET_FILE source/e2e-tests/conf/cloud-secret.yml
            ${config.secretFileMode ? "chmod ${config.secretFileMode} source/e2e-tests/conf/cloud-secret.yml" : ""}
        """
    }
}

String artifactFileName(Map cfg, String testName) {
    return [
        testVariables.git_branch,
        testVariables.git_short_commit,
        testName,
        testVariables.platform_version,
        getDbVersion(testVariables),
        "CW_${testVariables.cluster_wide}",
        testVariables.params_hash
    ].findAll { it?.toString()?.trim() }.join("-")
}

String buildParamsHash(Map testVariables) {
    def hashValues = [
        testVariables.git_branch,
        testVariables.git_short_commit,
        testVariables.platform_version,
        testVariables.cluster_wide,
        testVariables.platform_arch,
        testVariables.platform_channel,
        getDbVersion(testVariables),
    ].findAll { it != null }

    testVariables.images.values().findAll { it }.each { imageValue ->
        hashValues << imageValue
    }

    return sh(
        script: "echo '${hashValues.join('-')}' | md5sum | cut -d' ' -f1",
        returnStdout: true
    ).trim()
}

void pushArtifactFile(String fileName, String gitShortCommit) {
    gitShortCommit = gitShortCommit ?: env.GIT_SHORT_COMMIT

    echo "Push ${fileName} file to S3!"

    withCredentials([aws(
        credentialsId: 'AMI/OVF',
        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
    )]) {
        sh """
            touch ${fileName}

            S3_PATH=s3://percona-jenkins-artifactory/${JOB_NAME}/${gitShortCommit}

            aws s3 ls \$S3_PATH/${fileName} || :
            aws s3 cp --quiet ${fileName} \$S3_PATH/${fileName} || :
        """
    }
}

void updateListWithLastExecutionStatus(Map testVariables) {
    echo "=========================[ Checking previous execution ]========================="

    withCredentials([aws(
        credentialsId: 'AMI/OVF',
        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
    )]) {
        sh """
            aws s3 ls s3://percona-jenkins-artifactory/${testVariables.job_name}/${testVariables.git_short_commit}/ || :
        """

        testVariables.tests.each { test ->
            def file = artifactFileName(testVariables, test.name)
            def retFileExists = sh(
                script: """
                    aws s3api head-object \
                        --bucket percona-jenkins-artifactory \
                        --key ${testVariables.job_name}/${testVariables.git_short_commit}/${file} \
                        >/dev/null 2>&1
                """,
                returnStatus: true
            )

            if (retFileExists == 0) {
                test.result = "passed"
            }
        }
    }
}

Map resolveImages(Map testVariables) {
    def resolvedImages = [:]
    def releaseRun = "${testVariables.pillar_version}" != "none"

    testVariables.images.each { imageName, imageValue ->
        if (!releaseRun) {
            resolvedImages[imageName] = imageValue
            return
        }

        def releaseParamName = getReleaseParamName(
            imageName,
            testVariables.pillar_version,
            testVariables.operator,
            testVariables.ubi_version
        )

        resolvedImages[imageName] = imageValue ?: getReleaseVersionsParam(
            testVariables.release_versions,
            imageName,
            releaseParamName
        )
    }

    return resolvedImages
}

String getExportedVariablesForTests(Map testVariables, String clusterSuffix) {
    def exports = []

    if (testVariables.kubeconfig) {
        exports << "export KUBECONFIG=${testVariables.kubeconfig}"
    } else if (!testVariables.skip_kubeconfig) {
        exports << "export KUBECONFIG=${testVariables.kubeconfigPath ?: '/tmp'}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}"
    }

    exports << "[[ '${testVariables.debug_tests}' == 'YES' ]] && export DEBUG_TESTS=1"
    exports << "[[ '${testVariables.cluster_wide}' == 'YES' ]] && export OPERATOR_NS='${testVariables.operator}'"
    exports << """
        [[ '${testVariables.images.IMAGE_OPERATOR}' ]] && \
            export IMAGE='${testVariables.images.IMAGE_OPERATOR}' || \
            export IMAGE='${testVariables.default_operator_image}'
    """.stripIndent().trim()

    testVariables.images.each { imageName, imageValue ->
        exports << "export ${imageName}='${imageValue ?: ""}'"
    }

    if (testVariables.images.IMAGE_POSTGRESQL) {
        exports << "export PG_VER=\$(echo \$IMAGE_POSTGRESQL | sed -E 's/.*:(.*ppg)?([0-9]+).*/\\2/')"
    }

    if (testVariables.test_executor_type == "make") {
        exports << 'export PATH="$HOME/.local/bin:$PATH"'
        exports << 'export SKIP_DELETE=0'
        exports << 'export COLUMNS=200'
    }

    testVariables.extra_envs?.each { key, value ->
        exports << "export ${key}='${value ?: ""}'"
    }

    return exports.join("\n")
}

Map buildPsmdbTestVariables(Map config) {
    return [
        cluster_name           : config.cluster_name,
        kubeconfigPath         : config.kubeconfigPath ?: '/tmp',
        kubeconfig             : config.kubeconfig,
        skip_kubeconfig        : config.skip_kubeconfig ?: false,
        debug_tests            : config.debug_tests,
        cluster_wide           : config.cluster_wide,
        operator               : 'psmdb-operator',
        default_operator_image : config.default_operator_image,
        test_executor_type     : 'make',
        images                 : config.images,
        extra_envs             : config.extra_envs ?: [:]
    ]
}

Map buildPxcTestVariables(Map config) {
    return [
        cluster_name           : config.cluster_name,
        kubeconfigPath         : config.kubeconfigPath ?: '/tmp',
        kubeconfig             : config.kubeconfig,
        skip_kubeconfig        : config.skip_kubeconfig ?: false,
        debug_tests            : config.debug_tests,
        cluster_wide           : config.cluster_wide,
        operator               : 'pxc-operator',
        default_operator_image : config.default_operator_image,
        images                 : config.images,
        extra_envs             : config.extra_envs ?: [:]
    ]
}

String defineTestCommand(Map testVariables, String testName) {
    switch (testVariables.test_executor_type) {
        case "kuttl":
            return """
                export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"
                kubectl kuttl test --config e2e-tests/kuttl.yaml --test '^${testName}\$'
            """

        case "make":
            return """
                mkdir -p e2e-tests/{logs,reports}
                set -o pipefail
                make e2e-test TEST=${testName} 2>&1 | tee e2e-tests/logs/${testName}.log
            """

        default:
            return "e2e-tests/${testName}/run"
    }
}

void cleanupFailedTestNamespaces(Map testVariables, String testName, String clusterSuffix) {
    def kubeconfig = "${testVariables.kubeconfigPath ?: '/tmp'}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}"

    echo "Cleaning failed test namespaces for ${testName} on ${getClusterFullName(testVariables.cluster_name, clusterSuffix)}"

    withEnv([
        "FAILED_TEST_NAME=${testName}",
        "KUBECONFIG=${kubeconfig}"
    ]) {
        sh '''
            set +e

            if [ ! -s "$KUBECONFIG" ] || ! kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
                echo "Skipping failed test namespace cleanup: Kubernetes API is not reachable for $KUBECONFIG"
                exit 0
            fi

            kubectl get namespaces --request-timeout=10s --no-headers \
                | awk '{print $1}' \
                | while read -r namespace; do
                    case "$namespace" in
                        "$FAILED_TEST_NAME"-*|kuttl*)
                            echo "Removing finalizers from resources in namespace: $namespace"
                            kubectl api-resources --verbs=list --namespaced -o name --request-timeout=10s \
                                | while read -r resource; do
                                    kubectl get "$resource" -n "$namespace" -o name --ignore-not-found --request-timeout=10s 2>/dev/null \
                                        | while read -r object; do
                                            kubectl patch "$object" -n "$namespace" --type=merge -p '{"metadata":{"finalizers":[]}}' --request-timeout=10s || true
                                        done
                                done

                            echo "Deleting namespace: $namespace"
                            kubectl delete namespace "$namespace" --force --grace-period=0 --wait=false --request-timeout=10s || true
                            ;;
                    esac
                done
        '''
    }
}

// Below functions are annotated with @NonCPS because they are called from parallel stages and manipulate shared state.
@com.cloudbees.groovy.cps.NonCPS
Integer claimNextSkippedTest(List tests, String clusterSuffix) {
    synchronized (tests) {
        def index = tests.findIndexOf { test ->
            test.result == "skipped"
        }

        if (index < 0) {
            return null
        }

        tests[index].result = "failure"
        tests[index].cluster = clusterSuffix

        return index
    }
}

@com.cloudbees.groovy.cps.NonCPS
void updateTestResult(List tests, Integer testId, String result) {
    synchronized (tests) {
        tests[testId].result = result
    }
}

@com.cloudbees.groovy.cps.NonCPS
void updateTestTime(List tests, Integer testId, Object time) {
    synchronized (tests) {
        tests[testId].time = time
    }
}

@com.cloudbees.groovy.cps.NonCPS
Double elapsedSeconds(Object elapsedMillis) {
    return String.format('%.1f', ((elapsedMillis ?: 0) as Double) / 1000) as Double
}

@com.cloudbees.groovy.cps.NonCPS
void addCluster(List clusters, String clusterSuffix) {
    synchronized (clusters) {
        if (!clusters.contains(clusterSuffix)) {
            clusters.add(clusterSuffix)
        }
    }
}

@com.cloudbees.groovy.cps.NonCPS
void removeCluster(List clusters, String clusterSuffix) {
    synchronized (clusters) {
        clusters.remove(clusterSuffix)
    }
}

void runTest(Map testConfig) {
    def testVariables = testConfig.testVariables
    def testId = testConfig.testId
    def testName = testVariables.tests[testId].name
    def clusterSuffix = testConfig.clusterSuffix
    def retries = (testConfig.retries ?: 1) as Integer
    def maxAttempts = retries + 1
    def timeStart = System.currentTimeMillis()

    updateTestResult(testVariables.tests, testId, "failure")

    try {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            echo "The ${testName} test was started on cluster ${getClusterFullName(testVariables.cluster_name, clusterSuffix)} (attempt ${attempt}/${maxAttempts})!"

            def exitCode = 1
            try {
                timeout(time: 90, unit: 'MINUTES') {
                    def exports = getExportedVariablesForTests(testVariables, clusterSuffix)
                    def command = defineTestCommand(testVariables, testName)

                    exitCode = sh(
                        script: """
                            cd source
                            ${exports}
                            ${command}
                        """,
                        returnStatus: true
                    )
                }
            } catch (exc) {
                echo "Error occurred while running test ${testName}: ${exc}"
            }

            if (exitCode == 0) {
                pushArtifactFile(
                    artifactFileName(testVariables, testName),
                    testVariables.git_short_commit
                )
                updateTestResult(testVariables.tests, testId, "passed")
                return
            }

            echo "The ${testName} test failed with exit code ${exitCode} on attempt ${attempt}/${maxAttempts}"
            try {
                testVariables.libraries.tools.kubernetesCleanupFailedTestNamespaces(testVariables, testName, clusterSuffix)
            } catch (cleanupErr) {
                echo "Warning: failed to cleanup namespaces for ${testName}: ${cleanupErr}"
            }

            if (attempt < maxAttempts) {
                catchError(
                    buildResult: 'SUCCESS',
                    stageResult: 'SUCCESS',
                    message: "Retrying ${testName} on the same cluster"
                ) {
                    error("Retrying ${testName} on the same cluster")
                }
            }
        }

        catchError(
            buildResult: 'FAILURE',
            stageResult: 'FAILURE',
            message: "Test ${testName} failed after ${maxAttempts} attempts"
        ) {
            error("Test ${testName} failed after ${maxAttempts} attempts")
        }
    } finally {
        updateTestTime(testVariables.tests, testId, elapsedSeconds(System.currentTimeMillis() - timeStart))
        try {
            pushLogFile(testName, [
                sourceDir     : 'source',
                gitShortCommit: testVariables.git_short_commit
            ])
        } catch (logErr) {
            echo "Warning: failed to push log for ${testName}: ${logErr}"
        }
        echo "The ${testName} test was finished!"
    }
}

void clusterRunner(String clusterSuffix, Map testVariables) {
    def createdClusters = []
    def clusterCreated = false
    def clusterCfg = [
        clusterName     : testVariables.cluster_name,
        clusterSuffix   : clusterSuffix,
        product         : testVariables.operator,
        platformVersion : testVariables.platform_version,
        platformChannel : testVariables.platform_channel,
        machineType     : testVariables.machine_type,
        workerCountMin  : testVariables.worker_min_count ?: 4,
        workerCountMax  : testVariables.worker_max_count ?: 6,
        region          : testVariables.region ?: "",
        zone            : testVariables.zone ?: "",
        kubeconfig      : "${testVariables.kubeconfigPath}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}",
        debug           : testVariables.debug
    ]

    if (testVariables.platform_provider.toLowerCase() == "rancher") {
        clusterCfg.rancherVersion = testVariables.rancher_version
        clusterCfg.certManagerVersion = testVariables.cert_manager_version
    }

    def createCluster = { testVariables.libraries[testVariables.platform_provider].createCluster(clusterCfg) }
    def clusterCleanup = { testVariables.libraries.tools.kubernetesCleanupCluster(clusterCfg.kubeconfig) }
    def shutdownCluster = { testVariables.libraries[testVariables.platform_provider].shutdownCluster(clusterCfg) }

    try {
        while (true) {
            def testId = claimNextSkippedTest(testVariables.tests, clusterSuffix)
            if (testId == null) {
                break
            }

            if (!clusterCreated) {
                clusterCreated = true
                createdClusters.add(clusterSuffix)
                addCluster(testVariables.clusters, clusterSuffix)

                echo "=========================[ Cleanup existing cluster ${getClusterFullName(testVariables.cluster_name, clusterSuffix)} ]========================="  
                try {
                    shutdownCluster.call()
                } catch (Exception e) {
                    echo "Cluster shutdown failed, maybe cluster does not exist or is already deleted: ${e.getMessage()}"
                }

                echo "=========================[ Creating cluster ${getClusterFullName(testVariables.cluster_name, clusterSuffix)} ]========================="
                createCluster.call()
            }

            runTest(
                testId: testId,
                clusterSuffix: clusterSuffix,
                testVariables: testVariables,
                retries: testVariables.retries ?: 1
            )
        }
    } finally {
        // Each cluster contains only suffix
        createdClusters.each { cluster ->
            try {
                clusterCleanup.call()
                shutdownCluster.call()
                removeCluster(testVariables.clusters, cluster)
            } catch (Exception e) {
                echo "Warning: Error cleaning up cluster ${cluster}: ${e.getMessage()}"
            }
        }
    }
}

Map buildParallelClusterStages(Map testVariables) {
    def parallelStages = [:]

    testVariables.clusters = testVariables.clusters ?: []
    testVariables.numClusters = testVariables.numClusters ?: 1
    testVariables.clusterCfg = testVariables.clusterCfg ?: [:]
    testVariables.kubeconfigPath = testVariables.kubeconfigPath ?: "/tmp"
    testVariables.retries = testVariables.retries ?: 1

    for (int i = 1; i <= testVariables.numClusters; i++) {
        def clusterSuffix = "cluster${i}"

        parallelStages[clusterSuffix] = {
            stage(clusterSuffix) {
                if (testVariables.jenkins_agent_label) {
                    node(testVariables.jenkins_agent_label) {
                        libraries.tools.unstashClonedGitFiles()
                        libraries.dependencies.prepareNode(
                            testVariables.libraries,
                            testVariables.test_executor_type,
                            testVariables.operator,
                            testVariables.platform_provider
                        )
                        clusterRunner(clusterSuffix, testVariables)
                    }
                } else {
                    clusterRunner(clusterSuffix, testVariables)
                }
            }
        }
    }

    return parallelStages
}

String formatTime(def time) {
    if (!time || time == "N/A") {
        return "N/A"
    }

    try {
        def totalSeconds = time as Double
        def hours = (totalSeconds / 3600) as Integer
        def minutes = ((totalSeconds % 3600) / 60) as Integer
        def seconds = (totalSeconds % 60) as Integer

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } catch (Exception e) {
        println("Error converting time: ${e.message}")
        return time.toString()
    }
}

void pushLogFile(String testName, Map config = [:]) {
    def sourceDir = config.sourceDir ?: 'source'
    def gitShortCommit = config.gitShortCommit ?: env.GIT_SHORT_COMMIT
    def logFilePath = "${sourceDir}/e2e-tests/logs/${testName}.log"
    def logFileName = "${testName}.log"

    echo "Push logfile ${logFileName} to S3!"
    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory-public/\$JOB_NAME/${gitShortCommit}
            if [ ! -f ${logFilePath} ]; then
                mkdir -p ${sourceDir}/e2e-tests/logs
                cat > ${logFilePath} <<EOF
Log file ${logFileName} was not found in Jenkins workspace.
The test may have timed out or terminated before the test runner created/flushed the log.
Build URL: ${BUILD_URL}
EOF
            fi
            aws s3 ls \$S3_PATH/${logFileName} || :
            aws s3 cp --content-type text/plain --quiet ${logFilePath} \$S3_PATH/${logFileName}
        """
    }
}

void pushReportFile(String reportHtml, String gitShortCommit) {
    echo "Push ${reportHtml} to S3!"
    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory-public/\$JOB_NAME/${gitShortCommit}
            aws s3 cp --content-type text/html --quiet ${reportHtml} \$S3_PATH/${reportHtml} || :
        """
    }
}

void normalizeReports(List tests, String sourceDir = 'source') {
    def reportsDir = "${sourceDir}/e2e-tests/reports"
    sh "mkdir -p ${reportsDir}"

    for (int i = 0; i < tests.size(); i++) {
        def testName = tests[i]["name"]
        def testResult = tests[i]["result"]
        def testTime = tests[i]["time"] ?: 0

        if (testResult == "skipped") {
            continue
        }

        def xmlFile = "${reportsDir}/${testName}.xml"
        def htmlFile = "${reportsDir}/${testName}.html"

        // Always collapse to a single testcase per test so python (multi-method) and
        // bash-wrapper tests are counted identically in JUnit. Detail stays in the HTML.
        def failures = testResult == "failure" ? 1 : 0
        def errors = testResult == "error" ? 1 : 0
        def resultElement = ""
        if (testResult == "failure") {
            resultElement = '<failure message="Jenkins reported test failure">Jenkins reported this test as failed. See the HTML report for details.</failure>'
        } else if (testResult == "error") {
            resultElement = '<error message="Jenkins reported test error">Jenkins reported this test as errored (infrastructure/timeout). See the HTML report for details.</error>'
        }

        writeFile file: xmlFile, text: """<?xml version="1.0" encoding="utf-8"?>
<testsuites name="pytest tests">
<testsuite name="psmdb-e2e" errors="${errors}" failures="${failures}" skipped="0" tests="1" time="${testTime}">
<testcase classname="" name="${testName}" time="${testTime}">
${resultElement}
</testcase>
</testsuite>
</testsuites>"""

        if (!fileExists(htmlFile)) {
            def formattedTime = formatTime(testTime)
            def resultCapitalized
            def logMessage
            if (testResult == "failure") {
                resultCapitalized = "Failed"
                logMessage = "Test did not produce a report"
            } else if (testResult == "error") {
                resultCapitalized = "Error"
                logMessage = "Test errored (infrastructure/timeout) and did not produce a report"
            } else {
                resultCapitalized = "Passed"
                logMessage = "Test marked as passed (from previous run)"
            }

            writeFile file: htmlFile, text: """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<title id="head-title">${testName}.html</title>
</head>
<body>
<div id="data-container" data-jsonblob='{"environment": {"Note": "Placeholder report generated because the test report was missing"}, "tests": {"${testName}": [{"extras": [], "result": "${resultCapitalized}", "testId": "${testName}", "duration": "${formattedTime}", "resultsTableRow": ["<td class=\\"col-result\\">${resultCapitalized}</td>", "<td>-</td>", "<td class=\\"col-testId\\">${testName}</td>", "<td class=\\"col-duration\\">${formattedTime}</td>", "<td>-</td>"], "log": "${logMessage}"}]}}'></div>
</body>
</html>"""
        }
    }
}

void formatReportDuration(String htmlFile) {
    def marker = ' tests ran in '
    def suffix = ' seconds'
    def html = readFile(htmlFile)

    def valueStart = html.indexOf(marker)
    if (valueStart < 0) {
        return
    }
    valueStart += marker.length()

    def valueEnd = html.indexOf(suffix, valueStart)
    if (valueEnd < 0) {
        return
    }

    def formatted = formatTime(html.substring(valueStart, valueEnd))
    writeFile file: htmlFile, text: html.substring(0, valueStart) + formatted + html.substring(valueEnd + suffix.length())
}

void writePipelineParameters(String pipelineParameters) {
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters
    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

void publishPytestReports(Map config) {
    def tests = config.tests ?: []
    def sourceDir = config.sourceDir ?: 'source'
    def reportHtml = config.reportHtml ?: 'e2e-test-report.html'
    def reportXml = config.reportXml ?: 'e2e-test-report.xml'
    def gitShortCommit = config.gitShortCommit ?: env.GIT_SHORT_COMMIT
    def gitBranch = config.gitBranch ?: env.GIT_BRANCH
    def title = config.title ?: "PSMDB e2e tests - ${gitBranch} (${gitShortCommit})"
    def pushToS3 = config.containsKey('pushToS3') ? config.pushToS3 : true

    echo "=========================[ Publishing pytest HTML/JUnit reports ]========================="

    def startedTests = tests.findAll { test ->
        def result = test.containsKey("result") ? test.result : test["result"]
        result && result != "skipped"
    }

    if (!startedTests) {
        echo "No started tests; skipping pytest report merge."
        return
    }

    try {
        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            cd ${sourceDir}
            uv run pytest_html_merger -i e2e-tests/reports -o "\$WORKSPACE/${reportHtml}" -t "${title}"
            uv run junitparser merge --glob 'e2e-tests/reports/*.xml' "\$WORKSPACE/${reportXml}"
        """

        if (fileExists(reportHtml)) {
            formatReportDuration(reportHtml)
        }

        archiveArtifacts artifacts: "${reportXml}, ${reportHtml}", allowEmptyArchive: true

        if (pushToS3 && gitShortCommit && fileExists(reportHtml)) {
            pushReportFile(reportHtml, gitShortCommit)

            def reportUrl = "https://percona-jenkins-artifactory-public.s3.amazonaws.com/${env.JOB_NAME}/${gitShortCommit}/${reportHtml}"
            def reportLink = "<a href=\"${reportUrl}\">Test report</a>"
            currentBuild.description = currentBuild.description ? "${currentBuild.description} | ${reportLink}" : reportLink
        }
    } catch (err) {
        echo "Warning: optional HTML report publish failed: ${err}"
    }
}

void makeReport(List tests, Map testVariables) {
    echo "=========================[ Generating Parameters Report ]========================="

    def pipelineParameters = "testsuite name=${testVariables.job_name}\n"
    testVariables.images.each { key, value ->
        pipelineParameters += "${key}=${value ?: 'e2e_defaults'}\n"
    }

    pipelineParameters += "PLATFORM_VERSION=${testVariables.platform_version ?: 'e2e_defaults'}\n"
    pipelineParameters += "PLATFORM_CHANNEL=${testVariables.platform_channel ?: 'e2e_defaults'}\n"
    pipelineParameters += "PLATFORM_ARCH=${testVariables.platform_arch ?: 'e2e_defaults'}\n"
    pipelineParameters += "CLUSTER_WIDE=${testVariables.cluster_wide ?: 'e2e_defaults'}\n"

    writePipelineParameters(pipelineParameters)

    publishPytestReports(
        tests         : tests,
        gitShortCommit: testVariables.git_short_commit,
        gitBranch     : testVariables.git_branch,
        title         : "PSMDB e2e tests - ${testVariables.git_branch ?: env.GIT_BRANCH} (${testVariables.git_short_commit})"
    )
}

void makeReportJUnit(List tests, Map testVariables) {
    echo "=========================[ Generating Test Report ]========================="
    def testsReport = "<testsuite name=\"${testVariables.job_name}\">\n"
    tests.each { test ->
        testsReport += '<testcase name="' + test.name + '" time="' + test.time + '"><' + test.result + '/></testcase>\n'
    }
    testsReport += '</testsuite>\n'

    echo "=========================[ Generating Parameters Report ]========================="
    def pipelineParameters = "testsuite name=${testVariables.job_name}\n"
    testVariables.images.each { key, value ->
        pipelineParameters += "${key}=${value ?: 'e2e_defaults'}\n"
    }
    pipelineParameters += "PLATFORM_VER=${testVariables.platform_version}"
    if (testVariables.platform == "gke") {
        pipelineParameters += "\nGKE_RELEASE_CHANNEL=${testVariables.platform_channel}"
    }

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

return this
