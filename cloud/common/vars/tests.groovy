void loadCloudSecret(String operator) {
    def credentialsIds = [
        "pg"   : "cloud-secret-file",
        "ps"   : "cloud-secret-file-ps",
        "psmdb": "cloud-secret-file-psmdb",
        "pxc"  : "cloud-secret-file"
    ]

    withCredentials([file(
        credentialsId: credentialsIds[operator] ?: "cloud-secret-file-${operator}",
        variable: 'CLOUD_SECRET_FILE'
    )]) {
        sh '''
            cp "$CLOUD_SECRET_FILE" source/e2e-tests/conf/cloud-secret.yml
            chmod 600 source/e2e-tests/conf/cloud-secret.yml
        '''
    }
}

void loadCloudMinioSecret() {
    withCredentials([file(
        credentialsId: "cloud-minio-secret-file",
        variable: 'CLOUD_MINIO_SECRET_FILE'
    )]) {
        sh '''
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

String getReleaseParamName(String imageName, String pillarVersion, String operator) {
    def pillarVersionKey = "${pillarVersion}".replace("-postgis", "")
    def postgresImageKey = "${pillarVersion}".endsWith("-postgis") ?
        "IMAGE_POSTGIS${pillarVersionKey}" :
        "IMAGE_POSTGRESQL${pillarVersionKey}"

    def versionedImages = [
        "psmdb-operator": [
            IMAGE_MONGOD: "IMAGE_MONGOD${pillarVersion}"
        ],
        "ps-operator": [
            IMAGE_MYSQL : "IMAGE_MYSQL${pillarVersion}",
            IMAGE_BACKUP: "IMAGE_BACKUP${pillarVersion}",
            IMAGE_ROUTER: "IMAGE_ROUTER${pillarVersion}"
        ],
        "pxc-operator": [
            IMAGE_PXC   : "IMAGE_PXC${pillarVersion}",
            IMAGE_BACKUP: "IMAGE_BACKUP${pillarVersion}"
        ],
        "pg-operator": [
            IMAGE_POSTGRESQL: postgresImageKey,
            IMAGE_PGBOUNCER : "IMAGE_PGBOUNCER${pillarVersionKey}",
            IMAGE_BACKREST  : "IMAGE_BACKREST${pillarVersionKey}"
        ]
    ]

    return versionedImages[operator?.toLowerCase()]?.get(imageName) ?: imageName
}

Map prepareVersions(Map testVariables) {
    def libraries = testVariables.libraries
    if ("${testVariables.pillar_version}" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="
        testVariables.platform_channel = "stable"
        echo "Forcing channel=stable, because it's a release run!"

        testVariables.images = resolveImages(testVariables)

        switch (testVariables.platform_provider?.toLowerCase()) {
            case "rancher":
                ["rancher_version": "RANCHER", "cert_manager_version": "CERT_MANAGER"].each { field, key ->
                    if (!testVariables[field] || testVariables[field] == "latest") {
                        testVariables[field] = getReleaseVersionsParam(testVariables.release_versions, key)
                    }
                }
                break

            case "gcloud":
            case "azure":
            case "redhat":
            case "digitalocean":
            case "minikube":
                break

            default:
                error("Unsupported platform_provider: ${testVariables.platform_provider}")
        }

        def platformVersionAlias = testVariables.platform_version?.toLowerCase()

        if (testVariables.platform_provider?.toLowerCase() == "minikube" && platformVersionAlias == "min") {
            error("Unsupported Minikube platform_version=${testVariables.platform_version}. Use max, rel, or latest.")
        }

        if (platformVersionAlias in ["min", "max", "rel"]) {
            def platformPrefix = [
                "gcloud"      : "GKE",
                "azure"       : "AKS",
                "redhat"      : "OPENSHIFT",
                "digitalocean": "DOKS",
                "rancher"     : "RKE2",
                "minikube"    : "MINIKUBE",
            ][testVariables.platform_provider?.toLowerCase()]

            def platformKey = (testVariables.platform_provider?.toLowerCase() == "minikube" && platformVersionAlias in ["max", "rel"]) ?
                "REL" :
                testVariables.platform_version.toUpperCase()

            testVariables.platform_version = getReleaseVersionsParam(
                testVariables.release_versions,
                "${platformPrefix}_${platformKey}"
            )

        }

    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    if (testVariables.platform_version == "latest" && testVariables.platform_channel && testVariables.platform_provider) {
        testVariables.platform_version = libraries[testVariables.platform_provider].getLatestPlatformVersion(
            testVariables.platform_channel
        )
    } else if (testVariables.platform_provider) {
        testVariables.platform_version = libraries[testVariables.platform_provider].getPlatformVersion(
            testVariables.platform_version
        )
    }

    if (testVariables.platform_arch && testVariables.platform_provider && libraries[testVariables.platform_provider].metaClass.respondsTo(libraries[testVariables.platform_provider], "getMachineType", String)) {
        testVariables.machine_type = libraries[testVariables.platform_provider].getMachineType(
            testVariables.platform_arch
        )
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

String artifactFileName(Map testVariables, String testName) {
    def branch          = testVariables.git_branch
    def commit          = testVariables.git_short_commit
    def platformVersion = testVariables.platform_version
    def dbTag           = testVariables.db_tag
    def clusterWide     = testVariables.cluster_wide
    def paramsHash      = testVariables.params_hash

    return "${branch}-${commit}-${testName}-${platformVersion}-${dbTag}-CW_${clusterWide}-${paramsHash}"
}

String buildParamsHash(Map testVariables) {
    def hashValues = [
        testVariables.git_branch,
        testVariables.git_short_commit,
        testVariables.platform_version,
        testVariables.cluster_wide,
        testVariables.platform_arch,
        testVariables.platform_channel,
        testVariables.pillar_version,
        testVariables.db_tag
    ].findAll { it != null && it.toString().trim() != '' && it.toString() != 'null' }

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
            testVariables.operator
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

    exports << "export KUBECONFIG=${testVariables.kubeconfigPath ?: '/tmp'}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}"
    exports << "[[ '${testVariables.debug_tests}' == 'YES' ]] && export DEBUG_TESTS=1"
    exports << "[[ '${testVariables.cluster_wide}' == 'YES' ]] && export OPERATOR_NS='${testVariables.operator}'"
    exports << """
        [[ '${testVariables.images.IMAGE_OPERATOR}' ]] \
            && export IMAGE='${testVariables.images.IMAGE_OPERATOR}' \
            || export IMAGE='${testVariables.operator_repo}:${testVariables.git_branch}'
    """.stripIndent().trim()

    testVariables.images.each { imageName, imageValue ->
        exports << "export ${imageName}='${imageValue ?: ""}'"
    }

    if (testVariables.images.IMAGE_POSTGRESQL) {
        exports << "export PG_VER=\$(echo \$IMAGE_POSTGRESQL | sed -E 's/.*:(.*ppg)?([0-9]+).*/\\2/')"
    }

    if (testVariables.test_executor_type == "kuttl") {
        exports << 'export PATH="${KREW_ROOT:-$HOME/.krew}/bin:$PATH"'
    }

    testVariables.extra_envs?.each { key, value ->
        exports << "export ${key}='${value ?: ""}'"
    }

    return exports.join("\n")
}

String defineTestCommand(Map testVariables, String testName) {
    if (testVariables.test_executor_type == "kuttl") {
        return "kubectl kuttl test --config e2e-tests/kuttl.yaml --test '^${testName}\$'"
    }

    return "e2e-tests/${testName}/run"
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
    def retryCount = 0
    def testVariables = testConfig.testVariables
    def testId = testConfig.testId
    def testName = testVariables.tests[testId].name
    def clusterSuffix = testConfig.clusterSuffix

    waitUntil {
        def timeStart = System.currentTimeMillis()

        try {
            echo "The ${testName} test was started on cluster ${getClusterFullName(testVariables.cluster_name, clusterSuffix)}!"
            updateTestResult(testVariables.tests, testId, "failure")


            timeout(time: 90, unit: 'MINUTES') {
                def exports = getExportedVariablesForTests(testVariables, clusterSuffix)
                def command = defineTestCommand(testVariables, testName)

                sh """
                    cd source

                    ${exports}

                    ${command}
                """
            }

            pushArtifactFile(
                artifactFileName(testVariables, testName),
                testVariables.git_short_commit
            )

            updateTestResult(testVariables.tests, testId, "passed")
            return true

        } catch (exc) {
            try {
                testVariables.libraries.tools.kubernetesArchiveClusterLogs(
                    "${testVariables.kubeconfigPath ?: '/tmp'}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}",
                    testName,
                    testVariables.operator
                )
                testVariables.libraries.tools.kubernetesCleanupFailedTestNamespaces(
                    "${testVariables.kubeconfigPath ?: '/tmp'}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}",
                    testName,
                    testVariables.operator
                )
            } catch (cleanupErr) {
                echo "Warning: failed to cleanup namespaces for ${testName}: ${cleanupErr}"
            }

            if (retryCount >= (testConfig.retries ?: 1)) {
                currentBuild.result = 'FAILURE'
                return true
            }

            retryCount++
            return false

        } finally {
            updateTestTime(testVariables.tests, testId, elapsedSeconds(System.currentTimeMillis() - timeStart))
            echo "The ${testName} test was finished!"
        }
    }
}

void clusterRunner(String clusterSuffix, Map testVariables) {
    def createdClusters = []
    def clusterCreated = false
    def clusterCfg = [
        clusterName     : testVariables.cluster_name,
        clusterSuffix   : clusterSuffix,
        platformProvider: testVariables.platform_provider,
        platformVersion : testVariables.platform_version,
        platformChannel : testVariables.platform_channel,
        platformArch    : testVariables.platform_arch,
        machineType     : testVariables.machine_type,
        workerCountMin  : testVariables.worker_min_count ?: 4,
        workerCountMax  : testVariables.worker_max_count ?: 6,
        sourceRanges    : testVariables.source_ranges ?: "0.0.0.0/0",
        region          : testVariables.region ?: "",
        zone            : testVariables.zone ?: "",
        kubeconfig      : "${testVariables.kubeconfigPath}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}",
        debug           : testVariables.debug
    ]

    if (testVariables.platform_provider == "rancher") {
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

Map getParallelStages(Map testVariables) {
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
                clusterRunner(clusterSuffix, testVariables)
            }
        }
    }

    return parallelStages
}

void makeReport(List tests, Map testVariables) {
    echo "=========================[ Generating Test Report ]========================="
    tests = tests ?: []

    def testsReport = "<testsuite name=\"${testVariables.job_name}\">\n"
    tests.each { test ->
        testsReport += "<testcase name=\"${test.name}\" time=\"${test.time}\"><${test.result}/></testcase>\n"
    }

    testsReport += "</testsuite>\n"

    echo "=========================[ Generating Parameters Report ]========================="

    def pipelineParameters = "testsuite name=${testVariables.job_name}\n"
    testVariables.images.each { key, value ->
        pipelineParameters += "${key}=${value ?: 'e2e_defaults'}\n"
    }

    pipelineParameters += "platform_version=${testVariables.platform_version ?: 'e2e_defaults'}\n"
    pipelineParameters += "platform_channel=${testVariables.platform_channel ?: 'e2e_defaults'}\n"
    pipelineParameters += "platform_arch=${testVariables.platform_arch ?: 'e2e_defaults'}\n"
    pipelineParameters += "cluster_wide=${testVariables.cluster_wide ?: 'e2e_defaults'}\n"

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: "PipelineParameters.txt", text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

return this
