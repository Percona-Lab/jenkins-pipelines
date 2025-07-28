def call(Map config) {
    // Required parameters
    def required = ['clusterName', 'awsRegion', 's3Bucket', 'workDir']

    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    echo "Destroying OpenShift cluster: ${config.clusterName}"

    try {
        // Step 1: Check if cluster state exists in S3
        def stateExists = manageClusterStateS3('exists', [
            bucket: config.s3Bucket,
            clusterName: config.clusterName,
            region: config.awsRegion
        ])

        if (!stateExists) {
            echo "WARNING: No cluster state found in S3 for cluster: ${config.clusterName}"

            if (!config.force) {
                error 'Cluster state not found. Use force=true to attempt destruction anyway.'
            }
        }

        // Step 2: Get cluster metadata from S3
        def metadata = manageClusterStateS3.getClusterMetadata([
            bucket: config.s3Bucket,
            clusterName: config.clusterName,
            region: config.awsRegion
        ])

        if (metadata) {
            echo 'Found cluster metadata:'
            echo "  Created: ${metadata.created_date}"
            echo "  Created by: ${metadata.created_by}"
            echo "  OpenShift Version: ${metadata.openshift_version}"
            echo "  Region: ${metadata.aws_region}"
        }

        // Step 3: Download cluster state from S3
        def clusterDir = "${config.workDir}/${config.clusterName}"

        if (stateExists) {
            echo 'Downloading cluster state from S3...'

            def stateFile = "${config.workDir}/${config.clusterName}-state.tar.gz"
            def downloadResult = manageClusterStateS3('download', [
                bucket: config.s3Bucket,
                clusterName: config.clusterName,
                region: config.awsRegion,
                localPath: stateFile
            ])

            echo 'Extracting cluster state...'
            sh """
                cd ${config.workDir}
                tar -xzf ${config.clusterName}-state.tar.gz
                rm -f ${config.clusterName}-state.tar.gz
            """
        } else if (config.force && config.installConfig) {
            // If forcing destruction without state, create minimal structure
            echo 'Force mode: Creating minimal cluster structure...'
            sh "mkdir -p ${clusterDir}"
            writeFile file: "${clusterDir}/metadata.json", text: '{}'

            // If install config is provided, use it
            if (config.installConfig) {
                writeFile file: "${clusterDir}/install-config.yaml", text: config.installConfig
            }
        }

        // Step 4: Install OpenShift tools if needed
        def openshiftVersion = metadata?.openshift_version ?: config.openshiftVersion ?: 'latest'
        installOpenShiftTools([
            openshiftVersion: openshiftVersion
        ])

        // Step 5: Destroy the cluster
        if (fileExists(clusterDir)) {
            echo 'Destroying OpenShift cluster (this may take 10-20 minutes)...'

            def destroyCmd = """
                cd ${clusterDir}
                openshift-install destroy cluster --log-level=info
            """

            if (config.dryRun) {
                echo "DRY RUN: Would execute: ${destroyCmd}"
            } else {
                sh destroyCmd
            }

            // Step 6: Verify destruction
            if (!config.dryRun) {
                echo 'Verifying cluster destruction...'
                def remainingResources = checkRemainingResources(config.clusterName, config.awsRegion)

                if (remainingResources) {
                    echo 'WARNING: Some resources may still exist:'
                    remainingResources.each { resource ->
                        echo "  - ${resource}"
                    }

                    if (config.cleanupRemaining) {
                        echo 'Attempting to clean up remaining resources...'
                        cleanupRemainingResources(config.clusterName, config.awsRegion)
                    }
                }
            }

            // Step 7: Update metadata for destruction
            def destroyMetadata = createClusterMetadata.forDestroy([
                clusterName: config.clusterName,
                openshiftVersion: openshiftVersion,
                awsRegion: config.awsRegion,
                masterType: metadata?.master_type ?: 'unknown',
                workerType: metadata?.worker_type ?: 'unknown',
                workerCount: metadata?.worker_count ?: 0,
                destroyedBy: config.destroyedBy ?: env.BUILD_USER_ID ?: 'jenkins',
                destroyReason: config.reason ?: 'scheduled'
            ])

            // Save destruction metadata
            if (!config.dryRun) {
                writeFile file: "${clusterDir}/destroy-metadata.json", text: destroyMetadata
            }

            // Step 8: Clean up S3 state
            if (!config.keepBackup && !config.dryRun) {
                echo 'Removing cluster state from S3...'
                manageClusterStateS3('delete', [
                    bucket: config.s3Bucket,
                    clusterName: config.clusterName,
                    region: config.awsRegion
                ])
            } else {
                echo 'Keeping cluster backup in S3 as requested'
            }

            // Step 9: Clean up local files
            if (!config.keepLocal && !config.dryRun) {
                echo 'Cleaning up local cluster directory...'
                sh "rm -rf ${clusterDir}"
            }

            echo "Cluster ${config.clusterName} has been successfully destroyed"

            return [
                clusterName: config.clusterName,
                destroyed: true,
                metadata: destroyMetadata,
                remainingResources: remainingResources ?: []
            ]
        } else {
            error "Cluster directory not found: ${clusterDir}"
        }
    } catch (Exception e) {
        error "Failed to destroy OpenShift cluster: ${e.message}"
    }
}

def checkRemainingResources(String clusterName, String region) {
    echo 'Checking for remaining AWS resources...'

    def remainingResources = []

    // Check for EC2 instances
    def instanceCheck = sh(
        script: """
            aws ec2 describe-instances \
                --region ${region} \
                --filters "Name=tag:Name,Values=${clusterName}*" \
                          "Name=instance-state-name,Values=running,pending,stopping,stopped" \
                --query 'Reservations[].Instances[].InstanceId' \
                --output text 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()

    if (instanceCheck) {
        remainingResources << "EC2 Instances: ${instanceCheck}"
    }

    // Check for VPCs
    def vpcCheck = sh(
        script: """
            aws ec2 describe-vpcs \
                --region ${region} \
                --filters "Name=tag:Name,Values=${clusterName}*" \
                --query 'Vpcs[].VpcId' \
                --output text 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()

    if (vpcCheck) {
        remainingResources << "VPCs: ${vpcCheck}"
    }

    // Check for ELBs
    def elbCheck = sh(
        script: """
            aws elb describe-load-balancers \
                --region ${region} \
                --query "LoadBalancerDescriptions[?contains(LoadBalancerName, '${clusterName}')].LoadBalancerName" \
                --output text 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()

    if (elbCheck) {
        remainingResources << "Classic Load Balancers: ${elbCheck}"
    }

    return remainingResources
}

def cleanupRemainingResources(String clusterName, String region) {
    echo "Attempting to clean up remaining resources for cluster: ${clusterName}"

    // This is a simplified cleanup - in production you'd want more comprehensive cleanup
    sh """
        # Delete tagged EC2 instances
        aws ec2 describe-instances \
            --region ${region} \
            --filters "Name=tag:Name,Values=${clusterName}*" \
                      "Name=instance-state-name,Values=running,pending,stopping,stopped" \
            --query 'Reservations[].Instances[].InstanceId' \
            --output text 2>/dev/null | xargs -r aws ec2 terminate-instances --region ${region} --instance-ids || true

        echo "Cleanup attempt completed. Some resources may require manual cleanup."
    """
}
