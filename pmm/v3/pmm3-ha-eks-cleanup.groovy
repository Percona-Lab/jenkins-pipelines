/**
 * PMM HA EKS Cleanup Pipeline
 *
 * Manages cleanup of PMM HA test clusters. Supports manual and scheduled runs.
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
library changelog: false, identifier: 'v3lib@fix/pmm-ha-eks-access-entries', retriever: modernSCM(
    scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
    libraryPath: 'pmm/v3/'
)

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
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        REGION = 'us-east-2'
        CLUSTER_PREFIX = "${pmmHaEks.CLUSTER_PREFIX}"
    }

    stages {
        stage('Detect Run Type') {
            steps {
                script {
                    // Override ACTION for scheduled runs (cron triggers use DELETE_OLD mode)
                    if (currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause')) {
                        env.ACTION = 'DELETE_OLD'
                        echo 'Triggered by cron - will delete clusters older than 1 day.'
                    } else {
                        env.ACTION = params.ACTION
                        echo "Manual run with ACTION=${params.ACTION}"
                    }

                    // Validation: ensure required parameters are provided
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
                        eksCluster.listClusters(region: env.REGION, prefix: pmmHaEks.CLUSTER_PREFIX)
                    }
                }
            }
        }

        stage('Delete Cluster') {
            when { expression { env.ACTION == 'DELETE_CLUSTER' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        // Parse comma-separated cluster names for batch deletion
                        def clusterNames = params.CLUSTER_NAME.split(',').collect { it.trim() }.findAll { it }

                        // Filter out non-existent clusters
                        def existing = clusterNames.findAll { name ->
                            eksCluster.clusterExists(clusterName: name, region: env.REGION)
                        }

                        if (!existing) {
                            echo "No clusters found: ${clusterNames.join(', ')}"
                            return
                        }

                        // Single function handles both single and parallel deletion
                        pmmHaEks.deleteClusters(clusterNames: existing, region: env.REGION)
                    }
                }
            }
        }

        stage('Delete All Clusters') {
            when { expression { env.ACTION == 'DELETE_ALL' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        // Force delete all clusters (ignores retention tags, respects SKIP_NEWEST)
                        pmmHaEks.deleteAllClusters(
                            region: env.REGION,
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
                        // Respect retention tags and protect newest cluster during automated runs
                        pmmHaEks.deleteAllClusters(
                            region: env.REGION,
                            skipNewest: true  // Protect newest during cron
                        )
                    }
                }
            }
        }
    }
}
