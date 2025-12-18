@Library('jenkins-pipelines@feature/pmm-ha-rosa') _

/**
 * PMM HA on ROSA HCP - Creates a ROSA cluster and deploys PMM in HA mode.
 *
 * This pipeline is specifically designed for PMM High Availability testing on
 * Red Hat OpenShift Service on AWS (ROSA) with Hosted Control Planes (HCP).
 *
 * Features:
 * - Fast cluster provisioning (~15 min via ROSA HCP)
 * - PMM HA deployment via Helm
 * - ECR pull-through cache support (avoids Docker Hub rate limits)
 * - Automatic cleanup on failure (unless DEBUG_MODE enabled)
 */

def attemptClusterCleanup(String reason) {
    if (env.FINAL_CLUSTER_NAME) {
        if (params.DEBUG_MODE) {
            echo "DEBUG MODE: Skipping automatic cleanup of ${reason} cluster"
            echo 'To manually clean up, run pmm3-ha-rosa-cleanup with:'
            echo "  - CLUSTER_NAMES: ${env.FINAL_CLUSTER_NAME}"
            echo '  - ACTION: DELETE_NAMED'
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
                        openshiftRosa.login([token: env.ROSA_TOKEN, region: 'us-east-2'])
                        openshiftRosa.deleteCluster([
                            clusterName: env.FINAL_CLUSTER_NAME,
                            region: 'us-east-2',
                            deleteVpc: true
                        ])
                    }
                    echo 'Cleanup completed successfully'
                } catch (Exception e) {
                    echo "Cleanup failed: ${e.toString()}"
                    echo 'Manual cleanup may be required via pmm3-ha-rosa-cleanup job'
                }
            }
        }
    }
}

pipeline {
    agent { label 'agent-amd64-ol9' }

    environment {
        KUBECONFIG_DIR = "${WORKSPACE}/kubeconfig"
        AWS_REGION = 'us-east-2'
    }

    parameters {
        choice(
            name: 'OPENSHIFT_VERSION',
            choices: ['4.16', '4.17', '4.18'],
            description: 'OpenShift version for ROSA HCP cluster'
        )
        choice(
            name: 'REPLICAS',
            choices: ['3', '4', '5'],
            description: 'Number of worker nodes'
        )
        choice(
            name: 'INSTANCE_TYPE',
            choices: ['m5.xlarge', 'm5.large', 'm5.2xlarge'],
            description: 'EC2 instance type for worker nodes'
        )
        string(
            name: 'HELM_CHART_BRANCH',
            defaultValue: 'PMM-14420',
            description: 'Branch of percona-helm-charts repo for PMM HA chart'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: '',
            description: 'PMM Server image tag (leave empty for chart default)'
        )
        string(
            name: 'PMM_IMAGE_REPOSITORY',
            defaultValue: '',
            description: 'PMM Server image repository (leave empty for chart default)'
        )
        booleanParam(
            name: 'DEBUG_MODE',
            defaultValue: true,
            description: 'Skip cleanup on failure for debugging'
        )
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '100', daysToKeepStr: '30'))
        timeout(time: 60, unit: 'MINUTES')
        timestamps()
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    deleteDir()
                    sh "mkdir -p ${KUBECONFIG_DIR}"

                    // Generate cluster name with build number
                    env.FINAL_CLUSTER_NAME = "pmm-ha-rosa-${BUILD_NUMBER}"

                    echo "Cluster name: ${env.FINAL_CLUSTER_NAME}"

                    // Set build display
                    def imageDisplay = params.PMM_IMAGE_TAG ?: 'chart-default'
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.FINAL_CLUSTER_NAME}"
                    currentBuild.description = "ROSA ${params.OPENSHIFT_VERSION} | PMM HA | ${imageDisplay}"
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
                            region: env.AWS_REGION
                        ])
                    }
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
                        def clusters = openshiftRosa.listClusters([region: env.AWS_REGION])
                        def pmmHaClusters = clusters.findAll { it.name.startsWith('pmm-ha-rosa-') }

                        if (!pmmHaClusters.isEmpty()) {
                            echo openshiftRosa.formatClustersSummary(pmmHaClusters, 'EXISTING PMM HA ROSA CLUSTERS')
                        } else {
                            echo 'No existing PMM HA ROSA clusters found'
                        }

                        // Check cluster quota
                        pmmHaRosa.checkClusterLimit([
                            currentClusters: pmmHaClusters,
                            maxClusters: 5
                        ])
                    }
                }
            }
        }

        stage('Create ROSA HCP Cluster') {
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
                            region: env.AWS_REGION,
                            openshiftVersion: params.OPENSHIFT_VERSION,
                            replicas: params.REPLICAS.toInteger(),
                            instanceType: params.INSTANCE_TYPE
                        ])

                        env.CLUSTER_ID = clusterInfo.clusterId
                        env.CLUSTER_API_URL = clusterInfo.apiUrl
                        env.CLUSTER_CONSOLE_URL = clusterInfo.consoleUrl
                        env.CLUSTER_VERSION = clusterInfo.openshiftVersion
                    }
                }
            }
        }

        stage('Configure kubectl Access') {
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
                            region: env.AWS_REGION
                        ])

                        env.KUBECONFIG = accessInfo.kubeconfigPath
                        env.CLUSTER_ADMIN_PASSWORD = accessInfo.password
                    }
                }
            }
        }

        stage('Install PMM HA Dependencies') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        // Clone Helm charts repo
                        echo "Cloning percona-helm-charts branch: ${params.HELM_CHART_BRANCH}"
                        sh """
                            rm -rf percona-helm-charts
                            git clone --depth 1 -b ${params.HELM_CHART_BRANCH} https://github.com/percona/percona-helm-charts.git percona-helm-charts || \
                            git clone --depth 1 -b ${params.HELM_CHART_BRANCH} https://github.com/theTibi/percona-helm-charts.git percona-helm-charts
                            ls -la percona-helm-charts/charts/
                        """

                        // Install pmm-ha-dependencies chart first (etcd, pg, valkey)
                        echo 'Installing PMM HA dependencies (etcd, PostgreSQL, Valkey)...'
                        sh """
                            export PATH=\$HOME/.local/bin:\$PATH

                            # Add required helm repos
                            helm repo add percona https://percona.github.io/percona-helm-charts/ || true
                            helm repo add victoriametrics https://victoriametrics.github.io/helm-charts/ || true
                            helm repo add altinity https://helm.altinity.com || true
                            helm repo update

                            helm dependency build percona-helm-charts/charts/pmm-ha-dependencies
                            helm upgrade --install pmm-ha-deps percona-helm-charts/charts/pmm-ha-dependencies \
                                --namespace pmm --create-namespace \
                                --wait --timeout 10m
                        """
                    }
                }
            }
        }

        stage('Install PMM HA') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ),
                    usernamePassword(
                        credentialsId: 'dockerHub',
                        usernameVariable: 'DOCKERHUB_USER',
                        passwordVariable: 'DOCKERHUB_PASSWORD'
                    )
                ]) {
                    script {
                        echo 'Installing PMM HA on ROSA cluster'
                        echo '  Namespace: pmm'
                        echo "  Chart Branch: ${params.HELM_CHART_BRANCH}"
                        def imageDisplay = params.PMM_IMAGE_TAG ? "${params.PMM_IMAGE_REPOSITORY ?: 'default'}:${params.PMM_IMAGE_TAG}" : 'chart-default'
                        echo "  Image: ${imageDisplay}"

                        // Create namespace and SCC
                        sh '''
                            export PATH=$HOME/.local/bin:$PATH
                            oc create namespace pmm 2>/dev/null || true

                            cat <<EOF | oc apply -f -
apiVersion: security.openshift.io/v1
kind: SecurityContextConstraints
metadata:
  name: pmm-anyuid
allowHostDirVolumePlugin: false
allowHostIPC: false
allowHostNetwork: false
allowHostPID: false
allowHostPorts: false
allowPrivilegeEscalation: true
allowPrivilegedContainer: false
allowedCapabilities: null
defaultAddCapabilities: null
fsGroup:
  type: RunAsAny
groups: []
priority: null
readOnlyRootFilesystem: false
requiredDropCapabilities:
  - MKNOD
runAsUser:
  type: RunAsAny
seLinuxContext:
  type: MustRunAs
supplementalGroups:
  type: RunAsAny
users:
  - system:serviceaccount:pmm:default
  - system:serviceaccount:pmm:pmm-ha
volumes:
  - configMap
  - downwardAPI
  - emptyDir
  - persistentVolumeClaim
  - projected
  - secret
EOF
                            echo "Custom SCC 'pmm-anyuid' created for PMM HA workloads"
                        '''

                        // Generate password and create secret
                        env.PMM_ADMIN_PASSWORD = pmmHaRosa.generatePassword()
                        sh """
                            export PATH=\$HOME/.local/bin:\$PATH
                            oc delete secret pmm-secret -n pmm 2>/dev/null || true
                            oc create secret generic pmm-secret -n pmm \
                                --from-literal=PMM_ADMIN_PASSWORD=${env.PMM_ADMIN_PASSWORD} \
                                --from-literal=PMM_CLICKHOUSE_USER=clickhouse_pmm \
                                --from-literal=PMM_CLICKHOUSE_PASSWORD=${env.PMM_ADMIN_PASSWORD} \
                                --from-literal=VMAGENT_remoteWrite_basicAuth_username=victoriametrics_pmm \
                                --from-literal=VMAGENT_remoteWrite_basicAuth_password=${env.PMM_ADMIN_PASSWORD} \
                                --from-literal=PG_PASSWORD=${env.PMM_ADMIN_PASSWORD} \
                                --from-literal=GF_PASSWORD=${env.PMM_ADMIN_PASSWORD}
                            echo "Pre-created pmm-secret with all required keys"
                        """

                        // Configure Docker Hub pull secret
                        sh '''
                            export PATH=$HOME/.local/bin:$PATH
                            oc create secret docker-registry dockerhub-secret \
                                --docker-server=docker.io \
                                --docker-username="$DOCKERHUB_USER" \
                                --docker-password="$DOCKERHUB_PASSWORD" \
                                -n pmm 2>/dev/null || true
                            oc secrets link default dockerhub-secret --for=pull -n pmm 2>/dev/null || true
                        '''

                        // Build helm args
                        def helmArgs = [
                            'helm upgrade --install pmm-ha percona-helm-charts/charts/pmm-ha',
                            '--namespace pmm',
                            '--set service.type=LoadBalancer',
                            '--wait --timeout 15m'
                        ]

                        if (params.PMM_IMAGE_TAG) {
                            helmArgs.add("--set image.tag=${params.PMM_IMAGE_TAG}")
                        }
                        if (params.PMM_IMAGE_REPOSITORY) {
                            helmArgs.add("--set image.repository=${params.PMM_IMAGE_REPOSITORY}")
                        }

                        sh """
                            export PATH=\$HOME/.local/bin:\$PATH
                            helm dependency build percona-helm-charts/charts/pmm-ha || true
                            ${helmArgs.join(' \\\n                                ')}
                        """

                        // Get PMM URL
                        def pmmUrl = sh(
                            script: '''
                                export PATH=$HOME/.local/bin:$PATH
                                for i in {1..30}; do
                                    URL=$(oc get svc pmm-ha -n pmm -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)
                                    if [ -n "$URL" ]; then
                                        echo "https://$URL"
                                        exit 0
                                    fi
                                    sleep 10
                                done
                                echo "pending"
                            ''',
                            returnStdout: true
                        ).trim()

                        env.PMM_URL = pmmUrl
                        echo "PMM HA deployed: ${env.PMM_URL}"
                    }
                }
            }
        }

        stage('Cluster Summary') {
            steps {
                script {
                    def summary = """
====================================================================
PMM HA ON ROSA - DEPLOYMENT COMPLETE
====================================================================

CLUSTER ACCESS
--------------
Console URL:          ${env.CLUSTER_CONSOLE_URL}
API Endpoint:         ${env.CLUSTER_API_URL}
Cluster Admin:        cluster-admin
Cluster Password:     ${env.CLUSTER_ADMIN_PASSWORD}

PMM ACCESS
----------
PMM URL:              ${env.PMM_URL}
PMM Username:         admin
PMM Password:         ${env.PMM_ADMIN_PASSWORD}

CLUSTER DETAILS
---------------
Cluster Name:         ${env.FINAL_CLUSTER_NAME}
Cluster ID:           ${env.CLUSTER_ID}
OpenShift Version:    ${env.CLUSTER_VERSION}
AWS Region:           ${env.AWS_REGION}
Worker Nodes:         ${params.REPLICAS} x ${params.INSTANCE_TYPE}

LOGIN COMMANDS
--------------
# OpenShift CLI
oc login ${env.CLUSTER_API_URL} -u cluster-admin -p '${env.CLUSTER_ADMIN_PASSWORD}' --insecure-skip-tls-verify

# kubectl
export KUBECONFIG=${env.KUBECONFIG}
kubectl get pods -n pmm

====================================================================
"""
                    echo summary

                    // Update build description with PMM URL
                    currentBuild.description = "${env.FINAL_CLUSTER_NAME} | ROSA ${env.CLUSTER_VERSION} | PMM: ${env.PMM_URL}"
                }
            }
        }

        stage('Archive Artifacts') {
            steps {
                script {
                    def clusterInfo = """
Cluster Name: ${env.FINAL_CLUSTER_NAME}
Cluster ID: ${env.CLUSTER_ID}
Console URL: ${env.CLUSTER_CONSOLE_URL}
API URL: ${env.CLUSTER_API_URL}
Admin User: cluster-admin
Admin Password: ${env.CLUSTER_ADMIN_PASSWORD}

PMM URL: ${env.PMM_URL}
PMM User: admin
PMM Password: ${env.PMM_ADMIN_PASSWORD}
"""
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
            deleteDir()
        }
    }
}
