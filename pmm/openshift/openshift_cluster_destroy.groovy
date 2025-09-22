// groovylint-disable-next-line UnusedVariable, VariableName
@Library('jenkins-pipelines') _

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    environment {
        WORK_DIR = "${WORKSPACE}/openshift-clusters"
        S3_BUCKET = 'openshift-clusters-119175775298-us-east-2'
    }

    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '100', daysToKeepStr: '30'))
        timestamps()
    }

    stages {
        stage('Validate') {
            steps {
                script {
                    // Validate cluster name is provided
                    if (!params.CLUSTER_NAME || params.CLUSTER_NAME.trim().isEmpty()) {
                        error 'Cluster name is required'
                    }

                    // Clean workspace
                    deleteDir()

                    // Create work directory
                    sh "mkdir -p ${WORK_DIR}"

                    echo "Preparing to destroy cluster: ${params.CLUSTER_NAME}"

                    // Set initial build description - optimized for Blue Ocean
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${params.CLUSTER_NAME}"
                    currentBuild.description = "${params.CLUSTER_NAME} | ${params.AWS_REGION} | ${params.DESTROY_REASON} | DESTROYING"
                }
            }
        }

        stage('Check Cluster State') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        // Check if cluster state exists in S3
                        // Check if state exists by trying to get metadata
                        def metadata = openshiftS3.getMetadata([
                            bucket: env.S3_BUCKET,
                            clusterName: params.CLUSTER_NAME,
                            region: params.AWS_REGION,
                            accessKey: AWS_ACCESS_KEY_ID,
                            secretKey: AWS_SECRET_ACCESS_KEY
                        ])
                        def stateExists = (metadata != null)

                        if (!stateExists) {
                            error "Cluster state not found in S3 for cluster: ${params.CLUSTER_NAME}"
                        }

                        // Get cluster metadata if available
                        // Metadata already retrieved above

                        if (metadata) {
                            echo "Found cluster in S3:"
                            echo ""
                            // Cluster info (most important)
                            echo "Cluster Name:        ${params.CLUSTER_NAME}"
                            echo "OpenShift Version:   ${metadata.openshift_version}"
                            echo "AWS Region:          ${params.AWS_REGION}"
                            echo ""
                            // Resources
                            echo "Master Nodes:        3 × ${metadata.master_type}"
                            echo "Worker Nodes:        ${metadata.worker_count} × ${metadata.worker_type}"
                            echo ""
                            // Metadata
                            echo "Created:             ${metadata.created_date}"
                            echo "Created By:          ${metadata.created_by}"
                            echo ""

                            // Store metadata as JSON
                            if (metadata) {
                                env.CLUSTER_METADATA = JsonOutput.toJson(metadata)
                                // Update description with cluster version info - optimized for Blue Ocean
                                def ocpVersion = metadata.openshift_version ?: 'Unknown'
                                currentBuild.description = "${params.CLUSTER_NAME} | OCP ${ocpVersion} | ${params.AWS_REGION} | ${params.DESTROY_REASON} | DESTROYING"
                            }
                        }
                    }
                }
            }
        }

        stage('Destroy Cluster') {
            when {
                expression { !params.DRY_RUN }
            }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        def destroyConfig = [
                            clusterName: params.CLUSTER_NAME,
                            awsRegion: params.AWS_REGION,
                            s3Bucket: env.S3_BUCKET,
                            workDir: env.WORK_DIR,
                            reason: params.DESTROY_REASON,
                            destroyedBy: env.BUILD_USER_ID ?: 'jenkins'
                        ]

                        // Destroy the cluster
                        def result = openshiftCluster.destroy(destroyConfig)

                        // Store result as JSON
                        if (result) {
                            env.DESTROY_RESULT = JsonOutput.toJson(result)

                            // Update description with resource count if available
                            def resourceCount = result.resourcesDeleted ?: 'Unknown'
                            def ocpVersion = env.CLUSTER_METADATA ?
                                new JsonSlurper().parseText(env.CLUSTER_METADATA).openshift_version : 'Unknown'
                            currentBuild.description = "${params.CLUSTER_NAME} | OCP:${ocpVersion} | ${params.AWS_REGION} | Deleting ${resourceCount} resources..."
                        }
                    }
                }
            }
        }

        stage('Dry Run Report') {
            when {
                expression { params.DRY_RUN }
            }
            steps {
                script {
                    echo """
                    ========================================
                    DRY RUN - Cluster Destruction Plan
                    ========================================
                    Cluster Name: ${params.CLUSTER_NAME}
                    AWS Region: ${params.AWS_REGION}
                    S3 Bucket: ${env.S3_BUCKET}

                    Actions that would be performed:
                    1. Download cluster state from S3
                    2. Run 'openshift-install destroy cluster'
                    3. Delete cluster state from S3

                    Reason: ${params.DESTROY_REASON}
                    ========================================
                    """

                    def ocpVersion = env.CLUSTER_METADATA ?
                        new JsonSlurper().parseText(env.CLUSTER_METADATA).openshift_version : 'Unknown'
                    currentBuild.description = "${params.CLUSTER_NAME} | OCP ${ocpVersion} | ${params.AWS_REGION} | DRY-RUN"
                }
            }
        }
    }

    post {
        success {
            script {
                if (!params.DRY_RUN) {
                    echo "Cluster ${params.CLUSTER_NAME} destroyed successfully"

                    // Get final resource count and OCP version for description
                    def resourceCount = env.DESTROY_RESULT ?
                        new JsonSlurper().parseText(env.DESTROY_RESULT).resourcesDeleted : 'Unknown'
                    def ocpVersion = env.CLUSTER_METADATA ?
                        new JsonSlurper().parseText(env.CLUSTER_METADATA).openshift_version : 'Unknown'

                    currentBuild.description = "${params.CLUSTER_NAME} | OCP ${ocpVersion} | ${params.AWS_REGION} | ${params.DESTROY_REASON} | DESTROYED"

                    // Send notification if configured
                    if (env.SLACK_WEBHOOK) {
                        slackSend(
                            color: 'warning',
                            message: "OpenShift cluster destroyed: ${params.CLUSTER_NAME} in ${params.AWS_REGION} (Reason: ${params.DESTROY_REASON})"
                        )
                    }
                }
            }
        }
        failure {
            script {
                echo "Failed to destroy cluster ${params.CLUSTER_NAME}"

                // Get OCP version and failed stage for description
                def ocpVersion = env.CLUSTER_METADATA ?
                    new JsonSlurper().parseText(env.CLUSTER_METADATA).openshift_version : 'Unknown'
                def failedStage = env.STAGE_NAME ?: 'Unknown'

                currentBuild.description = "${params.CLUSTER_NAME} | OCP ${ocpVersion} | ${params.AWS_REGION} | FAILED: ${failedStage}"

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'danger',
                        message: "Failed to destroy OpenShift cluster: ${params.CLUSTER_NAME}"
                    )
                }
            }
        }
        aborted {
            script {
                echo "Cluster destruction was aborted: ${params.CLUSTER_NAME}"

                // Get OCP version for description
                def ocpVersion = env.CLUSTER_METADATA ?
                    new JsonSlurper().parseText(env.CLUSTER_METADATA).openshift_version : 'Unknown'

                currentBuild.description = "${params.CLUSTER_NAME} | OCP ${ocpVersion} | ${params.AWS_REGION} | ABORTED"

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'warning',
                        message: "OpenShift cluster destruction aborted: ${params.CLUSTER_NAME}"
                    )
                }
            }
        }
        always {
            script {
                // Log destruction attempt
                if (!params.DRY_RUN) {
                    // Determine status based on currentBuild.result
                    def status = 'UNKNOWN'
                    if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                        status = 'SUCCESS (Cluster Destroyed)'
                    } else if (currentBuild.result == 'FAILURE') {
                        status = 'FAILED (Resources may remain)'
                    } else {
                        status = currentBuild.result
                    }

                    echo """
                    OpenShift Cluster Destruction Summary
====================================================================

Cluster:              ${params.CLUSTER_NAME}
Region:               ${params.AWS_REGION}
Status:               ${status}
Reason:               ${params.DESTROY_REASON}
User:                 ${env.BUILD_USER_ID ?: 'jenkins'}
Build:                ${BUILD_NUMBER}
Time:                 ${new Date()}

====================================================================
                    """
                }
            }
        }
    }
}
