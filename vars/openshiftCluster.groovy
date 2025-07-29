// Main OpenShift cluster operations library
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

// Main entry point for creating clusters
def create(Map config) {
    def required = ['clusterName', 'openshiftVersion', 'awsRegion', 'pullSecret',
                    'sshPublicKey', 's3Bucket', 'workDir']

    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

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
        validateParams(params)

        // Step 2: Install OpenShift tools
        def resolvedVersion = openshiftTools.install([
            openshiftVersion: params.openshiftVersion
        ])
        params.openshiftVersion = resolvedVersion

        // Step 3: Prepare cluster directory
        def clusterDir = "${params.workDir}/${params.clusterName}"
        sh "mkdir -p ${clusterDir}"

        // Step 4: Generate install config
        def installConfig = generateInstallConfig(params)
        writeFile file: "${clusterDir}/install-config.yaml", text: installConfig
        writeFile file: "${clusterDir}/install-config.yaml.backup", text: installConfig

        // Step 5: Create cluster metadata
        def metadata = createMetadata(params, clusterDir)

        if (params.testMode) {
            echo 'TEST MODE: Skipping actual cluster creation'
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
        openshiftS3.uploadState([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            workDir: params.workDir,
            metadata: metadata
        ])

        // Step 8: Get cluster info
        def clusterInfo = getClusterInfo(clusterDir)

        // Step 9: Deploy PMM if requested
        if (params.deployPMM) {
            env.KUBECONFIG = "${clusterDir}/auth/kubeconfig"
            def pmmInfo = deployPMM(params)

            metadata.pmmDeployed = true
            metadata.pmmVersion = params.pmmVersion
            metadata.pmmUrl = pmmInfo.url
            metadata.pmm_namespace = pmmInfo.namespace

            openshiftS3.saveMetadata([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion
            ], metadata)

            clusterInfo.pmm = pmmInfo
        }

        displayClusterInfo(clusterInfo)
        return clusterInfo
    } catch (Exception e) {
        error "Failed to create OpenShift cluster: ${e.message}"
    } finally {
        sh "rm -f ${params.workDir}/${params.clusterName}-state.tar.gz || true"
    }
}

// Destroy cluster
def destroy(Map config) {
    def required = ['clusterName', 's3Bucket', 'awsRegion', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    def params = [
        force: false,
        skipS3Cleanup: false,
        testMode: false
    ] + config

    echo "Destroying OpenShift cluster: ${params.clusterName}"

    try {
        // Get metadata and cluster state from S3
        def metadata = openshiftS3.getMetadata([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion
        ])

        if (!metadata && !params.force) {
            error "No metadata found for cluster ${params.clusterName}. Use force=true to proceed anyway."
        }

        def clusterDir = "${params.workDir}/${params.clusterName}"
        sh "mkdir -p ${clusterDir}"

        // Download cluster state from S3
        def stateExists = openshiftS3.downloadState([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            workDir: params.workDir
        ])

        if (!stateExists && !params.force) {
            error "No state found for cluster ${params.clusterName}. Use force=true to cleanup S3 anyway."
        }

        // Install OpenShift tools
        if (metadata?.openshiftVersion) {
            openshiftTools.install([
                openshiftVersion: metadata.openshiftVersion
            ])
        }

        if (params.testMode) {
            echo 'TEST MODE: Skipping actual cluster destruction'
            return [
                clusterName: params.clusterName,
                testMode: true,
                message: 'Cluster would be destroyed'
            ]
        }

        // Destroy the cluster
        if (stateExists) {
            echo 'Destroying OpenShift cluster...'
            sh """
                cd ${clusterDir}
                if [ -f auth/kubeconfig ]; then
                    export KUBECONFIG=\$(pwd)/auth/kubeconfig
                    echo "Using kubeconfig: \$KUBECONFIG"
                fi
                openshift-install destroy cluster --log-level=info || true
            """
        }

        // Clean up S3
        if (!params.skipS3Cleanup) {
            openshiftS3.cleanup([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion
            ])
        }

        return [
            clusterName: params.clusterName,
            destroyed: true,
            s3Cleaned: !params.skipS3Cleanup
        ]
    } catch (Exception e) {
        error "Failed to destroy OpenShift cluster: ${e.message}"
    } finally {
        sh "rm -rf ${params.workDir}/${params.clusterName} || true"
    }
}

// List clusters
def list(Map config = [:]) {
    def params = [
        region: env.OPENSHIFT_AWS_REGION ?: 'us-east-2',
        format: 'table'
    ] + config

    try {
        def clusters = []
        def s3Output = sh(
            script: """
                aws s3api list-objects-v2 \
                    --bucket percona-jenkins-artifactory \
                    --prefix openshift-state/ \
                    --region ${params.region} \
                    --query 'Contents[?ends_with(Key, `metadata.json`)].Key' \
                    --output json
            """,
            returnStdout: true
        ).trim()

        if (s3Output && s3Output != 'null') {
            def s3Files = new JsonSlurper().parseText(s3Output)

            s3Files.each { key ->
                def clusterName = key.split('/')[1]
                def metadata = openshiftS3.getMetadata([
                    bucket: 'percona-jenkins-artifactory',
                    clusterName: clusterName,
                    region: params.region
                ])

                if (metadata) {
                    def uptime = calculateUptime(metadata.created_date)
                    clusters << [
                        name: clusterName,
                        version: metadata.openshift_version ?: 'Unknown',
                        region: metadata.aws_region ?: params.region,
                        created_by: metadata.created_by ?: 'Unknown',
                        created_at: metadata.created_date ?: 'Unknown',
                        uptime: uptime,
                        pmm_deployed: metadata.pmm_deployed ? 'Yes' : 'No',
                        pmm_version: metadata.pmm_version ?: 'N/A'
                    ]
                }
            }
        }

        if (params.format == 'json') {
            return clusters
        } else {
            displayClustersTable(clusters)
            return clusters
        }
    } catch (Exception e) {
        error "Failed to list OpenShift clusters: ${e.message}"
    }
}

// Helper functions
def validateParams(Map params) {
    if (!params.clusterName.matches(/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/)) {
        error 'Invalid cluster name. Must contain only lowercase letters, numbers, and hyphens.'
    }

    if (params.clusterName.length() > 20) {
        error 'Cluster name too long. Maximum 20 characters.'
    }

    if (!params.openshiftVersion.matches(/^(latest|stable|fast|candidate|eus-)?[0-9]+\.[0-9]+(\.[0-9]+)?$/)) {
        error 'Invalid OpenShift version format'
    }

    def validRegions = ['us-east-1', 'us-east-2', 'us-west-1', 'us-west-2',
                        'eu-west-1', 'eu-west-2', 'eu-central-1']
    if (!validRegions.contains(params.awsRegion)) {
        error "Invalid AWS region. Supported regions: ${validRegions.join(', ')}"
    }
}

def generateInstallConfig(Map params) {
    def config = [
        apiVersion: 'v1',
        baseDomain: params.baseDomain,
        compute: [[
            architecture: 'amd64',
            hyperthreading: 'Enabled',
            name: 'worker',
            platform: [
                aws: [
                    type: params.workerType
                ]
            ],
            replicas: params.workerCount
        ]],
        controlPlane: [
            architecture: 'amd64',
            hyperthreading: 'Enabled',
            name: 'master',
            platform: [
                aws: [
                    type: params.masterType
                ]
            ],
            replicas: 3
        ],
        metadata: [
            name: params.clusterName
        ],
        networking: [
            clusterNetwork: [[
                cidr: '10.128.0.0/14',
                hostPrefix: 23
            ]],
            machineNetwork: [[
                cidr: '10.0.0.0/16'
            ]],
            networkType: 'OVNKubernetes',
            serviceNetwork: ['172.30.0.0/16']
        ],
        platform: [
            aws: [
                region: params.awsRegion,
                userTags: [
                    'iit-billing-tag': 'openshift',
                    'delete-cluster-after-hours': params.deleteAfterHours,
                    'team': params.teamName,
                    'product': params.productTag,
                    'owner': params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins',
                    'creationTime': new Date().getTime().toString()
                ]
            ]
        ],
        pullSecret: params.pullSecret,
        sshKey: params.sshPublicKey
    ]

    // Add spot instance configuration if enabled
    if (params.useSpotInstances) {
        config.compute[0].platform.aws.spotMarketOptions = [:]
        if (params.spotMaxPrice) {
            config.compute[0].platform.aws.spotMarketOptions.maxPrice = params.spotMaxPrice
        }
    }

    return new JsonBuilder(config).toPrettyString()
}

def createMetadata(Map params, String clusterDir) {
    def metadata = [
        cluster_name: params.clusterName,
        openshift_version: params.openshiftVersion,
        aws_region: params.awsRegion,
        created_date: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        created_by: params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins',
        jenkins_build: env.BUILD_NUMBER ?: '1',
        master_type: params.masterType,
        worker_type: params.workerType,
        worker_count: params.workerCount,
        test_mode: params.testMode
    ]

    def json = new JsonBuilder(metadata).toPrettyString()
    writeFile file: "${clusterDir}/metadata.json", text: json

    return metadata
}

def deployPMM(Map params) {
    echo "Deploying PMM ${params.pmmVersion} to namespace pmm-monitoring..."

    sh """
        # Create namespace
        oc create namespace pmm-monitoring || true

        # Grant anyuid SCC permissions
        oc adm policy add-scc-to-user anyuid -z default -n pmm-monitoring
        oc adm policy add-scc-to-user anyuid -z pmm -n pmm-monitoring

        # Add Percona Helm repo
        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
        helm repo update

        # Deploy PMM using Helm
        helm upgrade --install pmm percona/pmm \
            --namespace pmm-monitoring \
            --version ${params.pmmVersion.startsWith('3.') ? '1.4.6' : '1.3.12'} \
            --set platform=openshift \
            --set service.type=ClusterIP \
            --set pmmAdminPassword='${params.adminPassword}' \
            --wait --timeout 10m

        # Create OpenShift route for HTTPS access
        oc create route edge pmm-https \
            --service=pmm \
            --port=https \
            --insecure-policy=Redirect \
            -n pmm-monitoring || true
    """

    def pmmUrl = sh(
        script: "oc get route pmm-https -n pmm-monitoring -o jsonpath='{.spec.host}'",
        returnStdout: true
    ).trim()

    return [
        url: "https://${pmmUrl}",
        username: 'admin',
        password: params.adminPassword,
        namespace: 'pmm-monitoring'
    ]
}

def getClusterInfo(String clusterDir) {
    def info = [:]

    info.apiUrl = sh(
        script: "grep 'server:' ${clusterDir}/auth/kubeconfig | head -1 | awk '{print \$2}'",
        returnStdout: true
    ).trim()

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

def calculateUptime(String createdAt) {
    if (!createdAt || createdAt == 'Unknown') {
        return 'Unknown'
    }

    try {
        def created = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", createdAt)
        def now = new Date()
        def diff = now.time - created.time

        def days = (diff / (1000 * 60 * 60 * 24)) as Integer
        def hours = ((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)) as Integer

        if (days > 0) {
            return "${days}d ${hours}h"
        } else {
            return "${hours}h"
        }
    } catch (Exception e) {
        return 'Unknown'
    }
}

def displayClustersTable(List clusters) {
    if (clusters.isEmpty()) {
        echo 'No OpenShift clusters found.'
        return
    }

    def table = new StringBuilder()
    table.append('+').append('-' * 22).append('+').append('-' * 17).append('+')
    table.append('-' * 12).append('+').append('-' * 17).append('+')
    table.append('-' * 22).append('+').append('-' * 12).append('+')
    table.append('-' * 15).append('+').append('-' * 14).append('+\n')

    table.append(String.format('| %-20s | %-15s | %-10s | %-15s | %-20s | %-10s | %-13s | %-12s |\n',
                              'CLUSTER NAME', 'VERSION', 'REGION', 'CREATED BY', 'CREATED AT', 'UPTIME', 'PMM DEPLOYED', 'PMM VERSION'))

    table.append('+').append('-' * 22).append('+').append('-' * 17).append('+')
    table.append('-' * 12).append('+').append('-' * 17).append('+')
    table.append('-' * 22).append('+').append('-' * 12).append('+')
    table.append('-' * 15).append('+').append('-' * 14).append('+\n')

    clusters.each { cluster ->
        def createdAt = cluster.created_at
        if (createdAt && createdAt != 'Unknown' && createdAt.length() > 20) {
            createdAt = createdAt.substring(0, 10) + ' ' + createdAt.substring(11, 19)
        }

        table.append(String.format('| %-20s | %-15s | %-10s | %-15s | %-20s | %-10s | %-13s | %-12s |\n',
                                  cluster.name.take(20),
                                  cluster.version.take(15),
                                  cluster.region,
                                  cluster.created_by.take(15),
                                  createdAt.take(20),
                                  cluster.uptime.take(10),
                                  cluster.pmm_deployed,
                                  cluster.pmm_version.take(12)))
    }

    table.append('+').append('-' * 22).append('+').append('-' * 17).append('+')
    table.append('-' * 12).append('+').append('-' * 17).append('+')
    table.append('-' * 22).append('+').append('-' * 12).append('+')
    table.append('-' * 15).append('+').append('-' * 14).append('+\n')

    echo table.toString()
}

// Backward compatibility
def call(Map config) {
    return create(config)
}
