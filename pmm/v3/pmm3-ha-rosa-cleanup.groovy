library changelog: false, identifier: 'lib@feature/pmm-ha-rosa', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
])

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    triggers {
        cron('H 0,12 * * *') // Runs twice daily at 00:00 & 12:00
    }

    parameters {
        choice(
            name: 'ACTION',
            choices: ['LIST_ONLY', 'DELETE_CLUSTERS', 'DELETE_ALL'],
            description: '''
                LIST_ONLY - list all PMM HA ROSA clusters<br/>
                DELETE_CLUSTERS - delete specific clusters (comma-separated list)<br/>
                DELETE_ALL - delete all PMM HA ROSA clusters<br/><br/>
                Note: Daily cron automatically deletes clusters older than 1 day.
            '''
        )
        string(
            name: 'CLUSTER_NAMES',
            defaultValue: '',
            description: 'Comma-separated list of cluster names to delete (e.g., pmm-ha-rosa-23,pmm-ha-rosa-24)'
        )
        booleanParam(
            name: 'SKIP_NEWEST',
            defaultValue: true,
            description: 'Skip the most recently created cluster (useful when a new cluster is being created)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 120, unit: 'MINUTES')
    }

    environment {
        REGION = 'us-east-2'
        CLUSTER_PREFIX = 'pmm-ha-rosa-'
        R53_ZONE_NAME = 'cd.percona.com'
    }

    stages {
        stage('Install CLI Tools') {
            steps {
                script {
                    pmmHaRosa.installRosaCli()
                    pmmHaRosa.installOcCli()
                }
            }
        }

        stage('Login to ROSA') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        pmmHaRosa.login([
                            token: env.ROSA_TOKEN,
                            region: env.REGION
                        ])
                    }
                }
            }
        }

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

                    if (env.ACTION == 'DELETE_CLUSTERS' && !params.CLUSTER_NAMES?.trim()) {
                        error('CLUSTER_NAMES is required for DELETE_CLUSTERS action.')
                    }

                    // Validate cluster names if provided
                    if (params.CLUSTER_NAMES?.trim()) {
                        def names = params.CLUSTER_NAMES.split(',').collect { it.trim() }
                        names.each { name ->
                            if (!name.startsWith(env.CLUSTER_PREFIX)) {
                                error("Cluster name '${name}' must start with ${env.CLUSTER_PREFIX}")
                            }
                        }
                    }
                }
            }
        }

        stage('List Clusters') {
            when { expression { env.ACTION == 'LIST_ONLY' } }
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        def clusters = pmmHaRosa.listClusters([
                            region: env.REGION
                        ])

                        if (clusters.isEmpty()) {
                            echo "No clusters found with prefix '${env.CLUSTER_PREFIX}'."
                        } else {
                            echo "Found ${clusters.size()} cluster(s):"
                            echo ''
                            clusters.each { cluster ->
                                def ageHours = pmmHaRosa.getClusterAgeHours(cluster.createdAt)
                                echo "• ${cluster.name}"
                                echo "  State:   ${cluster.state}"
                                echo "  Version: ${cluster.version}"
                                echo "  Region:  ${cluster.region}"
                                echo "  Age:     ${ageHours}h"
                                echo "  Created: ${cluster.createdAt}"
                                echo ''
                            }
                        }
                    }
                }
            }
        }

        stage('Delete Clusters') {
            when { expression { env.ACTION == 'DELETE_CLUSTERS' } }
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        def clusterNames = params.CLUSTER_NAMES.split(',').collect { it.trim() }
                        echo "Deleting ${clusterNames.size()} cluster(s): ${clusterNames.join(', ')}"

                        clusterNames.each { clusterName ->
                            echo "Deleting cluster: ${clusterName}"

                            // Delete Route53 record first
                            try {
                                pmmHaRosa.deleteRoute53Record([
                                    domain: "${clusterName}.${env.R53_ZONE_NAME}"
                                ])
                            } catch (Exception e) {
                                echo "Warning: Could not delete Route53 record for ${clusterName}: ${e.message}"
                            }

                            // Delete the ROSA cluster
                            try {
                                pmmHaRosa.deleteCluster([
                                    clusterName: clusterName
                                ])
                                echo "Cluster ${clusterName} deletion initiated."
                            } catch (Exception e) {
                                echo "Error deleting ${clusterName}: ${e.message}"
                            }
                        }

                        echo 'All specified clusters deletion initiated.'
                    }
                }
            }
        }

        stage('Delete All Clusters') {
            when { expression { env.ACTION == 'DELETE_ALL' } }
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        def clusters = pmmHaRosa.listClusters([
                            region: env.REGION
                        ])

                        if (clusters.isEmpty()) {
                            echo "No clusters found with prefix '${env.CLUSTER_PREFIX}'."
                            return
                        }

                        // Sort by creation time (newest first) and optionally skip newest
                        clusters = clusters.toSorted { a, b -> b.createdAt <=> a.createdAt }

                        if (params.SKIP_NEWEST && clusters.size() > 1) {
                            def skipped = clusters[0]
                            echo "Skipping newest cluster: ${skipped.name} (created: ${skipped.createdAt})"
                            clusters = clusters.drop(1)
                        }

                        if (clusters.isEmpty()) {
                            echo 'No clusters to delete after applying filters.'
                            return
                        }

                        echo "Deleting ${clusters.size()} cluster(s)..."

                        clusters.each { cluster ->
                            if (cluster.state == 'uninstalling') {
                                echo "Skipping ${cluster.name} - already uninstalling"
                                return
                            }

                            echo "Deleting: ${cluster.name}"

                            // Delete Route53 record
                            try {
                                pmmHaRosa.deleteRoute53Record([
                                    domain: "${cluster.name}.${env.R53_ZONE_NAME}"
                                ])
                            } catch (Exception e) {
                                echo "Warning: Could not delete Route53 record: ${e.message}"
                            }

                            // Delete the cluster
                            try {
                                pmmHaRosa.deleteCluster([
                                    clusterName: cluster.name
                                ])
                                echo "Deleted: ${cluster.name}"
                            } catch (Exception e) {
                                echo "Error deleting ${cluster.name}: ${e.message}"
                            }
                        }

                        echo 'All clusters deletion completed.'
                    }
                }
            }
        }

        stage('Delete Old Clusters (cron only)') {
            when { expression { env.ACTION == 'DELETE_OLD' } }
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        def clusters = pmmHaRosa.listClusters([
                            region: env.REGION
                        ])

                        if (clusters.isEmpty()) {
                            echo "No clusters found with prefix '${env.CLUSTER_PREFIX}'."
                            return
                        }

                        def maxAgeHours = 24
                        def deletedCount = 0

                        echo "Checking ${clusters.size()} cluster(s) for age > ${maxAgeHours}h..."

                        clusters.each { cluster ->
                            if (cluster.state == 'uninstalling') {
                                echo "Skipping ${cluster.name} - already uninstalling"
                                return
                            }

                            def ageHours = pmmHaRosa.getClusterAgeHours(cluster.createdAt)

                            if (ageHours > maxAgeHours) {
                                echo "Deleting old cluster: ${cluster.name} (age: ${ageHours}h)"

                                // Delete Route53 record
                                try {
                                    pmmHaRosa.deleteRoute53Record([
                                        domain: "${cluster.name}.${env.R53_ZONE_NAME}"
                                    ])
                                } catch (Exception e) {
                                    echo "Warning: Could not delete Route53 record: ${e.message}"
                                }

                                // Delete the cluster
                                try {
                                    pmmHaRosa.deleteCluster([
                                        clusterName: cluster.name
                                    ])
                                    deletedCount++
                                    echo "Deleted: ${cluster.name}"
                                } catch (Exception e) {
                                    echo "Error deleting ${cluster.name}: ${e.message}"
                                }
                            } else {
                                echo "Skipping recent cluster: ${cluster.name} (age: ${ageHours}h < ${maxAgeHours}h)"
                            }
                        }

                        echo "Cleanup complete. Deleted ${deletedCount} cluster(s)."
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                // Final cluster count
                try {
                    withCredentials([
                        aws(credentialsId: 'pmm-staging-slave'),
                        string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                    ]) {
                        pmmHaRosa.login([token: env.ROSA_TOKEN])
                        def remaining = pmmHaRosa.listClusters([region: env.REGION])
                        echo "Remaining PMM HA ROSA clusters: ${remaining.size()}"
                    }
                } catch (Exception e) {
                    echo "Could not get final cluster count: ${e.message}"
                }
            }
        }
    }
}
