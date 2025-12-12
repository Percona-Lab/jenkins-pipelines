@Library('jenkins-pipelines') _

/**
 * Destroys a ROSA HCP cluster and cleans up associated resources.
 *
 * This pipeline replaces the IPI-based openshift_cluster_destroy.groovy.
 * It handles deletion of ROSA clusters, VPC stacks, and S3 state.
 */
pipeline {
    agent { label 'agent-amd64-ol9' }

    parameters {
        string(
            name: 'CLUSTER_NAME',
            defaultValue: '',
            description: 'Name of the ROSA cluster to destroy (required)'
        )
        choice(
            name: 'AWS_REGION',
            choices: ['us-east-2'],
            description: 'AWS region where cluster is located'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'Show what would be deleted without actually deleting'
        )
        choice(
            name: 'DESTROY_REASON',
            choices: ['manual', 'scheduled', 'testing-complete', 'cost-optimization', 'other'],
            description: 'Reason for cluster destruction'
        )
        booleanParam(
            name: 'DELETE_ROUTE53',
            defaultValue: true,
            description: 'Delete associated Route53 DNS records'
        )
        string(
            name: 'BASE_DOMAIN',
            defaultValue: 'cd.percona.com',
            description: 'Base domain for Route53 cleanup'
        )
    }

    environment {
        S3_BUCKET = 'openshift-clusters-119175775298-us-east-2'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
    }

    stages {
        stage('Validate') {
            steps {
                script {
                    if (!params.CLUSTER_NAME?.trim()) {
                        error 'CLUSTER_NAME is required. Please specify the cluster to destroy.'
                    }

                    env.TARGET_CLUSTER = params.CLUSTER_NAME.trim()

                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.TARGET_CLUSTER}"
                    currentBuild.description = "${params.DRY_RUN ? '[DRY RUN] ' : ''}Destroying ${env.TARGET_CLUSTER} | ${params.DESTROY_REASON}"
                }
            }
        }

        stage('Install CLI Tools') {
            steps {
                script {
                    openshiftRosa.installRosaCli()
                }
            }
        }

        stage('Login to ROSA') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        openshiftRosa.login([
                            token: env.ROSA_TOKEN,
                            region: params.AWS_REGION
                        ])
                    }
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
                        // Check if cluster exists in ROSA
                        def clusters = openshiftRosa.listClusters([region: params.AWS_REGION])
                        def targetCluster = clusters.find { it.name == env.TARGET_CLUSTER }

                        if (targetCluster) {
                            echo """
====================================================================
CLUSTER FOUND IN ROSA
====================================================================
Name:           ${targetCluster.name}
ID:             ${targetCluster.id}
State:          ${targetCluster.state}
Version:        ${targetCluster.version}
Region:         ${targetCluster.region}
Created:        ${targetCluster.createdAt}
Age:            ${openshiftRosa.getClusterAgeHours(targetCluster.createdAt)} hours
====================================================================
"""
                            env.CLUSTER_ID = targetCluster.id
                            env.CLUSTER_STATE = targetCluster.state
                        } else {
                            echo "WARNING: Cluster '${env.TARGET_CLUSTER}' not found in ROSA API"
                            echo 'Will attempt to clean up S3 state and Route53 records if they exist'
                            env.CLUSTER_STATE = 'not-found'
                        }

                        // Check S3 state
                        def s3State = openshiftRosa.getClusterState([
                            clusterName: env.TARGET_CLUSTER,
                            region: params.AWS_REGION
                        ])

                        if (s3State) {
                            echo """
S3 STATE FOUND
--------------
Created by:     ${s3State.created_by ?: 'unknown'}
Created date:   ${s3State.created_date ?: 'unknown'}
Team:           ${s3State.team_name ?: 'unknown'}
TTL:            ${s3State.delete_after_hours ?: 'unknown'} hours
"""
                        } else {
                            echo 'No S3 state found for this cluster'
                        }
                    }
                }
            }
        }

        stage('Dry Run Report') {
            when { expression { params.DRY_RUN } }
            steps {
                script {
                    echo """
====================================================================
DRY RUN - WOULD DELETE THE FOLLOWING
====================================================================

ROSA Cluster:     ${env.TARGET_CLUSTER} (${env.CLUSTER_STATE})
VPC Stack:        ${env.TARGET_CLUSTER}-vpc
S3 State:         s3://${env.S3_BUCKET}/${env.TARGET_CLUSTER}/
Route53 Records:  ${params.DELETE_ROUTE53 ? "${env.TARGET_CLUSTER}.${params.BASE_DOMAIN}" : 'SKIPPED'}

Operator Roles:   Will be deleted
OIDC Provider:    Will NOT be deleted (shared)

====================================================================
To proceed with deletion, run this job again with DRY_RUN=false
====================================================================
"""
                    currentBuild.description = "[DRY RUN] Would delete ${env.TARGET_CLUSTER}"
                }
            }
        }

        stage('Delete Route53 Records') {
            when {
                allOf {
                    expression { !params.DRY_RUN }
                    expression { params.DELETE_ROUTE53 }
                }
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
                        echo "Deleting Route53 DNS records for ${env.TARGET_CLUSTER}..."

                        // Delete PMM record if it exists
                        openshiftRosa.deleteRoute53Record([
                            domain: "${env.TARGET_CLUSTER}.${params.BASE_DOMAIN}",
                            zoneName: params.BASE_DOMAIN
                        ])

                        echo 'Route53 cleanup completed'
                    }
                }
            }
        }

        stage('Delete Cluster') {
            when { expression { !params.DRY_RUN && env.CLUSTER_STATE != 'not-found' } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        echo "Deleting ROSA cluster: ${env.TARGET_CLUSTER}"

                        def deleteResult = openshiftRosa.deleteCluster([
                            clusterName: env.TARGET_CLUSTER,
                            region: params.AWS_REGION,
                            deleteOidc: false,  // Keep OIDC config (shared)
                            deleteOperatorRoles: true,
                            deleteVpc: true
                        ])

                        if (deleteResult.deleted) {
                            echo "ROSA cluster deletion initiated for ${env.TARGET_CLUSTER}"
                            echo "Cluster ID: ${deleteResult.clusterId}"
                        }
                    }
                }
            }
        }

        stage('Cleanup S3 State') {
            when { expression { !params.DRY_RUN } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        echo "Cleaning up S3 state for ${env.TARGET_CLUSTER}..."

                        openshiftRosa.deleteClusterState([
                            clusterName: env.TARGET_CLUSTER,
                            region: params.AWS_REGION
                        ])

                        echo 'S3 state cleanup completed'
                    }
                }
            }
        }

        stage('Verify Deletion') {
            when { expression { !params.DRY_RUN } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        echo 'Verifying cluster deletion...'

                        // Wait briefly for deletion to register
                        sleep(time: 10, unit: 'SECONDS')

                        def clusters = openshiftRosa.listClusters([region: params.AWS_REGION])
                        def targetCluster = clusters.find { it.name == env.TARGET_CLUSTER }

                        if (targetCluster) {
                            if (targetCluster.state == 'uninstalling') {
                                echo "Cluster ${env.TARGET_CLUSTER} is being uninstalled (this may take 5-10 minutes)"
                            } else {
                                echo "WARNING: Cluster still exists with state: ${targetCluster.state}"
                            }
                        } else {
                            echo "Cluster ${env.TARGET_CLUSTER} no longer appears in ROSA cluster list"
                        }

                        currentBuild.description = "Deleted ${env.TARGET_CLUSTER} | ${params.DESTROY_REASON}"
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                if (!params.DRY_RUN) {
                    echo """
====================================================================
CLUSTER DESTRUCTION COMPLETED
====================================================================
Cluster:          ${env.TARGET_CLUSTER}
Region:           ${params.AWS_REGION}
Reason:           ${params.DESTROY_REASON}
Route53 Cleanup:  ${params.DELETE_ROUTE53 ? 'Yes' : 'Skipped'}

Note: ROSA cluster deletion runs asynchronously in AWS.
Full resource cleanup may take 5-10 additional minutes.
====================================================================
"""
                }
            }
        }
        always {
            cleanWs()
        }
    }
}
