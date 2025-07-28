@Library('jenkins-pipelines') _

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    environment {
        OUTPUT_FILE = "${WORKSPACE}/cluster-list.txt"
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '50', daysToKeepStr: '7'))
        timestamps()
    }

    stages {
        stage('Setup') {
            steps {
                script {
                    // Clean workspace
                    deleteDir()

                    // Determine regions to check
                    if (params.AWS_REGION == 'all') {
                        env.REGIONS_TO_CHECK = 'us-east-1,us-east-2,us-west-2,eu-central-1,eu-west-1,eu-west-2,eu-west-3,ap-southeast-1,ap-southeast-2'
                    } else {
                        env.REGIONS_TO_CHECK = params.AWS_REGION
                    }

                    echo "Will check regions: ${env.REGIONS_TO_CHECK}"
                }
            }
        }

        stage('List Clusters') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        def allClusters = []
                        def regions = env.REGIONS_TO_CHECK.split(',')

                        // Get AWS account ID
                        def accountId = sh(
                            script: 'aws sts get-caller-identity --query Account --output text',
                            returnStdout: true
                        ).trim()

                        regions.each { region ->
                            region = region.trim()
                            echo "Checking region: ${region}"

                            def s3Bucket = "openshift-clusters-${accountId}-${region}"

                            // Check if bucket exists
                            def bucketExists = sh(
                                script: "aws s3api head-bucket --bucket ${s3Bucket} 2>/dev/null",
                                returnStatus: true
                            ) == 0

                            if (!bucketExists) {
                                echo "No S3 bucket found in region ${region}"
                                return
                            }

                            // List clusters in this region
                            try {
                                def clusters = manageClusterStateS3('list', [
                                    bucket: s3Bucket,
                                    region: region
                                ])

                                clusters.each { clusterName ->
                                    def clusterInfo = [
                                        name: clusterName,
                                        region: region,
                                        bucket: s3Bucket
                                    ]

                                    // Get metadata if requested
                                    if (params.SHOW_DETAILS) {
                                        def metadata = manageClusterStateS3.getClusterMetadata([
                                            bucket: s3Bucket,
                                            clusterName: clusterName,
                                            region: region
                                        ])

                                        if (metadata) {
                                            clusterInfo.putAll(metadata)

                                            // Calculate age
                                            if (metadata.created_date) {
                                                def createdDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", metadata.created_date)
                                                def ageHours = (new Date().time - createdDate.time) / (1000 * 60 * 60)
                                                clusterInfo.age_hours = ageHours.intValue()
                                            }
                                        }
                                    }

                                    // Check live status if requested
                                    if (params.CHECK_LIVE_STATUS) {
                                        clusterInfo.live_status = checkClusterLiveStatus(clusterName, region)
                                    }

                                    allClusters << clusterInfo
                                }
                            } catch (Exception e) {
                                echo "Error listing clusters in region ${region}: ${e.message}"
                            }
                        }

                        // Filter by age if requested
                        if (params.OLDER_THAN_HOURS && params.OLDER_THAN_HOURS.isInteger()) {
                            def minAge = params.OLDER_THAN_HOURS.toInteger()
                            allClusters = allClusters.findAll { cluster ->
                                cluster.age_hours && cluster.age_hours >= minAge
                            }
                        }

                        // Sort by creation date (newest first)
                        allClusters = allClusters.sort { a, b ->
                            (b.created_date ?: '') <=> (a.created_date ?: '')
                        }

                        // Format and display output
                        displayClusters(allClusters, params.OUTPUT_FORMAT)

                        // Store in environment for post actions
                        env.CLUSTER_COUNT = allClusters.size().toString()
                        env.CLUSTERS_JSON = groovy.json.JsonOutput.toJson(allClusters)
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                def count = env.CLUSTER_COUNT ?: '0'
                currentBuild.description = "Found ${count} clusters"

                // Archive the output
                if (fileExists(env.OUTPUT_FILE)) {
                    archiveArtifacts artifacts: 'cluster-list.txt', fingerprint: false
                }
            }
        }
        always {
            script {
                // Clean up credentials
                sh '''
                    unset AWS_ACCESS_KEY_ID
                    unset AWS_SECRET_ACCESS_KEY
                '''
            }
        }
    }
}

def checkClusterLiveStatus(String clusterName, String region) {
    try {
        // Check for running EC2 instances
        def instances = sh(
            script: """
                aws ec2 describe-instances \
                    --region ${region} \
                    --filters "Name=tag:Name,Values=${clusterName}*" \
                              "Name=instance-state-name,Values=running" \
                    --query 'Reservations[].Instances[].InstanceId' \
                    --output text 2>/dev/null || true
            """,
            returnStdout: true
        ).trim()

        return instances ? 'RUNNING' : 'NOT_FOUND'
    } catch (Exception e) {
        return 'CHECK_FAILED'
    }
}

def displayClusters(List clusters, String format) {
    switch (format) {
        case 'json':
            def json = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(clusters))
            echo json
            writeFile file: env.OUTPUT_FILE, text: json
            break

        case 'csv':
            def csv = new StringBuilder()
            csv.append('Name,Region,Version,Created,Age(Hours),Status,Master Type,Worker Type,Workers\n')
            clusters.each { cluster ->
                csv.append("${cluster.name},")
                csv.append("${cluster.region},")
                csv.append("${cluster.openshift_version ?: 'N/A'},")
                csv.append("${cluster.created_date ?: 'N/A'},")
                csv.append("${cluster.age_hours ?: 'N/A'},")
                csv.append("${cluster.live_status ?: 'N/A'},")
                csv.append("${cluster.master_type ?: 'N/A'},")
                csv.append("${cluster.worker_type ?: 'N/A'},")
                csv.append("${cluster.worker_count ?: 'N/A'}\n")
            }
            echo csv.toString()
            writeFile file: env.OUTPUT_FILE, text: csv.toString()
            break

        default: // table format
            def output = new StringBuilder()
            output.append('\n')
            output.append('='.multiply(120)).append('\n')
            output.append('OpenShift Clusters Summary\n')
            output.append('='.multiply(120)).append('\n')
            output.append(String.format('%-30s %-12s %-8s %-20s %-8s %-12s %-15s\n',
                'Cluster Name', 'Region', 'Version', 'Created', 'Age(h)', 'Status', 'Instance Types'))
            output.append('-'.multiply(120)).append('\n')

            clusters.each { cluster ->
                def instanceTypes = "${cluster.master_type ?: 'N/A'}/${cluster.worker_type ?: 'N/A'}"
                output.append(String.format('%-30s %-12s %-8s %-20s %-8s %-12s %-15s\n',
                    cluster.name,
                    cluster.region,
                    cluster.openshift_version ?: 'N/A',
                    cluster.created_date ? cluster.created_date.substring(0, 19) : 'N/A',
                    cluster.age_hours ?: 'N/A',
                    cluster.live_status ?: 'N/A',
                    instanceTypes
                ))
            }

            output.append('='.multiply(120)).append('\n')
            output.append("Total clusters: ${clusters.size()}\n")
            output.append('='.multiply(120)).append('\n')

            echo output.toString()
            writeFile file: env.OUTPUT_FILE, text: output.toString()
    }
}
