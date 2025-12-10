/**
 * PMM HA EKS Test Pipeline
 *
 * Creates an EKS cluster with PMM High Availability deployment for testing.
 * Access via kubectl port-forward for internal-only connectivity.
 *
 * Related:
 *   - Cleanup: pmm3-ha-eks-cleanup.groovy
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

    options {
        disableConcurrentBuilds()
        timeout(time: 90, unit: 'MINUTES')
    }

    parameters {
        choice(
            name: 'K8S_VERSION',
            choices: ['1.32', '1.33', '1.31', '1.30', '1.29'],
            description: 'Select Kubernetes cluster version'
        )
        booleanParam(
            name: 'INSTALL_PMM',
            defaultValue: true,
            description: 'Install PMM HA stack (uncheck for bare EKS cluster)'
        )
        // TODO: PMM HA charts not yet merged to percona/percona-helm-charts main.
        // theTibi/PMM-14420 has pmm-ha + pmm-ha-dependencies.
        // Once merged, update default to 'main' and swap repo priority.
        string(
            name: 'HELM_CHART_BRANCH',
            defaultValue: 'PMM-14420',
            description: 'Branch of percona-helm-charts repo (only used if INSTALL_PMM is checked)'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: '',
            description: 'PMM Server image tag (only used if INSTALL_PMM is checked)'
        )
        string(
            name: 'RETENTION_DAYS',
            defaultValue: '1',
            description: 'Days to retain cluster before auto-deletion by cleanup job (1-7, default: 1)'
        )
        password(
            name: 'PMM_ADMIN_PASSWORD',
            defaultValue: '',
            description: 'PMM admin password (only used if INSTALL_PMM is checked)'
        )
        string(
            name: 'PMM_NAMESPACE',
            defaultValue: 'pmm',
            description: 'Kubernetes namespace for PMM HA deployment'
        )
    }

    environment {
        CLUSTER_NAME = "${pmmHaEks.CLUSTER_PREFIX}${BUILD_NUMBER}"
        REGION = 'us-east-2'
        KUBECONFIG = "${WORKSPACE}/kubeconfig"
        PMM_NAMESPACE = "${params.PMM_NAMESPACE ?: 'pmm'}"
        INSTALL_PMM = "${params.INSTALL_PMM ? 'true' : 'false'}"
    }

    stages {
        stage('Write Cluster Config') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        env.VALIDATED_RETENTION_DAYS = eksCluster.validateRetentionDays(params.RETENTION_DAYS, pmmHaEks.MAX_RETENTION_DAYS)
                        echo "Retention: ${env.VALIDATED_RETENTION_DAYS} days"
                    }
                    sh '''
                    # Calculate delete-after timestamp (epoch seconds)
                    DELETE_AFTER_EPOCH=$(($(date +%s) + (VALIDATED_RETENTION_DAYS * 24 * 60 * 60)))
                    echo "Delete after: $(date -d @${DELETE_AFTER_EPOCH} 2>/dev/null || echo ${DELETE_AFTER_EPOCH})"

                    # Discover available AZs dynamically
                    AZS=$(aws ec2 describe-availability-zones --region "${REGION}" \
                        --query 'AvailabilityZones[?State==`available`].ZoneName' \
                        --output json)

                    cat > cluster-config.yaml <<EOF
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
  name: "${CLUSTER_NAME}"
  region: "${REGION}"
  version: "${K8S_VERSION}"
  tags:
    iit-billing-tag: "pmm"
    created-by: "jenkins"
    build-number: "${BUILD_NUMBER}"
    purpose: "pmm-ha-testing"
    retention-days: "${VALIDATED_RETENTION_DAYS}"
    delete-after: "${DELETE_AFTER_EPOCH}"
    install-pmm: "${INSTALL_PMM}"

vpc:
  nat:
    gateway: Single  # Single NAT gateway to conserve quota (5 per AZ limit)

accessConfig:
  authenticationMode: API

iam:
  withOIDC: true

addons:
  - name: aws-ebs-csi-driver
    wellKnownPolicies:
      ebsCSIController: true

managedNodeGroups:
  - name: ng-spot
    amiFamily: AmazonLinux2023
    instanceTypes:
      - m5a.xlarge
      - m5n.xlarge
      - m7a.xlarge
      - m7i-flex.xlarge
    volumeSize: 80
    spot: true
    minSize: 2
    maxSize: 5
    desiredCapacity: 4
    availabilityZones: ${AZS}
    tags:
        iit-billing-tag: "pmm"
        nodegroup: "spot"
    labels:
        workload: "pmm-ha-test"
EOF
                    '''
                }
            }
        }

        stage('Check Existing Clusters') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        def clusters = eksCluster.listClusters(region: env.REGION, prefix: pmmHaEks.CLUSTER_PREFIX)
                        def count = clusters.size()

                        if (clusters) {
                            echo "Existing clusters (${count}):"
                            clusters.each { echo "  - ${it}" }
                        }

                        if (count >= pmmHaEks.MAX_CLUSTERS) {
                            error("Maximum limit of ${pmmHaEks.MAX_CLUSTERS} test clusters reached.")
                        }

                        echo "Cluster count: ${count} / ${pmmHaEks.MAX_CLUSTERS}"
                    }
                }
            }
        }

        stage('Validate Helm Chart') {
            when { expression { params.INSTALL_PMM } }
            steps {
                script {
                    pmmHaEks.validateHelmChart(params.HELM_CHART_BRANCH)
                }
            }
        }

        stage('Create EKS Cluster') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        eksCluster.createCluster(configFile: 'cluster-config.yaml')
                    }
                }
            }
        }

        stage('Configure Cluster Access') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        eksCluster.configureAccess(
                            clusterName: env.CLUSTER_NAME,
                            region: env.REGION,
                            adminRoles: ['EKSAdminRole'],
                            adminGroupName: 'pmm-eks-admins',
                            discoverSSO: true
                        )
                    }
                }
            }
        }

        stage('Export kubeconfig') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        eksCluster.exportKubeconfig(
                            clusterName: env.CLUSTER_NAME,
                            region: env.REGION,
                            kubeconfigPath: env.KUBECONFIG
                        )
                    }
                    sh '''
                        kubectl cluster-info
                        kubectl get nodes
                    '''
                }
            }
        }

        stage('Setup Infrastructure') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        eksCluster.setupClusterComponents(
                            clusterName: env.CLUSTER_NAME,
                            region: env.REGION
                        )
                    }
                }
            }
        }

        stage('Install PMM HA') {
            when { expression { params.INSTALL_PMM } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        pmmHaEks.installPmm(
                            namespace: env.PMM_NAMESPACE,
                            chartBranch: params.HELM_CHART_BRANCH,
                            imageTag: params.PMM_IMAGE_TAG,
                            adminPassword: params.PMM_ADMIN_PASSWORD
                        )
                    }
                }
            }
        }

        stage('Cluster Summary') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        sh '''
                            set +x
                            echo "============================================"
                            echo "EKS Cluster Summary"
                            echo "============================================"
                            echo "Name:    ${CLUSTER_NAME}"
                            echo "Version: ${K8S_VERSION}"
                            echo "Region:  ${REGION}"
                            echo "Build:   ${BUILD_NUMBER}"
                            echo ""
                            kubectl get nodes -o wide
                            echo ""
                            kubectl get storageclass
                        '''

                        if (params.INSTALL_PMM) {
                            sh '''
                                echo ""
                                echo "============================================"
                                echo "PMM HA Summary"
                                echo "============================================"
                                echo "Namespace:         ${PMM_NAMESPACE}"
                                echo "Helm Chart Branch: ${HELM_CHART_BRANCH}"
                                echo ""
                                kubectl get pods -n "${PMM_NAMESPACE}"
                            '''

                            def result = pmmHaEks.writeAccessInfo(
                                clusterName: env.CLUSTER_NAME,
                                buildNumber: env.BUILD_NUMBER,
                                region: env.REGION,
                                namespace: env.PMM_NAMESPACE
                            )

                            echo """
============================================
Access Information
============================================
PMM Admin Password: ${result.creds.pmm}

kubectl:
  aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${REGION}
  kubectl port-forward svc/pmm-ha-haproxy 8443:443 -n ${PMM_NAMESPACE}
  # Then access https://localhost:8443
"""
                        } else {
                            echo """
============================================
Access Information (Bare EKS)
============================================
kubectl:
  aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${REGION}
"""
                        }
                    }
                }
            }
        }

        stage('Archive Artifacts') {
            steps {
                archiveArtifacts artifacts: 'kubeconfig', fingerprint: true
                archiveArtifacts artifacts: 'cluster-config.yaml', fingerprint: true
                script {
                    if (params.INSTALL_PMM) {
                        archiveArtifacts artifacts: 'pmm-credentials/access-info.txt', fingerprint: true
                    }
                }
            }
        }
    }

    post {
        success {
            withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                script {
                    if (params.INSTALL_PMM) {
                        def creds = pmmHaEks.getCredentials(env.PMM_NAMESPACE)
                        def chartRepo = sh(script: "cat .chart-repo-source 2>/dev/null || echo 'unknown'", returnStdout: true).trim()
                        currentBuild.description = "${CLUSTER_NAME} | admin / ${creds.pmm} | ${chartRepo}/${HELM_CHART_BRANCH}"
                    } else {
                        currentBuild.description = "${CLUSTER_NAME} (bare EKS)"
                    }
                }
            }
        }
        failure {
            withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                script {
                    if (eksCluster.clusterExists(clusterName: env.CLUSTER_NAME, region: env.REGION)) {
                        pmmHaEks.deleteCluster(clusterName: env.CLUSTER_NAME, region: env.REGION)
                    } else {
                        echo "Cluster ${CLUSTER_NAME} not found, nothing to clean up."
                    }
                }
            }
        }
    }
}
