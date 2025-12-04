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
 *   - DELETE_OLD (cron): Delete expired/untagged clusters + cleanup orphaned resources
 *   - CLEANUP_ORPHANS: Delete orphaned VPCs and failed CF stacks
 *
 * Related:
 *   - Create: pmm3-ha-eks.groovy
 *   - Shared library: vars/pmmHaEks.groovy
 */
library changelog: false, identifier: 'lib@fix/pmm-ha-eks-access-entries', retriever: modernSCM([
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
            choices: ['LIST_ONLY', 'DELETE_CLUSTER', 'DELETE_ALL', 'CLEANUP_ORPHANS'],
            description: '''
                LIST_ONLY - list all test clusters<br/>
                DELETE_CLUSTER - delete a specific cluster (requires CLUSTER_NAME)<br/>
                DELETE_ALL - delete all test clusters<br/>
                CLEANUP_ORPHANS - delete orphaned VPCs and failed CF stacks<br/><br/>
                Note: Daily cron automatically deletes clusters older than 1 day.
            '''
        )
        string(name: 'CLUSTER_NAME', defaultValue: '', description: 'Required only for DELETE_CLUSTER')
        booleanParam(name: 'SKIP_NEWEST', defaultValue: true, description: 'Skip the most recent cluster (protects in-progress builds)')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        REGION = 'us-east-2'
        CLUSTER_PREFIX = "${pmmHaEks.CLUSTER_PREFIX}"
        R53_ZONE_NAME = 'cd.percona.com'
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
                        def clusters = pmmHaEks.listClusters(env.REGION)

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
                        def clusterExists = sh(
                            script: "aws eks describe-cluster --region ${REGION} --name ${params.CLUSTER_NAME} >/dev/null 2>&1",
                            returnStatus: true
                        ) == 0

                        if (clusterExists) {
                            pmmHaEks.deleteCluster(
                                clusterName: params.CLUSTER_NAME,
                                region: env.REGION,
                                r53ZoneName: env.R53_ZONE_NAME
                            )
                        } else {
                            echo "Cluster '${params.CLUSTER_NAME}' not found in region '${REGION}'."
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
                            maxAgeHours: 0  // Delete all regardless of age
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
                            skipNewest: true  // Always protect newest during cron
                        )
                        // Also clean up orphaned resources during cron
                        pmmHaEks.cleanupOrphans(region: env.REGION)
                    }
                }
            }
        }

        stage('Cleanup Orphan Resources') {
            when { expression { env.ACTION == 'CLEANUP_ORPHANS' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        pmmHaEks.cleanupOrphans(region: env.REGION)
                    }
                }
            }
        }
    }
}
