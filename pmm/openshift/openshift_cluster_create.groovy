// groovylint-disable-next-line UnusedVariable, VariableName
@Library('jenkins-pipelines') _

/**
 * Attempts to clean up OpenShift cluster resources when creation fails or is aborted.
 *
 * This method handles the destruction of partially created clusters to prevent
 * orphaned AWS resources. It respects DEBUG_MODE to allow troubleshooting
 * and implements different timeouts for different failure scenarios.
 *
 * @param reason The reason for cleanup: 'failed' or 'aborted'
 *               - 'failed': Normal failure during cluster creation (10 min timeout)
 *               - 'aborted': Manual job termination (2 min timeout due to Jenkins constraints)
 */
def attemptClusterCleanup(String reason) {
    if (env.FINAL_CLUSTER_NAME) {
        if (params.DEBUG_MODE) {
            echo "DEBUG MODE: Skipping automatic cleanup of ${reason} cluster"
            echo 'To manually clean up resources later, run the openshift-cluster-destroy job with:'
            echo "  - Cluster Name: ${env.FINAL_CLUSTER_NAME}"
            echo "  - AWS Region: ${params.AWS_REGION}"
            echo 'Resources will remain in AWS for debugging purposes'
        } else {
            echo "Attempting to clean up ${reason} cluster resources..."
            // Aborted jobs have limited time before Jenkins kills the cleanup
            if (reason == 'aborted') {
                echo 'Note: Cleanup has 2 minute timeout on abort'
            }

            def cleanupTimeout = reason == 'aborted' ? 2 : 10
            timeout(time: cleanupTimeout, unit: 'MINUTES') {
                try {
                    withCredentials([
                        aws(
                            credentialsId: 'jenkins-openshift-aws',
                            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                        )
                    ]) {
                        // For aborted jobs, only attempt cleanup if state files exist
                        // This prevents unnecessary destroy attempts for very early aborts
                        if (reason == 'aborted') {
                            def clusterDir = "${env.WORK_DIR}/${env.FINAL_CLUSTER_NAME}"
                            def hasMetadata = fileExists("${clusterDir}/metadata.json")
                            def hasTerraformState = fileExists("${clusterDir}/terraform.tfstate")

                            if (!hasMetadata && !hasTerraformState) {
                                echo 'No cluster state found - skipping cleanup'
                                return
                            }
                            echo 'Found cluster state files - attempting destroy'
                        }

                        openshiftCluster.destroy([
                            clusterName: env.FINAL_CLUSTER_NAME,
                            awsRegion: params.AWS_REGION,
                            s3Bucket: env.S3_BUCKET,
                            workDir: env.WORK_DIR,
                            accessKey: AWS_ACCESS_KEY_ID,
                            secretKey: AWS_SECRET_ACCESS_KEY,
                            reason: "${reason}-cleanup",
                            destroyedBy: env.BUILD_USER_ID ?: "jenkins-${reason}"
                        ])
                        echo 'Cleanup completed successfully'
                    }
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

/**
 * Archives cluster logs and state files for debugging purposes.
 *
 * This method collects all relevant files from a cluster creation attempt,
 * including OpenShift installer logs, Terraform state, metadata, and
 * authentication files. These artifacts are preserved in Jenkins for
 * post-mortem analysis.
 *
 * Files archived:
 * - OpenShift installer logs (*.log, .openshift_install.log)
 * - Log bundles (log-bundle-*.tar.gz)
 * - Terraform state files
 * - Cluster metadata JSON
 * - Install configuration backup
 * - Authentication files (kubeconfig, passwords)
 *
 * @return void
 */
def archiveClusterLogs() {
    if (env.FINAL_CLUSTER_NAME) {
        echo "Archiving cluster installation artifacts..."
        def clusterPath = "openshift-clusters/${env.FINAL_CLUSTER_NAME}"

        echo "  • Archiving log files"
        archiveArtifacts artifacts: "${clusterPath}/**/*.log", allowEmptyArchive: true

        echo "  • Archiving log bundles"
        archiveArtifacts artifacts: "${clusterPath}/**/log-bundle-*.tar.gz", allowEmptyArchive: true

        echo "  • Archiving Terraform state"
        archiveArtifacts artifacts: "${clusterPath}/terraform.tfstate", allowEmptyArchive: true

        echo "  • Archiving cluster metadata"
        archiveArtifacts artifacts: "${clusterPath}/metadata.json", allowEmptyArchive: true

        echo "  • Archiving OpenShift install log"
        archiveArtifacts artifacts: "${clusterPath}/.openshift_install.log", allowEmptyArchive: true

        echo "  • Archiving install configuration backup"
        archiveArtifacts artifacts: "${clusterPath}/install-config.yaml.backup", allowEmptyArchive: true

        echo "  • Archiving authentication files"
        archiveArtifacts artifacts: "${clusterPath}/auth/**", allowEmptyArchive: true
    }
}

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    environment {
        WORK_DIR = "${WORKSPACE}/openshift-clusters"
        S3_BUCKET = 'openshift-clusters-119175775298-us-east-2'
    }

    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '100', daysToKeepStr: '30'))
        timestamps()
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    // Clean workspace
                    deleteDir()

                    // Create work directory
                    sh "mkdir -p ${WORK_DIR}"

                    // Set cluster name with timestamp if needed
                    if (params.CLUSTER_NAME == 'test-cluster') {
                        env.FINAL_CLUSTER_NAME = "test-cluster-${BUILD_NUMBER}"
                    } else {
                        env.FINAL_CLUSTER_NAME = params.CLUSTER_NAME
                    }

                    // Sanitize cluster name
                    env.FINAL_CLUSTER_NAME = env.FINAL_CLUSTER_NAME.toLowerCase().replaceAll(/[^a-z0-9-]/, '-')

                    echo "Final cluster name: ${env.FINAL_CLUSTER_NAME}"
                }
            }
        }

        stage('Get Credentials') {
            steps {
                script {
                    // Get pull secret from credentials
                    withCredentials([
                        string(credentialsId: 'openshift-pull-secret', variable: 'PULL_SECRET')
                    ]) {
                        env.OPENSHIFT_PULL_SECRET = PULL_SECRET
                    }

                    // Get or generate SSH key
                    withCredentials([
                        sshUserPrivateKey(
                            credentialsId: 'openshift-ssh-key',
                            keyFileVariable: 'SSH_KEY_FILE'
                        )
                    ]) {
                        // Generate public key from private key
                        sh """
                            ssh-keygen -y -f ${SSH_KEY_FILE} > ${WORK_DIR}/ssh-public-key.pub
                        """
                        env.SSH_PUBLIC_KEY = readFile("${WORK_DIR}/ssh-public-key.pub").trim()
                    }
                }
            }
        }

        stage('Pre-Creation Summary') {
            steps {
                script {
                    // Display configuration choices before starting
                    def preCreationSummary = """
                    OpenShift Cluster Creation Summary
====================================================================

CLUSTER CONFIGURATION
---------------------
Cluster Name:         ${env.FINAL_CLUSTER_NAME}
OpenShift Version:    ${params.OPENSHIFT_VERSION}
AWS Region:           ${params.AWS_REGION}
Base Domain:          ${params.BASE_DOMAIN}

COMPUTE RESOURCES
-----------------
Master Nodes:         3 x ${params.MASTER_INSTANCE_TYPE}
Worker Nodes:         ${params.WORKER_COUNT} x ${params.WORKER_INSTANCE_TYPE}

PMM DEPLOYMENT
--------------
Deploy PMM:           ${params.DEPLOY_PMM ? 'Yes' : 'No'}"""

                    if (params.DEPLOY_PMM) {
                        preCreationSummary += """
PMM Repository:       ${params.PMM_IMAGE_REPOSITORY}
PMM Image Tag:        ${params.PMM_IMAGE_TAG}"""
                        if (params.PMM_HELM_CHART_BRANCH) {
                            preCreationSummary += """
Helm Chart Branch:    ${params.PMM_HELM_CHART_BRANCH} (from percona-helm-charts)"""
                        } else {
                            preCreationSummary += """
Helm Chart Version:   ${params.PMM_HELM_CHART_VERSION}"""
                        }
                        preCreationSummary += """
Admin Password:       ${params.PMM_ADMIN_PASSWORD == '<GENERATED>' ? 'Auto-generated' : 'User-specified'}"""
                    }

                    preCreationSummary += """

LIFECYCLE
---------
Auto-delete after:    ${params.DELETE_AFTER_HOURS} hours
Team:                 ${params.TEAM_NAME}
Product Tag:          ${params.PRODUCT_TAG}

--------------------------------------------------------------------
Starting cluster creation process...
                    """

                    echo preCreationSummary

                    // Set build description early with key details
                    def pmmStatus = params.DEPLOY_PMM ? "PMM:${params.PMM_IMAGE_TAG}" : "No-PMM"
                    currentBuild.description = "${env.FINAL_CLUSTER_NAME} | OCP:${params.OPENSHIFT_VERSION} | ${params.AWS_REGION} | ${pmmStatus}"
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.FINAL_CLUSTER_NAME}"
                }
            }
        }

        stage('List Existing Clusters') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        echo "Checking for existing clusters in ${params.AWS_REGION}..."

                        def clusters = openshiftCluster.list([
                            region: params.AWS_REGION,
                            accessKey: AWS_ACCESS_KEY_ID,
                            secretKey: AWS_SECRET_ACCESS_KEY
                        ])

                        if (!clusters.isEmpty()) {
                            // Use the shared formatting function for consistent output
                            def clusterSummary = openshiftTools.formatClustersSummary(
                                clusters,
                                "EXISTING OPENSHIFT CLUSTERS IN ${params.AWS_REGION}"
                            )
                            echo clusterSummary

                            // Check for potential naming conflicts
                            def existingCluster = clusters.find { it.name == env.FINAL_CLUSTER_NAME }
                            if (existingCluster) {
                                error """
                                NAMING CONFLICT DETECTED!

                                A cluster named '${env.FINAL_CLUSTER_NAME}' already exists in ${params.AWS_REGION}.

                                Existing cluster details:
                                - Created by: ${existingCluster.created_by}
                                - Created at: ${existingCluster.created_at}
                                - Status: ${openshiftTools.getClusterStatus(existingCluster)}

                                Please choose a different cluster name or delete the existing cluster first.
                                """
                            }
                        } else {
                            echo "No existing OpenShift clusters found in ${params.AWS_REGION}"
                        }
                    }
                }
            }
        }

        stage('Validate PMM Image') {
            when {
                expression { params.DEPLOY_PMM }
            }
            steps {
                script {
                    echo "Validating PMM image: ${params.PMM_IMAGE_REPOSITORY}:${params.PMM_IMAGE_TAG}"

                    // TEMPORARILY DISABLED: Image validation is skipped due to Jenkins agent issues
                    echo "[WARNING] PMM image validation is temporarily disabled due to Jenkins agent environment issues"
                    echo "Proceeding with image: ${params.PMM_IMAGE_REPOSITORY}:${params.PMM_IMAGE_TAG}"
                    echo "Please ensure the image exists before deployment to avoid Helm installation failures"

                    /* Commented out until Jenkins agent issues are resolved
                    // Validate the PMM image exists before creating the cluster
                    def imageValid = openshiftCluster.validatePMMImage([
                        pmmImageRepository: params.PMM_IMAGE_REPOSITORY,
                        pmmImageTag: params.PMM_IMAGE_TAG
                    ])

                    if (!imageValid) {
                        error """
                        PMM image validation failed!

                        Image not found: ${params.PMM_IMAGE_REPOSITORY}:${params.PMM_IMAGE_TAG}

                        Please verify:
                        1. The image tag is spelled correctly
                        2. The image exists in the repository
                        3. Check available tags at: https://hub.docker.com/r/${params.PMM_IMAGE_REPOSITORY}/tags

                        Common issues:
                        - Timestamp tags (like 202508040905) may not exist
                        - PR tags (like PR-1234) are in perconalab/pmm-server, not percona/pmm-server
                        - Development tags (like dev-latest) are in perconalab/pmm-server
                        """
                    }

                    echo "PMM image validated successfully"
                    */
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
                    )
                ]) {
                    script {
                        // Set debug mode if requested
                        if (params.DEBUG_MODE) {
                            env.OPENSHIFT_INSTALL_LOG_LEVEL = 'debug'
                        }

                        def clusterConfig = [
                            clusterName: env.FINAL_CLUSTER_NAME,
                            openshiftVersion: params.OPENSHIFT_VERSION,
                            awsRegion: params.AWS_REGION,
                            pullSecret: env.OPENSHIFT_PULL_SECRET,
                            sshPublicKey: env.SSH_PUBLIC_KEY,
                            s3Bucket: env.S3_BUCKET,
                            workDir: env.WORK_DIR,
                            baseDomain: params.BASE_DOMAIN,
                            masterType: params.MASTER_INSTANCE_TYPE,
                            workerType: params.WORKER_INSTANCE_TYPE,
                            workerCount: params.WORKER_COUNT.toInteger(),
                            deleteAfterHours: params.DELETE_AFTER_HOURS,
                            teamName: params.TEAM_NAME,
                            productTag: params.PRODUCT_TAG,
                            deployPMM: params.DEPLOY_PMM,
                            pmmImageTag: params.PMM_IMAGE_TAG,
                            pmmHelmChartVersion: params.PMM_HELM_CHART_VERSION,
                            pmmHelmChartBranch: params.PMM_HELM_CHART_BRANCH ?: '',
                            pmmImageRepository: params.PMM_IMAGE_REPOSITORY,
                            pmmAdminPassword: params.PMM_ADMIN_PASSWORD ?: '<GENERATED>',  // Default to auto-generation
                            // SSL Configuration
                            enableSSL: params.ENABLE_SSL,
                            sslMethod: params.SSL_METHOD,
                            sslEmail: params.SSL_EMAIL,
                            useStaging: params.USE_STAGING_CERT,
                            consoleCustomDomain: params.CONSOLE_CUSTOM_DOMAIN,
                            pmmCustomDomain: params.PMM_CUSTOM_DOMAIN,
                            buildUser: env.BUILD_USER_ID ?: 'jenkins',
                            accessKey: AWS_ACCESS_KEY_ID,
                            secretKey: AWS_SECRET_ACCESS_KEY
                        ]

                        // Create the cluster
                        def clusterInfo = openshiftCluster.create(clusterConfig)

                        // Store cluster info for post actions
                        env.CLUSTER_API_URL = clusterInfo.apiUrl
                        env.CLUSTER_CONSOLE_URL = clusterInfo.consoleUrl ?: ''
                        env.CLUSTER_DIR = clusterInfo.clusterDir
                        env.KUBECONFIG = clusterInfo.kubeconfig

                        if (clusterInfo.pmm) {
                            env.PMM_URL = clusterInfo.pmm.url
                            env.PMM_IP = clusterInfo.pmm.ip ?: 'N/A'
                            env.PMM_PASSWORD = clusterInfo.pmm.password
                            env.PMM_PASSWORD_GENERATED = clusterInfo.pmm.passwordGenerated.toString()
                        }

                        // Archive credentials with correct path
                        echo ""
                        echo "Archiving cluster credentials..."
                        def clusterPath = "openshift-clusters/${env.FINAL_CLUSTER_NAME}"
                        if (fileExists("${clusterPath}/auth")) {
                            echo "  ✓ Found auth directory at ${clusterPath}/auth"
                            echo "  ✓ Archiving kubeconfig and kubeadmin-password"
                            archiveArtifacts artifacts: "${clusterPath}/auth/**",
                                           fingerprint: true,
                                           allowEmptyArchive: false
                        } else {
                            error "Critical: Auth directory not found at ${clusterPath}/auth"
                        }
                    }
                }
            }
        }

        stage('Configure SSL Certificates') {
            when {
                expression { params.ENABLE_SSL && env.CLUSTER_DIR }
            }
            steps {
                script {
                    echo ""
                    echo "====================================================================="
                    echo "Configuring SSL Certificates"
                    echo "====================================================================="
                    echo ""
                    echo "SSL Method: ${params.SSL_METHOD}"
                    echo "Base Domain: ${params.BASE_DOMAIN}"

                    def sslConfig = [
                        clusterName: env.FINAL_CLUSTER_NAME,
                        baseDomain: params.BASE_DOMAIN,
                        kubeconfig: env.KUBECONFIG,
                        method: params.SSL_METHOD,
                        email: params.SSL_EMAIL,
                        useStaging: params.USE_STAGING_CERT
                    ]

                    def sslResults = [:]

                    if (params.SSL_METHOD == 'letsencrypt') {
                        echo "Setting up Let's Encrypt certificates..."

                        // Configure console domain
                        def consoleDomain = params.CONSOLE_CUSTOM_DOMAIN ?:
                            "console-${env.FINAL_CLUSTER_NAME}.${params.BASE_DOMAIN}"

                        sslConfig.consoleDomain = consoleDomain

                        // Setup Let's Encrypt
                        sslResults = openshiftSSL.setupLetsEncrypt(sslConfig)

                        if (sslResults.consoleCert) {
                            echo "✓ Console certificate configured for: ${consoleDomain}"
                            env.CONSOLE_SSL_DOMAIN = consoleDomain
                        }
                    } else if (params.SSL_METHOD == 'acm') {
                        echo "Setting up AWS ACM certificates..."

                        withCredentials([
                            aws(
                                credentialsId: 'jenkins-openshift-aws',
                                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                            )
                        ]) {
                            def services = []

                            // Add PMM service if deployed
                            if (params.DEPLOY_PMM && env.PMM_URL) {
                                def pmmDomain = params.PMM_CUSTOM_DOMAIN ?:
                                    "pmm-${env.FINAL_CLUSTER_NAME}.${params.BASE_DOMAIN}"

                                services.add([
                                    name: 'monitoring-service',
                                    namespace: 'pmm-monitoring',
                                    domain: pmmDomain
                                ])
                            }

                            sslConfig.services = services
                            sslConfig.accessKey = AWS_ACCESS_KEY_ID
                            sslConfig.secretKey = AWS_SECRET_ACCESS_KEY

                            sslResults = awsCertificates.setupACM(sslConfig)

                            if (sslResults.services) {
                                sslResults.services.each { name, config ->
                                    if (config.configured) {
                                        echo "✓ Service ${name} configured with ACM certificate"
                                        if (config.domain) {
                                            echo "  Domain: https://${config.domain}"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Store SSL results for post-creation display
                    env.SSL_CONFIGURED = sslResults ? 'true' : 'false'

                    if (sslResults.errors && !sslResults.errors.isEmpty()) {
                        echo "SSL configuration completed with warnings:"
                        sslResults.errors.each { error ->
                            echo "  ⚠ ${error}"
                        }
                    }
                }
            }
        }

        stage('Post-Creation Tasks') {
            steps {
                script {
                    // Display deployment status
                    echo "====================================================================="
                    echo "OpenShift Cluster Deployment Complete"
                    echo "====================================================================="
                    echo ""

                    // Primary access info (most important for QA)
                    echo "Console URL:         ${env.CLUSTER_CONSOLE_URL ?: 'https://console-openshift-console.apps.' + env.FINAL_CLUSTER_NAME + '.' + params.BASE_DOMAIN}"
                    if (params.DEPLOY_PMM && env.PMM_URL) {
                        echo "PMM URL:             ${env.PMM_URL}"
                        echo "PMM Public IP:       ${env.PMM_IP}"
                    }
                    echo ""

                    // Credentials
                    if (params.DEPLOY_PMM && env.PMM_URL) {
                        def passwordInfo = env.PMM_PASSWORD_GENERATED == 'true' ?
                            env.PMM_PASSWORD :
                            "*** (user-specified)"
                        echo "PMM Username:        admin"
                        echo "PMM Password:        ${passwordInfo}"
                    }
                    echo "Kubeadmin Password:  <check Jenkins artifacts: auth/kubeadmin-password>"
                    echo ""

                    // Version info (critical for testing)
                    if (params.DEPLOY_PMM && env.PMM_URL) {
                        echo "PMM Version:         ${params.PMM_IMAGE_TAG}"
                    }
                    echo "OpenShift Version:   ${params.OPENSHIFT_VERSION}"
                    echo ""

                    // Technical access details
                    echo "API Endpoint:        ${env.CLUSTER_API_URL}"
                    echo "Login Command:       oc login ${env.CLUSTER_API_URL} -u kubeadmin"
                    echo ""

                    // Cluster configuration
                    echo "Cluster Name:        ${env.FINAL_CLUSTER_NAME}"
                    echo "AWS Region:          ${params.AWS_REGION}"
                    echo "Status:              Active"
                    echo "Master Nodes:        3 × ${params.MASTER_INSTANCE_TYPE}"
                    echo "Worker Nodes:        ${params.WORKER_COUNT} × ${params.WORKER_INSTANCE_TYPE}"
                    echo ""

                    // Administrative info (least urgent)
                    echo "Team Owner:          ${params.TEAM_NAME}"
                    echo "Product Tag:         ${params.PRODUCT_TAG}"
                    echo "Auto-Delete After:   ${params.DELETE_AFTER_HOURS} hours"
                    echo "S3 Backup Location:  s3://${env.S3_BUCKET}/${env.FINAL_CLUSTER_NAME}/"

                    // PMM deployment details (if deployed)
                    if (params.DEPLOY_PMM && env.PMM_URL) {
                        echo ""
                        echo "PMM Deployment:"
                        echo "  Repository:        ${params.PMM_IMAGE_REPOSITORY}"
                        if (params.PMM_HELM_CHART_BRANCH) {
                            echo "  Helm Chart Branch: ${params.PMM_HELM_CHART_BRANCH} (from percona-helm-charts)"
                        } else {
                            echo "  Helm Chart:        ${params.PMM_HELM_CHART_VERSION}"
                        }
                        echo "  Namespace:         pmm-monitoring"
                    } else if (params.DEPLOY_PMM) {
                        echo ""
                        echo "PMM Status:          Not deployed (may have failed or is pending)"
                    }

                    echo ""
                    echo "===================================================================="

                    // Update build description - optimized for Blue Ocean (single line with pipe separators)
                    // Blue Ocean doesn't render HTML, so we use a clean single-line format
                    def pmmStatus = env.PMM_URL ? "PMM: ${env.PMM_URL}" : "No PMM"
                    def pmmDetails = ""
                    if (env.PMM_URL && env.PMM_IP) {
                        pmmDetails = " | PMM IP: ${env.PMM_IP}"
                        // Don't include password in description for security
                    }

                    currentBuild.description = "${env.FINAL_CLUSTER_NAME} | " +
                        "OCP ${params.OPENSHIFT_VERSION} | " +
                        "${params.AWS_REGION} | " +
                        "Active | " +
                        "Console: https://console-openshift-console.apps.${env.FINAL_CLUSTER_NAME}.${params.BASE_DOMAIN} | " +
                        "${pmmStatus}${pmmDetails} | " +
                        "Masters: 3×${params.MASTER_INSTANCE_TYPE} | " +
                        "Workers: ${params.WORKER_COUNT}×${params.WORKER_INSTANCE_TYPE} | " +
                        "Auto-delete: ${params.DELETE_AFTER_HOURS}h | " +
                        "Team: ${params.TEAM_NAME}"

                    // Keep display name consistent
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.FINAL_CLUSTER_NAME}"
                }
            }
        }
    }

    post {
        always {
            script {
                // Clean up workspace but keep cluster state
                sh """
                    # Clean up temporary files
                    rm -f ${WORK_DIR}/ssh-public-key.pub || true
                """

                // Clear sensitive environment variables
                env.OPENSHIFT_PULL_SECRET = ''
            }
        }
        success {
            script {
                echo "Cluster ${env.FINAL_CLUSTER_NAME} created successfully"
                echo ""

                // Archive critical authentication files
                echo "Archiving cluster authentication files..."
                if (env.WORK_DIR && env.FINAL_CLUSTER_NAME) {
                    def criticalFiles = [
                        "${env.WORK_DIR}/${env.FINAL_CLUSTER_NAME}/auth/kubeconfig",
                        "${env.WORK_DIR}/${env.FINAL_CLUSTER_NAME}/auth/kubeadmin-password"
                    ]
                    criticalFiles.each { file ->
                        if (fileExists(file)) {
                            def relativePath = file.replaceFirst("${env.WORKSPACE}/", '')
                            def fileName = file.split('/').last()
                            echo "  ✓ Archiving ${fileName}"
                            archiveArtifacts artifacts: relativePath,
                                           fingerprint: true,
                                           allowEmptyArchive: false
                        }
                    }
                }

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'good',
                        message: "OpenShift cluster created: ${env.FINAL_CLUSTER_NAME} in ${params.AWS_REGION}"
                    )
                }
            }
        }
        failure {
            script {
                // Update build description to show failure
                def failedStage = env.STAGE_NAME ?: 'Unknown'
                currentBuild.description = "${env.FINAL_CLUSTER_NAME ?: 'Unknown'} | " +
                    "OCP ${params.OPENSHIFT_VERSION} | " +
                    "${params.AWS_REGION} | " +
                    "FAILED: ${failedStage}"

                // Display failure summary
                def failureSummary = """
                    OpenShift Cluster Creation Failed
====================================================================

DEPLOYMENT STATUS: FAILED

ATTEMPTED CONFIGURATION
-----------------------
Cluster Name:         ${env.FINAL_CLUSTER_NAME ?: 'Not set'}
OpenShift Version:    ${params.OPENSHIFT_VERSION}
AWS Region:           ${params.AWS_REGION}

Failure Stage:        ${failedStage}"""

                if (params.DEPLOY_PMM && env.STAGE_NAME?.contains('PMM')) {
                    failureSummary += """

PMM CONFIGURATION ATTEMPTED
---------------------------
Repository:           ${params.PMM_IMAGE_REPOSITORY}
Image Tag:            ${params.PMM_IMAGE_TAG}
Possible Issue:       Image may not exist or network error"""
                }

                failureSummary += """

--------------------------------------------------------------------
Cleanup Status:       Attempting automatic cleanup...
Logs:                 Available in Jenkins artifacts
                """

                echo failureSummary

                // Archive failure logs
                archiveClusterLogs()

                // Attempt cleanup
                attemptClusterCleanup('failed')

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'danger',
                        message: "Failed to create OpenShift cluster: ${env.FINAL_CLUSTER_NAME}"
                    )
                }
            }
        }
        aborted {
            script {
                // Update build description to show aborted
                currentBuild.description = "${env.FINAL_CLUSTER_NAME ?: 'Unknown'} | " +
                    "OCP ${params.OPENSHIFT_VERSION} | " +
                    "${params.AWS_REGION} | " +
                    "ABORTED"

                echo 'Job was manually terminated - attempting to clean up cluster resources'

                if (env.FINAL_CLUSTER_NAME) {
                    echo "Detected partially created cluster: ${env.FINAL_CLUSTER_NAME}"

                    // Archive any logs that were created before abort
                    archiveClusterLogs()

                    // Attempt cleanup
                    attemptClusterCleanup('aborted')
                }

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'warning',
                        message: "OpenShift cluster creation aborted: ${env.FINAL_CLUSTER_NAME}. Cleanup attempted."
                    )
                }
            }
        }
    }
}
