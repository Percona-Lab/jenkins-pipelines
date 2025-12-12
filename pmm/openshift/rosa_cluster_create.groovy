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

        // PMM Deployment (optional)
        booleanParam(
            name: 'DEPLOY_PMM',
            defaultValue: false,
            description: 'Deploy PMM after cluster creation'
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

        // SSL Configuration
        booleanParam(
            name: 'ENABLE_SSL',
            defaultValue: false,
            description: 'Enable SSL/TLS certificates'
        )
        choice(
            name: 'SSL_METHOD',
            choices: ['letsencrypt', 'acm'],
            description: 'SSL certificate method'
        )
        string(
            name: 'SSL_EMAIL',
            defaultValue: 'cloud-dev-test@percona.com',
            description: 'Email for Let\'s Encrypt certificates'
        )
        booleanParam(
            name: 'USE_STAGING_CERT',
            defaultValue: false,
            description: 'Use Let\'s Encrypt staging (for testing)'
        )

        // Lifecycle
        string(
            name: 'DELETE_AFTER_HOURS',
            defaultValue: '8',
            description: 'Auto-delete after N hours (used by cleanup job)'
        )
        string(
            name: 'TEAM_NAME',
            defaultValue: 'cloud',
            description: 'Team name for billing/tracking'
        )
        string(
            name: 'PRODUCT_TAG',
            defaultValue: 'pmm',
            description: 'Product tag for resource tracking'
        )
        string(
            name: 'BASE_DOMAIN',
            defaultValue: 'cd.percona.com',
            description: 'Base domain for cluster URLs'
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
                    def pmmStatus = params.DEPLOY_PMM ? "PMM:${params.PMM_IMAGE_TAG}" : "No-PMM"
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

CLUSTER CONFIGURATION
---------------------
Cluster Name:         ${env.FINAL_CLUSTER_NAME}
OpenShift Version:    ${params.OPENSHIFT_VERSION}
AWS Region:           ${params.AWS_REGION}
Base Domain:          ${params.BASE_DOMAIN}

COMPUTE RESOURCES
-----------------
Worker Nodes:         ${params.WORKER_COUNT} x ${params.INSTANCE_TYPE}

PMM DEPLOYMENT
--------------
Deploy PMM:           ${params.DEPLOY_PMM ? 'Yes' : 'No'}"""

                    if (params.DEPLOY_PMM) {
                        summary += """
PMM Repository:       ${params.PMM_IMAGE_REPOSITORY}
PMM Image Tag:        ${params.PMM_IMAGE_TAG}"""
                        if (params.PMM_HELM_CHART_BRANCH) {
                            summary += """
Helm Chart Branch:    ${params.PMM_HELM_CHART_BRANCH}"""
                        } else {
                            summary += """
Helm Chart Version:   ${params.PMM_HELM_CHART_VERSION}"""
                        }
                        summary += """
Admin Password:       ${params.PMM_ADMIN_PASSWORD == '<GENERATED>' ? 'Auto-generated' : 'User-specified'}"""
                    }

                    summary += """

SSL CONFIGURATION
-----------------
Enable SSL:           ${params.ENABLE_SSL ? 'Yes' : 'No'}"""
                    if (params.ENABLE_SSL) {
                        summary += """
SSL Method:           ${params.SSL_METHOD}
Staging Cert:         ${params.USE_STAGING_CERT}"""
                    }

                    summary += """

LIFECYCLE
---------
Auto-delete after:    ${params.DELETE_AFTER_HOURS} hours
Team:                 ${params.TEAM_NAME}
Product Tag:          ${params.PRODUCT_TAG}

--------------------------------------------------------------------
Starting ROSA HCP cluster creation process...
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
            when { expression { params.DEPLOY_PMM } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        echo 'Deploying PMM to ROSA cluster...'

                        // Use pmmHaRosa for PMM deployment (has ECR and Helm logic)
                        def pmmConfig = [
                            namespace: 'pmm-monitoring',
                            imageTag: params.PMM_IMAGE_TAG,
                            imageRepository: params.PMM_IMAGE_REPOSITORY,
                            storageClass: 'gp3-csi'
                        ]

                        if (params.PMM_HELM_CHART_BRANCH) {
                            pmmConfig.chartBranch = params.PMM_HELM_CHART_BRANCH
                        }

                        if (params.PMM_ADMIN_PASSWORD != '<GENERATED>') {
                            pmmConfig.adminPassword = params.PMM_ADMIN_PASSWORD
                        }

                        def pmmInfo = pmmHaRosa.installPmm(pmmConfig)

                        env.PMM_PASSWORD = pmmInfo.adminPassword
                        env.PMM_PASSWORD_GENERATED = (params.PMM_ADMIN_PASSWORD == '<GENERATED>') ? 'true' : 'false'

                        // Create route with Route53 DNS
                        def routeInfo = pmmHaRosa.createRoute([
                            namespace: 'pmm-monitoring',
                            clusterName: env.FINAL_CLUSTER_NAME,
                            r53ZoneName: params.BASE_DOMAIN
                        ])

                        env.PMM_URL = routeInfo.url
                        env.PMM_DOMAIN = routeInfo.routeHost

                        echo "PMM deployed successfully: ${env.PMM_URL}"
                    }
                }
            }
        }

        stage('Configure SSL') {
            when { expression { params.ENABLE_SSL } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        echo "Configuring SSL certificates using ${params.SSL_METHOD}..."

                        def sslConfig = [
                            clusterName: env.FINAL_CLUSTER_NAME,
                            baseDomain: params.BASE_DOMAIN,
                            email: params.SSL_EMAIL,
                            useStaging: params.USE_STAGING_CERT,
                            kubeconfigPath: "${KUBECONFIG_DIR}/config"
                        ]

                        if (params.SSL_METHOD == 'letsencrypt') {
                            openshiftSSL.setupLetsEncrypt(sslConfig)
                            echo 'Let\'s Encrypt SSL configured'
                        } else if (params.SSL_METHOD == 'acm') {
                            awsCertificates.setupACM(sslConfig + [
                                accessKey: AWS_ACCESS_KEY_ID,
                                secretKey: AWS_SECRET_ACCESS_KEY
                            ])
                            echo 'ACM SSL configured'
                        }

                        env.SSL_CONFIGURED = 'true'
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

                    if (params.DEPLOY_PMM && env.PMM_URL) {
                        summary += """
PMM URL:              ${env.PMM_URL}"""
                    }

                    summary += """

CREDENTIALS
-----------
Cluster Admin:        cluster-admin
Cluster Password:     ${env.CLUSTER_ADMIN_PASSWORD}"""

                    if (params.DEPLOY_PMM && env.PMM_URL) {
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

LIFECYCLE
---------
Auto-Delete After:    ${params.DELETE_AFTER_HOURS} hours
Team:                 ${params.TEAM_NAME}

====================================================================
"""
                    echo summary

                    // Update build description
                    def pmmStatus = env.PMM_URL ? "PMM: ${env.PMM_URL}" : "No PMM"
                    currentBuild.description = "${env.FINAL_CLUSTER_NAME} | " +
                        "ROSA ${env.CLUSTER_VERSION} | " +
                        "${params.AWS_REGION} | " +
                        "Active | " +
                        "Console: ${env.CLUSTER_CONSOLE_URL} | " +
                        "${pmmStatus} | " +
                        "Workers: ${params.WORKER_COUNT}x${params.INSTANCE_TYPE} | " +
                        "Auto-delete: ${params.DELETE_AFTER_HOURS}h"
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
                    if (params.DEPLOY_PMM && env.PMM_URL) {
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
