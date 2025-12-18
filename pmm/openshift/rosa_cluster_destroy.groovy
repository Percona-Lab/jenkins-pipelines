@Library('jenkins-pipelines@feature/pmm-ha-rosa') _

def openshiftRosa

/**
 * Destroys a ROSA HCP cluster.
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
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
        timeout(time: 30, unit: 'MINUTES')
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

        stage('Validate') {
            steps {
                script {
                    if (!params.CLUSTER_NAME?.trim()) {
                        error 'CLUSTER_NAME is required'
                    }
                    env.TARGET_CLUSTER = params.CLUSTER_NAME.trim()
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.TARGET_CLUSTER}"
                    currentBuild.description = "${params.DRY_RUN ? '[DRY RUN] ' : ''}Destroying ${env.TARGET_CLUSTER}"
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

        stage('Check Cluster') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        def clusters = openshiftRosa.listClusters([region: params.AWS_REGION])
                        def targetCluster = clusters.find { it.name == env.TARGET_CLUSTER }

                        if (targetCluster) {
                            echo """
====================================================================
CLUSTER FOUND
====================================================================
Name:    ${targetCluster.name}
ID:      ${targetCluster.id}
State:   ${targetCluster.state}
Version: ${targetCluster.version}
Age:     ${openshiftRosa.getClusterAgeHours(targetCluster.createdAt)} hours
====================================================================
"""
                            env.CLUSTER_ID = targetCluster.id
                            env.CLUSTER_STATE = targetCluster.state
                        } else {
                            error "Cluster '${env.TARGET_CLUSTER}' not found"
                        }
                    }
                }
            }
        }

        stage('Dry Run') {
            when { expression { params.DRY_RUN } }
            steps {
                script {
                    echo """
====================================================================
DRY RUN - Would delete:
- ROSA Cluster: ${env.TARGET_CLUSTER}
- VPC Stack: ${env.TARGET_CLUSTER}-vpc
- Operator Roles
====================================================================
Run again with DRY_RUN=false to proceed.
"""
                }
            }
        }

        stage('Delete Cluster') {
            when { expression { !params.DRY_RUN } }
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

                        openshiftRosa.deleteCluster([
                            clusterName: env.TARGET_CLUSTER,
                            region: params.AWS_REGION,
                            deleteOidc: false,
                            deleteOperatorRoles: true,
                            deleteVpc: true
                        ])

                        echo 'Cluster deletion initiated'
                        currentBuild.description = "Deleted ${env.TARGET_CLUSTER}"
                    }
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
