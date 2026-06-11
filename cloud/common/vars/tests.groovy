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

String artifactFileName(Map cfg) {
    return "${cfg.gitBranch}-${cfg.gitShortCommit}-${cfg.testName}-${cfg.platformVer}-${cfg.dbTag}-CW_${cfg.clusterWide}-${cfg.paramsHash}"
}

String getClusterFullName(String clusterName, String clusterSuffix) {
    return "${clusterName}-${clusterSuffix}"
}

String getReleaseParamName(String imageName, String pillarVersion, String operator) {
    def versionedImages = [
        "psmdb-operator": [
            IMAGE_MONGOD: "IMAGE_MONGOD${pillarVersion}"
        ],
        "ps-operator": [
            IMAGE_MYSQL: "IMAGE_MYSQL${pillarVersion}"
        ],
        "pxc-operator": [
            IMAGE_PXC: "IMAGE_PXC${pillarVersion}"
        ],
        "pg-operator": [
            IMAGE_PGBOUNCER: "IMAGE_PGBOUNCER${pillarVersion}",
            IMAGE_BACKREST : "IMAGE_BACKREST${pillarVersion}"
        ]
    ]

    return versionedImages[operator?.toLowerCase()]?.get(imageName) ?: imageName
}

List load(String testList, String testSuite) {
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
            time   : "0"
        ]
    }

    echo "Loaded ${tests.size()} tests:"
    echo tests.collect { " - ${it.name}" }.join('\n')

    return tests
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
            def file = artifactFileName(buildArtifactParams(testVariables, test.name))
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

Map buildArtifactParams(Map testVariables, String testName) {
    return [
        gitBranch     : testVariables.git_branch,
        gitShortCommit: testVariables.git_short_commit,
        testName      : testName,
        platformVersion : testVariables.platform_version,
        dbTag         : testVariables.db_tag,
        clusterWide   : testVariables.cluster_wide,
        paramsHash    : testVariables.params_hash
    ]
}

String buildParamsHash(Map testVariables) {
    def hashValues = [
        testVariables.git_branch,
        testVariables.git_short_commit,
        testVariables.platform_version,
        testVariables.cluster_wide,
        testVariables.platform_arch,
        testVariables.platform_channel,
        testVariables.pillar_version
    ].findAll { it != null }

    testVariables.images.values().findAll { it }.each { imageValue ->
        hashValues << imageValue
    }

    return sh(
        script: "echo '${hashValues.join('-')}' | md5sum | cut -d' ' -f1",
        returnStdout: true
    ).trim()
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

void loadCloudSecret(String operator) {
    withCredentials([file(
        credentialsId: "cloud-secret-file-${operator}",
        variable: 'CLOUD_SECRET_FILE'
    )]) {
        sh '''
            cp "$CLOUD_SECRET_FILE" source/e2e-tests/conf/cloud-secret.yml
        '''
    }
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

Map prepareVersions(Map testVariables) {
    def libraries = testVariables.libraries

    if ("${testVariables.pillar_version}" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="
        testVariables.platform_channel = "stable"
        echo "Forcing channel=stable, because it's a release run!"

        testVariables.images = resolveImages(testVariables)

        if (testVariables.platform_type == "rancher") {
            testVariables.rancher_version = getReleaseVersionsParam(
                testVariables.release_versions,
                "RANCHER_VERSION",
                testVariables.rancher_version ?: "latest"
            )
            testVariables.cert_manager_version = getReleaseVersionsParam(
                testVariables.release_versions,
                "CERT_MANAGER_VERSION",
                testVariables.cert_manager_version ?: "latest"
            )
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    if (testVariables.platform_version?.toLowerCase() in ["min", "max"]) {
        testVariables.platform_version = getReleaseVersionsParam(
            testVariables.release_versions,
            "PLATFORM_VER",
            "RANCHER_${testVariables.platform_version.toUpperCase()}"
        )
    }

    if (testVariables.platform_version == "latest" && testVariables.platform_channel && testVariables.platform_type) {
        testVariables.platform_version = libraries[testVariables.platform_type].getLatestVersion(
            testVariables.platform_channel
        )
    }

    if (testVariables.platform_arch && testVariables.platform_type) {
        testVariables.machine_type = libraries[testVariables.platform_type].getMachineType(
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

String getExportedVariablesForTests(Map testVariables, String clusterSuffix) {
    def exports = []

    exports << "export KUBECONFIG=${testVariables.kubeconfigPath ?: '/tmp'}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}"
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

    testVariables.extra_envs?.each { key, value ->
        exports << "export ${key}='${value ?: ""}'"
    }

    return exports.join("\n")
}

String getTestCommand(Map testVariables, String testName) {
    if (testVariables.test_executor_type == "kuttl") {
        return "kubectl kuttl test --config e2e-tests/kuttl.yaml --test '^${testName}\$'"
    }

    return "e2e-tests/${testName}/run"
}

void runTest(Map testConfig) {
    def retryCount = 0
    def test = testConfig.tests[testConfig.testId]
    def testName = test.name
    def testVariables = testConfig.testVariables

    waitUntil {
        def timeStart = System.currentTimeMillis()

        try {
            echo "The ${testName} test was started on cluster ${getClusterFullName(testVariables.cluster_name, test.cluster)}!"
            test.result = "failure"


            timeout(time: 90, unit: 'MINUTES') {
                def exports = getExportedVariablesForTests(testVariables, test.cluster)
                def command = getTestCommand(testVariables, testName)

                sh """
                    cd source

                    ${exports}

                    ${command}
                """
            }

            pushArtifactFile(
                artifactFileName(buildArtifactParams(testVariables, testName)),
                testVariables.git_short_commit
            )

            test.result = "passed"
            return true

        } catch (exc) {
            if (retryCount >= (testConfig.retries ?: 1)) {
                currentBuild.result = 'FAILURE'
                return true
            }

            retryCount++
            return false

        } finally {
            test.time = (System.currentTimeMillis() - timeStart) / 1000
            echo "The ${testName} test was finished!"
        }
    }
}

void clusterRunner(String clusterSuffix, Map testVariables) {
    def createdClusters = []
    def clusterCreated = false
    def clusterCfg = [
        clusterName: testVariables.cluster_name,
        clusterSuffix: clusterSuffix,
        platformType: testVariables.platform_type,
        platformVersion : testVariables.platform_version,
        platformChannel : testVariables.platform_channel,
        platformArch : testVariables.platform_arch,
        machineType : testVariables.machine_type,
        kubeconfig  : "${testVariables.kubeconfigPath}/${getClusterFullName(testVariables.cluster_name, clusterSuffix)}",
        debug       : testVariables.debug
    ]

    if (testVariables.platform_type == "rancher") {
        clusterCfg.rancherVersion = testVariables.rancher_version
        clusterCfg.certManagerVersion = testVariables.cert_manager_version
    }

    def createCluster = { testVariables.libraries[testVariables.platform_type].createCluster(clusterCfg) }
    def clusterCleanup = { testVariables.libraries.tools.kubernetesCleanupCluster(clusterCfg.kubeconfig) }
    def shutdownCluster = { testVariables.libraries[testVariables.platform_type].shutdownCluster(clusterCfg) }

    try {
        testVariables.tests.eachWithIndex { test, index ->
            if (test.result != "skipped") {
                return
            }

            test.result = "failure"
            test.cluster = clusterSuffix

            if (!clusterCreated) {
                clusterCreated = true
                createdClusters.add(clusterSuffix)
                testVariables.clusters.add(clusterSuffix)

                echo "=========================[ Creating cluster ${getClusterFullName(testVariables.cluster_name, clusterSuffix)} ]========================="
                createCluster.call()
            }

            runTest(
                tests: testVariables.tests,
                testId: index,
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
                testVariables.clusters.remove(cluster)
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

    addSummary(
        icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

return this
