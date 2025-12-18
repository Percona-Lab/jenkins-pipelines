@Library('jenkins-pipelines@feature/pmm-ha-rosa') _

def openshiftRosa

/**
 * PMM HA ROSA Cleanup - Manages cleanup of PMM HA ROSA HCP clusters.
 *
 * Supports three actions:
 * - LIST_ONLY: Show all PMM HA ROSA clusters without deleting
 * - DELETE_OLD: Delete clusters older than 24 hours (automatic cleanup)
 * - DELETE_NAMED: Delete specific clusters by name
 *
 * Designed to run on a schedule (cron) for cost management.
 */

pipeline {
    agent { label 'agent-amd64-ol9' }

    environment {
        AWS_REGION = 'us-east-2'
        CLUSTER_PREFIX = 'pmm-ha-rosa-'
        MAX_AGE_HOURS = 24
    }

    parameters {
        choice(
            name: 'ACTION',
            choices: ['LIST_ONLY', 'DELETE_OLD', 'DELETE_NAMED'],
            description: '''
                LIST_ONLY - List all PMM HA ROSA clusters
                DELETE_OLD - Delete clusters older than 24 hours
                DELETE_NAMED - Delete specific clusters by name
            '''
        )
        string(
            name: 'CLUSTER_NAMES',
            defaultValue: '',
            description: 'Comma-separated list of cluster names to delete (for DELETE_NAMED action)'
        )
        booleanParam(
            name: 'SKIP_NEWEST',
            defaultValue: true,
            description: 'Skip the most recently created cluster when using DELETE_OLD'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
        timeout(time: 60, unit: 'MINUTES')
        timestamps()
    }

    stages {
        stage('Checkout & Load') {
            steps {
                checkout scm
                script {
                    openshiftRosa = load 'pmm/v3/vars/openshiftRosa.groovy'
                }
            }
        }

        stage('Setup') {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${params.ACTION}"
                    currentBuild.description = "Action: ${params.ACTION}"

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
                            region: env.AWS_REGION
                        ])
                    }
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
                        def allClusters = openshiftRosa.listClusters([region: env.AWS_REGION])
                        def pmmHaClusters = allClusters.findAll { it.name.startsWith(env.CLUSTER_PREFIX) }

                        def clusterCount = pmmHaClusters.size()
                        env.CLUSTER_COUNT = clusterCount.toString()

                        if (clusterCount == 0) {
                            echo 'No PMM HA ROSA clusters found'
                            currentBuild.description = 'No clusters found'
                        } else {
                            // Add age information
                            for (int i = 0; i < pmmHaClusters.size(); i++) {
                                pmmHaClusters[i].ageHours = openshiftRosa.getClusterAgeHours(pmmHaClusters[i].createdAt)
                            }

                            // Sort by creation time (newest first)
                            pmmHaClusters = pmmHaClusters.sort { a, b -> (a.ageHours ?: 0) <=> (b.ageHours ?: 0) }

                            def title = "PMM HA ROSA CLUSTERS (${clusterCount} found)"
                            echo openshiftRosa.formatClustersSummary(pmmHaClusters, title)

                            // Store cluster list for later stages
                            env.CLUSTER_LIST = pmmHaClusters.collect { it.name }.join(',')

                            // Identify clusters to delete based on action
                            if (params.ACTION == 'DELETE_OLD') {
                                def oldClusters = pmmHaClusters.findAll { it.ageHours >= env.MAX_AGE_HOURS.toInteger() }

                                if (params.SKIP_NEWEST && oldClusters.size() > 0) {
                                    // Remove the newest cluster from deletion list
                                    def newestOld = oldClusters.min { it.ageHours }
                                    oldClusters = oldClusters.findAll { it.name != newestOld.name }
                                    echo "Skipping newest old cluster: ${newestOld.name} (${newestOld.ageHours}h old)"
                                }

                                env.CLUSTERS_TO_DELETE = oldClusters.collect { it.name }.join(',')
                                echo "Clusters to delete (older than ${env.MAX_AGE_HOURS}h): ${env.CLUSTERS_TO_DELETE ?: 'none'}"
                            } else if (params.ACTION == 'DELETE_NAMED') {
                                def requestedNames = params.CLUSTER_NAMES?.split(',')?.collect { it.trim() }?.findAll { it }
                                def validNames = requestedNames?.findAll { name ->
                                    pmmHaClusters.any { it.name == name }
                                }
                                env.CLUSTERS_TO_DELETE = validNames?.join(',') ?: ''

                                if (requestedNames && validNames?.size() != requestedNames.size()) {
                                    def invalid = requestedNames - validNames
                                    echo "WARNING: Some clusters not found: ${invalid.join(', ')}"
                                }
                            }
                        }
                    }
                }
            }
        }

        stage('Delete Clusters') {
            when {
                expression {
                    params.ACTION in ['DELETE_OLD', 'DELETE_NAMED'] && env.CLUSTERS_TO_DELETE?.trim()
                }
            }
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
                        def clustersToDelete = env.CLUSTERS_TO_DELETE.split(',').collect { it.trim() }.findAll { it }
                        def deleted = []
                        def failed = []

                        echo "Starting deletion of ${clustersToDelete.size()} cluster(s)..."

                        clustersToDelete.each { clusterName ->
                            try {
                                echo "Deleting cluster: ${clusterName}"
                                openshiftRosa.deleteCluster([
                                    clusterName: clusterName,
                                    region: env.AWS_REGION,
                                    deleteOidc: false,
                                    deleteOperatorRoles: true,
                                    deleteVpc: true
                                ])
                                deleted.add(clusterName)
                                echo "Successfully initiated deletion: ${clusterName}"
                            } catch (Exception e) {
                                echo "Failed to delete ${clusterName}: ${e.message}"
                                failed.add(clusterName)
                            }
                        }

                        def summary = """
====================================================================
CLEANUP SUMMARY
====================================================================
Action:          ${params.ACTION}
Total clusters:  ${env.CLUSTER_COUNT}
Deleted:         ${deleted.size()} (${deleted.join(', ') ?: 'none'})
Failed:          ${failed.size()} (${failed.join(', ') ?: 'none'})
====================================================================
"""
                        echo summary
                        currentBuild.description = "Deleted: ${deleted.size()}, Failed: ${failed.size()}"

                        if (failed.size() > 0) {
                            unstable("Some cluster deletions failed: ${failed.join(', ')}")
                        }
                    }
                }
            }
        }

        stage('Summary') {
            when {
                expression { params.ACTION == 'LIST_ONLY' || !env.CLUSTERS_TO_DELETE?.trim() }
            }
            steps {
                script {
                    if (params.ACTION == 'LIST_ONLY') {
                        echo "LIST_ONLY action completed. Found ${env.CLUSTER_COUNT} PMM HA ROSA cluster(s)."
                        currentBuild.description = "Listed ${env.CLUSTER_COUNT} cluster(s)"
                    } else {
                        echo 'No clusters matched deletion criteria.'
                        currentBuild.description = 'No clusters to delete'
                    }
                }
            }
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}
