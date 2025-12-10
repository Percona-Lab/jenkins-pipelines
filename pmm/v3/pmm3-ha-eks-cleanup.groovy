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
                        eksCluster.listClustersWithAge(region: env.REGION, prefix: pmmHaEks.CLUSTER_PREFIX)
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
                            def clusterName = clusterNames[0]
                            if (eksCluster.clusterExists(clusterName: clusterName, region: env.REGION)) {
                                pmmHaEks.deleteCluster(clusterName: clusterName, region: env.REGION)
                            } else {
                                echo "Cluster '${clusterName}' not found in region '${REGION}'."
                            }
                        } else {
                            echo "Deleting ${clusterNames.size()} clusters in parallel: ${clusterNames.join(', ')}"
                            def parallelStages = [:]
                            clusterNames.each { clusterName ->
                                parallelStages[clusterName] = {
                                    if (eksCluster.clusterExists(clusterName: clusterName, region: env.REGION)) {
                                        pmmHaEks.deleteCluster(clusterName: clusterName, region: env.REGION)
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
                            prefix: pmmHaEks.CLUSTER_PREFIX,
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
                        pmmHaEks.deleteAllClusters(
                            prefix: pmmHaEks.CLUSTER_PREFIX,
                            region: env.REGION,
                            skipNewest: true  // Protect newest during cron
                        )
                    }
                }
            }
        }
    }
}
