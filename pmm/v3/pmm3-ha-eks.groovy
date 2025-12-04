/**
 * PMM HA EKS Test Pipeline
 *
 * Creates an EKS cluster with PMM High Availability deployment for testing.
 * Includes ALB ingress with ACM certificate and Route53 DNS.
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
        // PMM HA charts are not yet merged to percona/percona-helm-charts main branch.
        // theTibi/PMM-14420 contains both pmm-ha and pmm-ha-dependencies charts.
        // Once merged to percona main, update default to 'main' and swap repo priority.
        string(
            name: 'HELM_CHART_BRANCH',
            defaultValue: 'PMM-14420',
            description: 'Branch of percona-helm-charts repo (theTibi/PMM-14420 has both pmm-ha and pmm-ha-dependencies)'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: '',
            description: 'PMM Server image tag (leave empty for chart default)'
        )
        string(
            name: 'RETENTION_DAYS',
            defaultValue: '1',
            description: 'Days to retain cluster before auto-deletion by cleanup job (1-7, default: 1)'
        )
        password(
            name: 'PMM_ADMIN_PASSWORD',
            defaultValue: '',
            description: 'PMM admin password (leave empty for auto-generated 16-char password)'
        )
    }

    environment {
        CLUSTER_NAME = "${pmmHaEks.CLUSTER_PREFIX}${BUILD_NUMBER}"
        REGION = 'us-east-2'
        KUBECONFIG = "${WORKSPACE}/kubeconfig/config"
        PMM_NAMESPACE = 'pmm'
        ACM_CERT_ARN = 'arn:aws:acm:us-east-2:119175775298:certificate/9bd3a0c8-8205-4092-8003-7304ca762143'
        R53_ZONE_NAME = 'cd.percona.com'
        PMM_DOMAIN = "${pmmHaEks.CLUSTER_PREFIX}${BUILD_NUMBER}.${R53_ZONE_NAME}"
    }

    stages {
        stage('Write Cluster Config') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        env.VALIDATED_RETENTION_DAYS = pmmHaEks.validateRetentionDays(params.RETENTION_DAYS)
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
                        def clusters = pmmHaEks.listClusters(env.REGION)
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
            steps {
                script {
                    pmmHaEks.validateHelmChart(params.HELM_CHART_BRANCH)
                }
            }
        }

        stage('Create EKS Cluster') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        eksctl create cluster -f cluster-config.yaml --timeout=40m --verbose=4
                    '''
                }
            }
        }

        stage('Configure Cluster Access') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        pmmHaEks.configureAccess(
                            clusterName: env.CLUSTER_NAME,
                            region: env.REGION
                        )
                    }
                }
            }
        }

        stage('Export kubeconfig') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        rm -rf kubeconfig
                        mkdir -p kubeconfig

                        aws eks update-kubeconfig \
                            --name "${CLUSTER_NAME}" \
                            --region "${REGION}" \
                            --kubeconfig "${KUBECONFIG}"

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
                        pmmHaEks.setupInfrastructure(
                            clusterName: env.CLUSTER_NAME,
                            region: env.REGION
                        )
                    }
                }
            }
        }

        stage('Install PMM HA') {
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

        stage('Setup External Access') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        pmmHaEks.createIngress(
                            namespace: env.PMM_NAMESPACE,
                            domain: env.PMM_DOMAIN,
                            certArn: env.ACM_CERT_ARN,
                            r53ZoneName: env.R53_ZONE_NAME,
                            region: env.REGION
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
                            domain: env.PMM_DOMAIN,
                            namespace: env.PMM_NAMESPACE
                        )

                        echo """
============================================
Access Information
============================================
PMM URL: https://${PMM_DOMAIN}
ALB:     ${result.albHostname}
User:    admin
Password: ${result.creds.pmm}

kubectl:
  aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${REGION}
"""
                    }
                }
            }
        }

        stage('Archive Artifacts') {
            steps {
                archiveArtifacts artifacts: 'kubeconfig/config', fingerprint: true
                archiveArtifacts artifacts: 'cluster-config.yaml', fingerprint: true
                archiveArtifacts artifacts: 'pmm-credentials/access-info.txt', fingerprint: true
            }
        }
    }

    post {
        success {
            withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                script {
                    def creds = pmmHaEks.getCredentials(env.PMM_NAMESPACE)
                    def chartRepo = sh(script: "cat .chart-repo-source 2>/dev/null || echo 'unknown'", returnStdout: true).trim()

                    currentBuild.description = "https://${PMM_DOMAIN} | admin / ${creds.pmm} | ${chartRepo}/${HELM_CHART_BRANCH}"
                }
            }
        }
        failure {
            withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                script {
                    def clusterExists = sh(
                        script: "eksctl get cluster --region ${REGION} --name ${CLUSTER_NAME} >/dev/null 2>&1",
                        returnStatus: true
                    ) == 0

                    if (clusterExists) {
                        pmmHaEks.deleteCluster(
                            clusterName: env.CLUSTER_NAME,
                            region: env.REGION,
                            r53ZoneName: env.R53_ZONE_NAME
                        )
                    } else {
                        echo "Cluster ${CLUSTER_NAME} not found, nothing to clean up."
                    }
                }
            }
        }
    }
}
