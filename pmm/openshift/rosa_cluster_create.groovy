@Library('jenkins-pipelines') _

/**
 * Creates a ROSA HCP cluster with optional PMM deployment.
 *
 * This pipeline replaces the IPI-based openshift_cluster_create.groovy,
 * using ROSA HCP for faster provisioning (~15 min vs ~45 min).
 *
 * Parameters are defined inline (pure Groovy, no JJB YAML dependency).
 */

def attemptClusterCleanup(String reason) {
    if (env.FINAL_CLUSTER_NAME) {
        if (params.DEBUG_MODE) {
            echo "DEBUG MODE: Skipping automatic cleanup of ${reason} cluster"
            echo 'To manually clean up, run rosa-cluster-destroy with:'
            echo "  - Cluster Name: ${env.FINAL_CLUSTER_NAME}"
            echo "  - AWS Region: ${params.AWS_REGION}"
        } else {
            echo "Attempting to clean up ${reason} cluster resources..."
            def cleanupTimeout = reason == 'aborted' ? 2 : 10
            timeout(time: cleanupTimeout, unit: 'MINUTES') {
                try {
                    withCredentials([
                        aws(
                            credentialsId: 'jenkins-openshift-aws',
                            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                        ),
                        string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                    ]) {
                        openshiftRosa.login([token: env.ROSA_TOKEN, region: params.AWS_REGION])
                        openshiftRosa.deleteCluster([
                            clusterName: env.FINAL_CLUSTER_NAME,
                            region: params.AWS_REGION,
                            deleteVpc: true
                        ])
                    }
                    echo 'Cleanup completed successfully'
                } catch (Exception e) {
                    echo "Cleanup failed: ${e.toString()}"
                    if (reason == 'aborted') {
                        echo 'Manual cleanup may be required'
                    }
                }
            }
        }
    }
}

pipeline {
    agent { label 'agent-amd64-ol9' }

    environment {
        KUBECONFIG_DIR = "${WORKSPACE}/kubeconfig"
    }

    parameters {
        // Basic Configuration
        string(
            name: 'CLUSTER_NAME',
            defaultValue: 'test-cluster',
            description: 'Name for the ROSA HCP cluster (will be sanitized)'
        )
        choice(
            name: 'OPENSHIFT_VERSION',
            choices: ['4.18', '4.17', '4.16'],
            description: 'OpenShift version'
        )
        choice(
            name: 'AWS_REGION',
            choices: ['us-east-2'],
            description: 'AWS region'
        )

        // Compute Resources
        choice(
            name: 'WORKER_COUNT',
            choices: ['3', '2', '4', '5', '6'],
            description: 'Number of worker nodes'
        )
        choice(
            name: 'INSTANCE_TYPE',
            choices: ['m5.xlarge', 'm5.large', 'm5.2xlarge', 'm6i.xlarge', 'm6i.2xlarge'],
            description: 'EC2 instance type for worker nodes'
        )

        // PMM Deployment
        choice(
            name: 'PMM_MODE',
            choices: ['none', 'standalone', 'ha'],
            description: 'PMM deployment mode: none (cluster only), standalone (single instance), ha (high availability)'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: '3.3.1',
            description: 'PMM Server image tag'
        )
        string(
            name: 'PMM_IMAGE_REPOSITORY',
            defaultValue: 'percona/pmm-server',
            description: 'PMM Server image repository'
        )
        string(
            name: 'PMM_HELM_CHART_BRANCH',
            defaultValue: '',
            description: 'Helm chart branch from percona-helm-charts (leave empty to use released version)'
        )
        string(
            name: 'PMM_HELM_CHART_VERSION',
            defaultValue: '1.4.7',
            description: 'Helm chart version (used if branch is empty)'
        )
        string(
            name: 'PMM_ADMIN_PASSWORD',
            defaultValue: '<GENERATED>',
            description: 'PMM admin password (leave as <GENERATED> for auto-generation)'
        )

        // Advanced
        booleanParam(
            name: 'DEBUG_MODE',
            defaultValue: false,
            description: 'Skip cleanup on failure for debugging'
        )
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '100', daysToKeepStr: '30'))
        timeout(time: 90, unit: 'MINUTES')
        timestamps()
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    deleteDir()
                    sh "mkdir -p ${KUBECONFIG_DIR}"

                    // Set cluster name with build number if using default
                    if (params.CLUSTER_NAME == 'test-cluster') {
                        env.FINAL_CLUSTER_NAME = "test-cluster-${BUILD_NUMBER}"
                    } else {
                        env.FINAL_CLUSTER_NAME = params.CLUSTER_NAME
                    }

                    // Sanitize cluster name
                    env.FINAL_CLUSTER_NAME = env.FINAL_CLUSTER_NAME.toLowerCase().replaceAll(/[^a-z0-9-]/, '-')

                    echo "Final cluster name: ${env.FINAL_CLUSTER_NAME}"

                    // Set build display
                    def pmmStatus = params.PMM_MODE != 'none' ? "PMM-${params.PMM_MODE}:${params.PMM_IMAGE_TAG}" : "No-PMM"
                    currentBuild.description = "${env.FINAL_CLUSTER_NAME} | ROSA ${params.OPENSHIFT_VERSION} | ${params.AWS_REGION} | ${pmmStatus}"
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.FINAL_CLUSTER_NAME}"
                }
            }
        }

        stage('Install CLI Tools') {
            steps {
                script {
                    openshiftRosa.installRosaCli()
                    openshiftRosa.installOcCli([version: params.OPENSHIFT_VERSION])
                    openshiftTools.installHelm()
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

        stage('Pre-Creation Summary') {
            steps {
                script {
                    def summary = """
====================================================================
ROSA HCP Cluster Creation Summary
====================================================================

Cluster Name:         ${env.FINAL_CLUSTER_NAME}
OpenShift Version:    ${params.OPENSHIFT_VERSION}
AWS Region:           ${params.AWS_REGION}
Worker Nodes:         ${params.WORKER_COUNT} x ${params.INSTANCE_TYPE}
PMM Mode:             ${params.PMM_MODE}"""

                    if (params.PMM_MODE != 'none') {
                        summary += """
PMM Image:            ${params.PMM_IMAGE_REPOSITORY}:${params.PMM_IMAGE_TAG}"""
                    }

                    summary += """

====================================================================
"""
                    echo summary
                }
            }
        }

        stage('Check Existing Clusters') {
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

                        if (!clusters.isEmpty()) {
                            echo openshiftRosa.formatClustersSummary(clusters, "EXISTING ROSA CLUSTERS IN ${params.AWS_REGION}")

                            // Check for naming conflicts
                            def existingCluster = clusters.find { it.name == env.FINAL_CLUSTER_NAME }
                            if (existingCluster) {
                                error """
NAMING CONFLICT DETECTED!

A cluster named '${env.FINAL_CLUSTER_NAME}' already exists in ${params.AWS_REGION}.

Existing cluster details:
- State: ${existingCluster.state}
- Created at: ${existingCluster.createdAt}

Please choose a different cluster name or delete the existing cluster first.
"""
                            }
                        } else {
                            echo "No existing ROSA clusters found in ${params.AWS_REGION}"
                        }
                    }
                }
            }
        }

        stage('Create Cluster') {
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
                        def clusterInfo = openshiftRosa.createCluster([
                            clusterName: env.FINAL_CLUSTER_NAME,
                            region: params.AWS_REGION,
                            openshiftVersion: params.OPENSHIFT_VERSION,
                            replicas: params.WORKER_COUNT.toInteger(),
                            instanceType: params.INSTANCE_TYPE
                        ])

                        // Store cluster info in environment
                        env.CLUSTER_ID = clusterInfo.clusterId
                        env.CLUSTER_API_URL = clusterInfo.apiUrl
                        env.CLUSTER_CONSOLE_URL = clusterInfo.consoleUrl
                        env.CLUSTER_VERSION = clusterInfo.openshiftVersion
                    }
                }
            }
        }

        stage('Configure Access') {
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
                        def accessInfo = openshiftRosa.configureAccess([
                            clusterName: env.FINAL_CLUSTER_NAME,
                            kubeconfigPath: "${KUBECONFIG_DIR}/config",
                            region: params.AWS_REGION
                        ])

                        env.KUBECONFIG = accessInfo.kubeconfigPath
                        env.CLUSTER_ADMIN_PASSWORD = accessInfo.password
                    }
                }
            }
        }

        stage('Deploy PMM') {
            when { expression { params.PMM_MODE != 'none' } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        echo "Deploying PMM (${params.PMM_MODE} mode) to ROSA cluster..."

                        def pmmConfig = [
                            namespace: 'pmm',
                            imageTag: params.PMM_IMAGE_TAG,
                            imageRepository: params.PMM_IMAGE_REPOSITORY,
                            storageClass: 'gp3-csi'
                        ]

                        if (params.PMM_ADMIN_PASSWORD != '<GENERATED>') {
                            pmmConfig.adminPassword = params.PMM_ADMIN_PASSWORD
                        }

                        def pmmInfo
                        if (params.PMM_MODE == 'ha') {
                            // HA mode - use pmmHaRosa
                            if (params.PMM_HELM_CHART_BRANCH) {
                                pmmConfig.chartBranch = params.PMM_HELM_CHART_BRANCH
                            }
                            pmmInfo = pmmHaRosa.installPmm(pmmConfig)
                        } else {
                            // Standalone mode - use openshiftRosa
                            if (params.PMM_HELM_CHART_VERSION) {
                                pmmConfig.chartVersion = params.PMM_HELM_CHART_VERSION
                            }
                            pmmInfo = openshiftRosa.installPmmStandalone(pmmConfig)
                        }

                        // Get PMM service URL
                        env.PMM_URL = sh(
                            script: "oc get svc ${pmmInfo.serviceName} -n ${pmmConfig.namespace} -o jsonpath='https://{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo 'pending'",
                            returnStdout: true
                        ).trim()

                        env.PMM_PASSWORD = pmmInfo.adminPassword
                        env.PMM_PASSWORD_GENERATED = (params.PMM_ADMIN_PASSWORD == '<GENERATED>') ? 'true' : 'false'

                        echo "PMM deployed successfully: ${env.PMM_URL}"
                    }
                }
            }
        }

        stage('Post-Creation Summary') {
            steps {
                script {
                    def summary = """
====================================================================
ROSA HCP Cluster Created Successfully
====================================================================

PRIMARY ACCESS
--------------
Console URL:          ${env.CLUSTER_CONSOLE_URL}
API Endpoint:         ${env.CLUSTER_API_URL}"""

                    if (params.PMM_MODE != 'none' && env.PMM_URL) {
                        summary += """
PMM URL:              ${env.PMM_URL}"""
                    }

                    summary += """

CREDENTIALS
-----------
Cluster Admin:        cluster-admin
Cluster Password:     ${env.CLUSTER_ADMIN_PASSWORD}"""

                    if (params.PMM_MODE != 'none' && env.PMM_URL) {
                        def passwordDisplay = env.PMM_PASSWORD_GENERATED == 'true' ? env.PMM_PASSWORD : '*** (user-specified)'
                        summary += """
PMM Username:         admin
PMM Password:         ${passwordDisplay}"""
                    }

                    summary += """

CLUSTER DETAILS
---------------
Cluster Name:         ${env.FINAL_CLUSTER_NAME}
Cluster ID:           ${env.CLUSTER_ID}
OpenShift Version:    ${env.CLUSTER_VERSION}
AWS Region:           ${params.AWS_REGION}
Worker Nodes:         ${params.WORKER_COUNT} x ${params.INSTANCE_TYPE}

LOGIN COMMAND
-------------
oc login ${env.CLUSTER_API_URL} -u cluster-admin -p '${env.CLUSTER_ADMIN_PASSWORD}' --insecure-skip-tls-verify

====================================================================
"""
                    echo summary

                    // Update build description
                    def pmmStatus = env.PMM_URL ? "PMM: ${env.PMM_URL}" : "No PMM"
                    currentBuild.description = "${env.FINAL_CLUSTER_NAME} | ROSA ${env.CLUSTER_VERSION} | ${pmmStatus}"
                }
            }
        }

        stage('Archive Artifacts') {
            steps {
                script {
                    // Save cluster info to file for archiving
                    def clusterInfo = """
Cluster Name: ${env.FINAL_CLUSTER_NAME}
Cluster ID: ${env.CLUSTER_ID}
Console URL: ${env.CLUSTER_CONSOLE_URL}
API URL: ${env.CLUSTER_API_URL}
Admin User: cluster-admin
Admin Password: ${env.CLUSTER_ADMIN_PASSWORD}
"""
                    if (params.PMM_MODE != 'none' && env.PMM_URL) {
                        clusterInfo += """
PMM URL: ${env.PMM_URL}
PMM User: admin
PMM Password: ${env.PMM_PASSWORD}
"""
                    }

                    writeFile file: "${KUBECONFIG_DIR}/cluster-info.txt", text: clusterInfo

                    archiveArtifacts artifacts: 'kubeconfig/**', allowEmptyArchive: true
                }
            }
        }
    }

    post {
        failure {
            script {
                attemptClusterCleanup('failed')
            }
        }
        aborted {
            script {
                attemptClusterCleanup('aborted')
            }
        }
        always {
            cleanWs()
        }
    }
}
