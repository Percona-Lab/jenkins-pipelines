@Library('jenkins-pipelines') _

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    environment {
        // S3 bucket for storing cluster state
        S3_BUCKET = "openshift-clusters-${AWS_ACCOUNT_ID}-${AWS_REGION}"
        WORK_DIR = "${WORKSPACE}/openshift-clusters"
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
                    env.FINAL_CLUSTER_NAME = validateOpenShiftParams.sanitizeClusterName(env.FINAL_CLUSTER_NAME)

                    echo "Final cluster name: ${env.FINAL_CLUSTER_NAME}"
                }
            }
        }

        stage('Setup AWS Credentials') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        // Export AWS credentials for subsequent stages
                        env.AWS_ACCESS_KEY_ID = AWS_ACCESS_KEY_ID
                        env.AWS_SECRET_ACCESS_KEY = AWS_SECRET_ACCESS_KEY
                        env.AWS_DEFAULT_REGION = params.AWS_REGION
                        env.AWS_REGION = params.AWS_REGION

                        // Get AWS account ID
                        env.AWS_ACCOUNT_ID = sh(
                            script: 'aws sts get-caller-identity --query Account --output text',
                            returnStdout: true
                        ).trim()

                        // Update S3 bucket name with account ID
                        env.S3_BUCKET = "openshift-clusters-${AWS_ACCOUNT_ID}-${AWS_REGION}"
                    }
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

        stage('Create Cluster') {
            steps {
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
                        useSpotInstances: params.USE_SPOT_INSTANCES,
                        spotMaxPrice: params.SPOT_MAX_PRICE,
                        testMode: params.TEST_MODE,
                        deployPMM: params.DEPLOY_PMM,
                        pmmVersion: params.PMM_VERSION,
                        pmmAdminPassword: params.PMM_ADMIN_PASSWORD,
                        buildUser: env.BUILD_USER_ID ?: 'jenkins'
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
                    }

                    // Archive credentials if not in test mode
                    if (!params.TEST_MODE) {
                        archiveArtifacts artifacts: "${env.FINAL_CLUSTER_NAME}/auth/**", fingerprint: true
                    }
                }
            }
        }

        stage('Post-Creation Tasks') {
            when {
                expression { !params.TEST_MODE }
            }
            steps {
                script {
                    // Display cluster information
                    echo """
                    ========================================
                    Cluster Creation Summary
                    ========================================
                    Cluster Name: ${env.FINAL_CLUSTER_NAME}
                    OpenShift Version: ${params.OPENSHIFT_VERSION}
                    AWS Region: ${params.AWS_REGION}
                    API URL: ${env.CLUSTER_API_URL}
                    Console URL: ${env.CLUSTER_CONSOLE_URL}
                    S3 Backup: s3://${env.S3_BUCKET}/clusters/${env.FINAL_CLUSTER_NAME}/
                    Delete After: ${params.DELETE_AFTER_HOURS} hours
                    ${env.PMM_URL ? "PMM URL: ${env.PMM_URL}" : ''}
                    ========================================
                    """

                    // Set build description
                    currentBuild.description = "${env.FINAL_CLUSTER_NAME} - ${params.AWS_REGION}"

                    // Add build parameters as properties for easier tracking
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.FINAL_CLUSTER_NAME}"
                }
            }
        }
    }

    post {
        success {
            script {
                if (!params.TEST_MODE) {
                    echo "Cluster ${env.FINAL_CLUSTER_NAME} created successfully"

                    // Send notification if configured
                    if (env.SLACK_WEBHOOK) {
                        slackSend(
                            color: 'good',
                            message: "OpenShift cluster created: ${env.FINAL_CLUSTER_NAME} in ${params.AWS_REGION}"
                        )
                    }
                }
            }
        }
        failure {
            script {
                echo "Failed to create cluster ${env.FINAL_CLUSTER_NAME}"

                // Attempt cleanup if cluster creation failed
                if (env.FINAL_CLUSTER_NAME && !params.TEST_MODE) {
                    echo 'Attempting to clean up failed cluster resources...'
                    try {
                        destroyOpenShiftCluster([
                            clusterName: env.FINAL_CLUSTER_NAME,
                            awsRegion: params.AWS_REGION,
                            s3Bucket: env.S3_BUCKET,
                            workDir: env.WORK_DIR,
                            force: true
                        ])
                    } catch (Exception e) {
                        echo "Cleanup failed: ${e.message}"
                    }
                }

                // Send notification if configured
                if (env.SLACK_WEBHOOK) {
                    slackSend(
                        color: 'danger',
                        message: "Failed to create OpenShift cluster: ${env.FINAL_CLUSTER_NAME}"
                    )
                }
            }
        }
        always {
            script {
                // Clean up workspace but keep cluster state
                sh """
                    # Clean up temporary files
                    rm -f ${WORK_DIR}/ssh-public-key.pub || true

                    # Remove sensitive environment variables
                    unset AWS_ACCESS_KEY_ID
                    unset AWS_SECRET_ACCESS_KEY
                    unset OPENSHIFT_PULL_SECRET
                """
            }
        }
    }
}
