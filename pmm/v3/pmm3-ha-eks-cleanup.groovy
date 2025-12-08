/**
 * PMM HA EKS Cleanup Pipeline
 *
 * Manages cleanup of PMM HA test clusters. Supports manual and scheduled runs.
 * Deletes Route53 records, ALB ingress, and EKS clusters.
 *
 * Actions:
 *   - LIST_ONLY: List all test clusters with age
 *   - DELETE_CLUSTER: Delete a specific cluster
 *   - DELETE_ALL: Delete all test clusters (respects SKIP_NEWEST and retention tags)
 *   - Cron: Automatically deletes expired/untagged clusters
 *
 * Related:
 *   - Create: pmm3-ha-eks.groovy
 *   - Shared library: vars/pmmHaEks.groovy
 */
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
])

pipeline {
    agent {
        label 'cli'
    }

    triggers {
        cron('H 0,12 * * *')
    }

    parameters {
        choice(
            name: 'ACTION',
            choices: ['LIST_ONLY', 'DELETE_CLUSTER', 'DELETE_ALL'],
            description: '''
                LIST_ONLY - list all test clusters<br/>
                DELETE_CLUSTER - delete a specific cluster (requires CLUSTER_NAME)<br/>
                DELETE_ALL - delete all test clusters<br/><br/>
                Note: Daily cron automatically deletes expired clusters.
            '''
        )
        string(name: 'CLUSTER_NAME', defaultValue: '', description: 'Cluster name(s) for DELETE_CLUSTER. Comma-separated for parallel deletion (e.g., pmm-ha-test-65,pmm-ha-test-66)')
        booleanParam(name: 'SKIP_NEWEST', defaultValue: true, description: 'Skip the most recent cluster (protects in-progress builds)')
        booleanParam(
            name: 'ENABLE_PUBLIC_INGRESS',
            defaultValue: true,
            description: 'Delete Route53 DNS records during cleanup (uncheck if clusters have no public ingress)'
        )
        string(
            name: 'R53_ZONE_NAME',
            defaultValue: 'cd.percona.com',
            description: 'Route53 hosted zone name (only used if ENABLE_PUBLIC_INGRESS is checked)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        REGION = 'us-east-2'
        CLUSTER_PREFIX = "${pmmHaEks.CLUSTER_PREFIX}"
        R53_ZONE_NAME = "${params.ENABLE_PUBLIC_INGRESS ? (params.R53_ZONE_NAME ?: 'cd.percona.com') : ''}"
    }

    stages {
        stage('Detect Run Type') {
            steps {
                script {
                    if (currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause')) {
                        env.ACTION = 'DELETE_OLD'
                        echo 'Triggered by cron - will delete clusters older than 1 day.'
                    } else {
                        env.ACTION = params.ACTION
                        echo "Manual run with ACTION=${params.ACTION}"
                    }

                    if (env.ACTION == 'DELETE_CLUSTER' && !params.CLUSTER_NAME) {
                        error('CLUSTER_NAME is required for DELETE_CLUSTER.')
                    }
                    if (params.CLUSTER_NAME && !params.CLUSTER_NAME.startsWith(env.CLUSTER_PREFIX)) {
                        error("Cluster name must start with ${env.CLUSTER_PREFIX}")
                    }
                }
            }
        }

        stage('List Clusters') {
            when { expression { env.ACTION == 'LIST_ONLY' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        def clusters = eksCluster.listClusters(region: env.REGION, prefix: pmmHaEks.CLUSTER_PREFIX)

                        if (!clusters) {
                            echo "No clusters found with prefix '${env.CLUSTER_PREFIX}'."
                            return
                        }

                        echo "Found ${clusters.size()} cluster(s):"
                        clusters.each { clusterName ->
                            def info = sh(
                                script: """
                                    CREATED=\$(aws eks describe-cluster --name ${clusterName} --region ${env.REGION} \
                                        --query 'cluster.createdAt' --output text)
                                    CREATED_EPOCH=\$(date -d "\${CREATED}" +%s)
                                    AGE_HOURS=\$(( ( \$(date +%s) - CREATED_EPOCH ) / 3600 ))
                                    echo "\${CREATED}|\${AGE_HOURS}"
                                """,
                                returnStdout: true
                            ).trim()
                            def parts = info.split('\\|')
                            echo "* ${clusterName} | Created: ${parts[0]} | Age: ${parts[1]}h"
                        }
                    }
                }
            }
        }

        stage('Delete Cluster') {
            when { expression { env.ACTION == 'DELETE_CLUSTER' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        def clusterNames = params.CLUSTER_NAME.split(',').collect { it.trim() }.findAll { it }

                        if (clusterNames.size() == 1) {
                            // Single cluster - delete directly
                            def clusterName = clusterNames[0]
                            def clusterExists = sh(
                                script: "aws eks describe-cluster --region ${REGION} --name ${clusterName} >/dev/null 2>&1",
                                returnStatus: true
                            ) == 0

                            if (clusterExists) {
                                pmmHaEks.deleteCluster(
                                    clusterName: clusterName,
                                    region: env.REGION,
                                    r53ZoneName: env.R53_ZONE_NAME
                                )
                            } else {
                                echo "Cluster '${clusterName}' not found in region '${REGION}'."
                            }
                        } else {
                            // Multiple clusters - delete in parallel
                            echo "Deleting ${clusterNames.size()} clusters in parallel: ${clusterNames.join(', ')}"
                            def parallelStages = [:]
                            clusterNames.each { clusterName ->
                                parallelStages[clusterName] = {
                                    def exists = sh(
                                        script: "aws eks describe-cluster --region ${REGION} --name ${clusterName} >/dev/null 2>&1",
                                        returnStatus: true
                                    ) == 0
                                    if (exists) {
                                        pmmHaEks.deleteCluster(
                                            clusterName: clusterName,
                                            region: env.REGION,
                                            r53ZoneName: env.R53_ZONE_NAME
                                        )
                                    } else {
                                        echo "Cluster '${clusterName}' not found."
                                    }
                                }
                            }
                            parallel parallelStages
                        }
                    }
                }
            }
        }

        stage('Delete All Clusters') {
            when { expression { env.ACTION == 'DELETE_ALL' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        pmmHaEks.deleteAllClusters(
                            region: env.REGION,
                            r53ZoneName: env.R53_ZONE_NAME,
                            skipNewest: params.SKIP_NEWEST,
                            respectRetention: false  // Force delete regardless of retention tags
                        )
                    }
                }
            }
        }

        stage('Delete Old Clusters (cron only)') {
            when { expression { env.ACTION == 'DELETE_OLD' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        pmmHaEks.deleteAllClusters(
                            region: env.REGION,
                            r53ZoneName: env.R53_ZONE_NAME,
                            skipNewest: true  // Protect newest during cron
                        )
                    }
                }
            }
        }
    }
}
