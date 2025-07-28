def call(Map config) {
    // Required parameters
    def required = ['clusterName', 'openshiftVersion', 'awsRegion', 'pullSecret',
                    'sshPublicKey', 's3Bucket', 'workDir']

    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    // Set defaults
    def params = [
        baseDomain: 'cd.percona.com',
        masterType: 'm5.xlarge',
        workerType: 'm5.large',
        workerCount: 3,
        deleteAfterHours: '8',
        teamName: 'cloud',
        productTag: 'openshift',
        useSpotInstances: false,
        testMode: false,
        deployPMM: true,
        pmmVersion: '3.3.0'
    ] + config

    echo "Creating OpenShift cluster: ${params.clusterName}"

    try {
        // Step 1: Validate parameters
        validateOpenShiftParams(params)

        // Step 2: Install OpenShift tools
        def resolvedVersion = installOpenShiftTools([
            openshiftVersion: params.openshiftVersion
        ])
        params.openshiftVersion = resolvedVersion

        // Step 3: Prepare cluster directory
        def clusterDir = "${params.workDir}/${params.clusterName}"
        sh "mkdir -p ${clusterDir}"

        // Step 4: Generate install config
        def installConfig = generateOpenShiftInstallConfig([
            baseDomain: params.baseDomain,
            workerType: params.workerType,
            workerCount: params.workerCount,
            masterType: params.masterType,
            clusterName: params.clusterName,
            awsRegion: params.awsRegion,
            deleteAfterHours: params.deleteAfterHours,
            teamName: params.teamName,
            productTag: params.productTag,
            buildUser: params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins',
            timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
            pullSecret: params.pullSecret,
            sshPublicKey: params.sshPublicKey
        ], params.useSpotInstances, params.spotMaxPrice)

        // Save install config
        writeFile file: "${clusterDir}/install-config.yaml", text: installConfig

        // Backup install config (it gets consumed during install)
        writeFile file: "${clusterDir}/install-config.yaml.backup", text: installConfig

        // Step 5: Create cluster metadata
        def metadata = createClusterMetadata([
            clusterName: params.clusterName,
            openshiftVersion: params.openshiftVersion,
            awsRegion: params.awsRegion,
            masterType: params.masterType,
            workerType: params.workerType,
            workerCount: params.workerCount,
            buildUser: params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins',
            buildNumber: env.BUILD_NUMBER ?: '1',
            jobName: env.JOB_NAME ?: 'openshift-cluster-create',
            testMode: params.testMode,
            baseDomain: params.baseDomain,
            deleteAfterHours: params.deleteAfterHours,
            teamName: params.teamName,
            productTag: params.productTag,
            useSpotInstances: params.useSpotInstances,
            spotMaxPrice: params.spotMaxPrice,
            pmmDeployed: false
        ], "${clusterDir}/metadata.json")

        if (params.testMode) {
            echo 'TEST MODE: Skipping actual cluster creation'
            echo "Install config and metadata have been generated in: ${clusterDir}"
            return [
                clusterName: params.clusterName,
                clusterDir: clusterDir,
                testMode: true,
                metadata: metadata
            ]
        }

        // Step 6: Create the cluster
        echo 'Creating OpenShift cluster (this will take 30-45 minutes)...'
        sh """
            cd ${clusterDir}
            openshift-install create cluster --log-level=info
        """

        // Step 7: Save cluster state to S3
        echo 'Backing up cluster state to S3...'
        sh """
            cd ${params.workDir}
            tar -czf ${params.clusterName}-state.tar.gz ${params.clusterName}/
        """

        def s3Path = manageClusterStateS3('upload', [
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            localPath: "${params.workDir}/${params.clusterName}-state.tar.gz",
            metadata: [
                'openshift-version': params.openshiftVersion,
                'created-by': params.buildUser ?: 'jenkins'
            ]
        ])

        // Save metadata to S3
        manageClusterStateS3.saveClusterMetadata([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion
        ], metadata)

        // Step 8: Get cluster info
        def clusterInfo = getClusterInfo(clusterDir)

        // Step 9: Deploy PMM if requested
        if (params.deployPMM) {
            echo 'Deploying PMM to the cluster...'

            // Set KUBECONFIG for PMM deployment
            env.KUBECONFIG = "${clusterDir}/auth/kubeconfig"

            def pmmInfo = deployPMMToOpenShift([
                pmmVersion: params.pmmVersion,
                namespace: 'pmm-monitoring',
                adminPassword: params.pmmAdminPassword ?: 'admin'
            ])

            // Update metadata with PMM info
            def updatedMetadata = createClusterMetadata([
                clusterName: params.clusterName,
                openshiftVersion: params.openshiftVersion,
                awsRegion: params.awsRegion,
                masterType: params.masterType,
                workerType: params.workerType,
                workerCount: params.workerCount,
                pmmDeployed: true,
                pmmVersion: params.pmmVersion,
                pmmUrl: pmmInfo.url,
                additionalMetadata: [
                    pmm_namespace: pmmInfo.namespace
                ]
            ])

            manageClusterStateS3.saveClusterMetadata([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion
            ], updatedMetadata)

            clusterInfo.pmm = pmmInfo
        }

        // Step 10: Display cluster information
        displayClusterInfo(clusterInfo)

        return clusterInfo
    } catch (Exception e) {
        error "Failed to create OpenShift cluster: ${e.message}"
    } finally {
        // Clean up local state file
        sh "rm -f ${params.workDir}/${params.clusterName}-state.tar.gz || true"
    }
}

def getClusterInfo(String clusterDir) {
    def info = [:]

    // Get API URL
    info.apiUrl = sh(
        script: "grep 'server:' ${clusterDir}/auth/kubeconfig | head -1 | awk '{print \$2}'",
        returnStdout: true
    ).trim()

    // Get console URL
    def consoleRoute = sh(
        script: """
            export KUBECONFIG=${clusterDir}/auth/kubeconfig
            oc get route -n openshift-console console -o jsonpath='{.spec.host}' 2>/dev/null || echo ''
        """,
        returnStdout: true
    ).trim()

    if (consoleRoute) {
        info.consoleUrl = "https://${consoleRoute}"
    }

    // Get kubeadmin password
    def kubeadminFile = "${clusterDir}/auth/kubeadmin-password"
    if (fileExists(kubeadminFile)) {
        info.kubeadminPassword = readFile(kubeadminFile).trim()
    }

    info.kubeconfig = "${clusterDir}/auth/kubeconfig"
    info.clusterDir = clusterDir

    return info
}

def displayClusterInfo(Map info) {
    echo """
========================================
OpenShift Cluster Created Successfully!
========================================
API URL: ${info.apiUrl}
Console URL: ${info.consoleUrl ?: 'Not available'}
Username: kubeadmin
Password: ${info.kubeadminPassword ?: 'Check auth/kubeadmin-password'}
Kubeconfig: ${info.kubeconfig}

To access the cluster:
export KUBECONFIG=${info.kubeconfig}
oc login -u kubeadmin -p ${info.kubeadminPassword ?: '<password>'}

${info.pmm ? """
PMM Access:
URL: ${info.pmm.url}
Username: ${info.pmm.username}
Password: ${info.pmm.password}
""" : ''}
========================================
"""
}
