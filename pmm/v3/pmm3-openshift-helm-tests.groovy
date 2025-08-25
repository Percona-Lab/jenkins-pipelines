library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def kubeconfigPath = null

/**
 * Lists available OpenShift clusters from S3
 */
def listAvailableClusters() {
    def clusters = []
    try {
        withCredentials([
            [$class: 'AmazonWebServicesCredentialsBinding',
             credentialsId: 'jenkins-openshift-aws',
             accessKeyVariable: 'AWS_ACCESS_KEY_ID',
             secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
        ]) {
            def result = sh(
                script: """
                    aws s3 ls s3://openshift-clusters-119175775298-us-east-2/ --region us-east-2 2>/dev/null | 
                    grep PRE | awk '{print \$2}' | sed 's/\\///' | sort
                """,
                returnStdout: true
            ).trim()
            
            if (result) {
                clusters = result.split('\n').toList()
            }
        }
    } catch (Exception e) {
        echo "Failed to list clusters from S3: ${e.message}"
    }
    return clusters
}

/**
 * Downloads kubeconfig from S3 for existing cluster
 */
def downloadKubeconfigFromS3(String clusterName) {
    echo "Downloading kubeconfig for cluster: ${clusterName}"
    
    withCredentials([
        [$class: 'AmazonWebServicesCredentialsBinding',
         credentialsId: 'jenkins-openshift-aws',
         accessKeyVariable: 'AWS_ACCESS_KEY_ID',
         secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
    ]) {
        sh """
            # Download auth backup from S3
            aws s3 cp s3://openshift-clusters-119175775298-us-east-2/${clusterName}/auth-backup.tar.gz \
                ${WORKSPACE}/auth-backup.tar.gz \
                --region us-east-2
            
            # Extract kubeconfig
            mkdir -p ${WORKSPACE}/cluster-auth
            tar -xzf ${WORKSPACE}/auth-backup.tar.gz -C ${WORKSPACE}/cluster-auth
            rm -f ${WORKSPACE}/auth-backup.tar.gz
            
            # Verify kubeconfig exists
            if [ ! -f "${WORKSPACE}/cluster-auth/auth/kubeconfig" ]; then
                echo "ERROR: kubeconfig not found in downloaded archive"
                exit 1
            fi
            
            echo "Successfully downloaded kubeconfig from S3"
        """
    }
    
    return "${WORKSPACE}/cluster-auth/auth/kubeconfig"
}

pipeline {
    agent {
        label 'min-noble-x64'
    }
    parameters {
        string(
            name: 'CLUSTER_NAME',
            defaultValue: '',
            description: 'Name of existing OpenShift cluster from S3 (leave empty to see available clusters)'
        )
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH'
        )
        string(
            defaultValue: 'perconalab/pmm-server',
            description: 'PMM Server image repository',
            name: 'IMAGE_REPO'
        )
        string(
            defaultValue: '3-dev-latest',
            description: 'PMM Server image tag',
            name: 'IMAGE_TAG'
        )
        string(
            defaultValue: 'percona/pmm-server',
            description: 'Helm chart repository',
            name: 'HELM_CHART'
        )
        string(
            defaultValue: 'latest',
            description: 'Helm chart version (use "latest" for most recent)',
            name: 'CHART_VERSION'
        )
        string(
            defaultValue: 'pmm',
            description: 'Namespace to deploy PMM',
            name: 'NAMESPACE'
        )
        booleanParam(
            defaultValue: false,
            description: 'Run helm upgrade test after initial install',
            name: 'TEST_UPGRADE'
        )
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare Environment') {
            steps {
                script {
                    currentBuild.description = "Cluster: ${params.CLUSTER_NAME ?: 'TBD'}. Image: ${params.IMAGE_REPO}:${params.IMAGE_TAG}"
                }
                deleteDir()
                git poll: false, branch: params.PMM_QA_GIT_BRANCH, url: 'https://github.com/percona/pmm-qa.git'

                sh """
                    # Install test dependencies
                    sudo mkdir -p /srv/pmm-qa || :
                    sudo git clone --single-branch --branch ${params.PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git /srv/pmm-qa
                    sudo chmod -R 755 /srv/pmm-qa

                    # Install BATS testing framework
                    sudo mkdir -p /opt/bats || :
                    sudo git clone https://github.com/bats-core/bats-core.git /opt/bats
                    sudo chmod -R 755 /opt/bats
                    sudo /opt/bats/install.sh /usr/local

                    # Setup BATS libraries
                    cd /srv/pmm-qa/k8s
                    sudo ./setup_bats_libs.sh
                    
                    # Install/Update Helm if needed
                    if ! command -v helm &> /dev/null; then
                        echo "Installing Helm..."
                        curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
                    fi
                    helm version
                """
            }
        }
        
        stage('Select Cluster') {
            steps {
                script {
                    // List available clusters if none specified
                    if (!params.CLUSTER_NAME) {
                        def availableClusters = listAvailableClusters()
                        if (availableClusters.isEmpty()) {
                            error """
                                No clusters found in S3!
                                
                                Please create a cluster first using the 'openshift-cluster-create' job.
                            """
                        }
                        
                        echo """
                            ====================================
                            Available OpenShift Clusters in S3:
                            ===================================="""
                        availableClusters.each { cluster ->
                            echo "  • ${cluster}"
                        }
                        echo """
                            ====================================
                            
                            Please re-run this job with CLUSTER_NAME parameter set to one of the above clusters.
                            
                            Example: CLUSTER_NAME=test-cluster-3
                        """
                        error "No cluster specified. Please select from the list above and re-run."
                    }
                    
                    echo "Using cluster: ${params.CLUSTER_NAME}"
                    
                    // Download kubeconfig from S3
                    kubeconfigPath = downloadKubeconfigFromS3(params.CLUSTER_NAME)
                    
                    // Validate cluster connectivity
                    sh """
                        export KUBECONFIG=${kubeconfigPath}
                        echo "Testing cluster connectivity..."
                        oc version
                        oc get nodes
                        echo "✓ Cluster is accessible!"
                    """
                }
            }
        }
        
        stage('Deploy PMM Helm Chart') {
            steps {
                script {
                    withEnv(["KUBECONFIG=${kubeconfigPath}"]) {
                        sh """
                            echo "Deploying PMM using Helm..."
                            echo "  Chart: ${params.HELM_CHART}"
                            echo "  Version: ${params.CHART_VERSION}"
                            echo "  Namespace: ${params.NAMESPACE}"
                            echo "  Image: ${params.IMAGE_REPO}:${params.IMAGE_TAG}"
                            
                            # Create namespace if it doesn't exist
                            oc create namespace ${params.NAMESPACE} --dry-run=client -o yaml | oc apply -f -
                            
                            # Add Percona Helm repository
                            helm repo add percona https://percona.github.io/percona-helm-charts/
                            helm repo update
                            
                            # Deploy PMM Server using Helm
                            helm upgrade --install pmm-server percona/pmm \\
                                --namespace ${params.NAMESPACE} \\
                                --set image.repository=${params.IMAGE_REPO} \\
                                --set image.tag=${params.IMAGE_TAG} \\
                                --set service.type=LoadBalancer \\
                                --wait \\
                                --timeout 10m \\
                                --debug
                            
                            # Check deployment status
                            echo "Checking PMM deployment status..."
                            oc get pods -n ${params.NAMESPACE}
                            oc get svc -n ${params.NAMESPACE}
                            oc get pvc -n ${params.NAMESPACE}
                        """
                    }
                }
            }
        }
        
        stage('Run Helm Tests') {
            steps {
                script {
                    withEnv([
                        "KUBECONFIG=${kubeconfigPath}",
                        "BATS_LIB_PATH=/srv/pmm-qa/k8s/lib",
                        "IMAGE_REPO=${params.IMAGE_REPO}",
                        "IMAGE_TAG=${params.IMAGE_TAG}",
                        "NAMESPACE=${params.NAMESPACE}"
                    ]) {
                        sh """
                            echo "Running Helm tests..."
                            
                            # Wait for PMM to be ready
                            echo "Waiting for PMM pod to be ready..."
                            oc wait --for=condition=ready pod -l app.kubernetes.io/name=pmm \\
                                -n ${NAMESPACE} --timeout=300s || true
                            
                            # Run BATS tests if available
                            if [ -f /srv/pmm-qa/k8s/helm-test.bats ]; then
                                cd /srv/pmm-qa/k8s
                                bats --tap helm-test.bats
                            else
                                echo "Running basic validation tests..."
                                
                                # Basic health checks
                                POD_NAME=\$(oc get pods -n ${NAMESPACE} -l app.kubernetes.io/name=pmm -o jsonpath='{.items[0].metadata.name}')
                                
                                echo "Pod name: \$POD_NAME"
                                
                                # Check if PMM is responding
                                oc exec -n ${NAMESPACE} \$POD_NAME -- curl -s http://localhost/v1/version || true
                                
                                # Check logs for errors
                                echo "Checking for errors in logs..."
                                oc logs -n ${NAMESPACE} \$POD_NAME --tail=50
                            fi
                        """
                    }
                }
            }
        }
        
        stage('Test Helm Upgrade') {
            when {
                expression { params.TEST_UPGRADE == true }
            }
            steps {
                script {
                    withEnv(["KUBECONFIG=${kubeconfigPath}"]) {
                        sh """
                            echo "Testing Helm upgrade..."
                            
                            # Perform helm upgrade with same or different version
                            helm upgrade pmm-server percona/pmm \\
                                --namespace ${params.NAMESPACE} \\
                                --set image.repository=${params.IMAGE_REPO} \\
                                --set image.tag=${params.IMAGE_TAG} \\
                                --set service.type=LoadBalancer \\
                                --wait \\
                                --timeout 10m
                            
                            # Verify upgrade succeeded
                            oc get pods -n ${params.NAMESPACE}
                            oc wait --for=condition=ready pod -l app.kubernetes.io/name=pmm \\
                                -n ${params.NAMESPACE} --timeout=300s
                            
                            echo "✓ Helm upgrade completed successfully!"
                        """
                    }
                }
            }
        }
        
        stage('Cleanup') {
            steps {
                script {
                    withEnv(["KUBECONFIG=${kubeconfigPath}"]) {
                        sh """
                            echo "Cleaning up PMM deployment..."
                            
                            # Uninstall helm release
                            helm uninstall pmm-server -n ${params.NAMESPACE} || true
                            
                            # Delete namespace (optional - comment out to keep namespace)
                            # oc delete namespace ${params.NAMESPACE} --ignore-not-found=true
                            
                            echo "✓ Cleanup completed!"
                        """
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                echo """
                    Test Summary:
                    - Cluster: ${params.CLUSTER_NAME}
                    - Chart: ${params.HELM_CHART}
                    - Image: ${params.IMAGE_REPO}:${params.IMAGE_TAG}
                    - Namespace: ${params.NAMESPACE}
                    
                    Note: The OpenShift cluster remains available for other tests.
                """
            }
        }
        failure {
            script {
                if (kubeconfigPath) {
                    withEnv(["KUBECONFIG=${kubeconfigPath}"]) {
                        sh """
                            echo "Collecting debug information..."
                            oc get events -n ${params.NAMESPACE} --sort-by='.lastTimestamp' || true
                            oc describe pods -n ${params.NAMESPACE} || true
                        """
                    }
                }
            }
        }
    }
}