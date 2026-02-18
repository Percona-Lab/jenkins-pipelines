def cleanupCluster() {
    withCredentials([
        aws(credentialsId: 'pmm-staging-slave'),
        string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
    ]) {
        sh '''
            rosa login --token="${ROSA_TOKEN}"

            if rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" &>/dev/null; then
                echo "Destroying ROSA cluster ${CLUSTER_NAME}..."
                rosa delete cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" --yes --watch || true
            fi
        '''
    }
}

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    options {
        timeout(time: 120, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
    }

    parameters {
        choice(
            name: 'OPENSHIFT_VERSION',
            choices: ['4.18', '4.17', '4.16', '4.19'],
            description: 'OpenShift version to install (latest patch auto-resolved)'
        )
        string(
            name: 'HELM_CHART_BRANCH',
            defaultValue: 'main',
            description: 'Branch of percona-helm-charts repo for PMM chart'
        )
        string(
            name: 'PMM_IMAGE_REPOSITORY',
            defaultValue: '',
            description: 'PMM image repository override (default: from Helm chart)'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: '',
            description: 'PMM image tag override (default: from Helm chart)'
        )
        choice(
            name: 'RETENTION_DAYS',
            choices: ['1', '2', '3'],
            description: 'Days to retain cluster before auto-deletion'
        )
        string(
            name: 'PMM_ADMIN_PASSWORD',
            defaultValue: '',
            description: 'PMM admin password (leave empty for auto-generated)'
        )
        booleanParam(
            name: 'ENABLE_EXTERNAL_ACCESS',
            defaultValue: false,
            description: 'Enable external access for PMM (creates LoadBalancer)'
        )
    }

    environment {
        CLUSTER_NAME = "pmm-ha-openshift-${BUILD_NUMBER}"
        REGION = "us-east-2"
        KUBECONFIG = "${WORKSPACE}/kubeconfig"
        PATH = "${HOME}/.local/bin:${PATH}"
    }

    stages {
        stage('Install Tools') {
            steps {
                sh '''
                    # Create local bin directory
                    mkdir -p $HOME/.local/bin
                    export PATH="$HOME/.local/bin:$PATH"

                    # Install ROSA CLI
                    echo "Installing ROSA CLI..."
                    curl -sL https://mirror.openshift.com/pub/openshift-v4/clients/rosa/latest/rosa-linux.tar.gz -o rosa.tar.gz
                    tar xzf rosa.tar.gz
                    mv rosa $HOME/.local/bin/
                    chmod +x $HOME/.local/bin/rosa
                    rm -f rosa.tar.gz

                    # Install OpenShift CLI (oc)
                    echo "Installing OpenShift CLI..."
                    curl -sL https://mirror.openshift.com/pub/openshift-v4/clients/ocp/stable/openshift-client-linux.tar.gz -o oc.tar.gz
                    tar xzf oc.tar.gz -C $HOME/.local/bin oc kubectl
                    chmod +x $HOME/.local/bin/oc $HOME/.local/bin/kubectl
                    rm -f oc.tar.gz

                    # Verify installations
                    rosa version
                    oc version --client
                '''
            }
        }

        stage('Initialize ROSA') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        rosa login --token="${ROSA_TOKEN}"

                        echo "Initializing ROSA (idempotent - safe to run multiple times)..."
                        rosa init --region="${REGION}" || true

                        echo "Ensuring account roles exist..."
                        # Check if account roles already exist
                        if ! rosa list account-roles --region="${REGION}" | grep -q "ManagedOpenShift-Installer-Role"; then
                            echo "Creating account roles..."
                            rosa create account-roles --mode auto --yes --region="${REGION}"
                        else
                            echo "Account roles already exist."
                        fi

                        echo "Ensuring OIDC provider exists..."
                        # OIDC config is created per cluster, but we can verify account is ready
                        rosa whoami --region="${REGION}"
                    '''
                }
            }
        }

        stage('Check Existing Clusters') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        set +x

                        rosa login --token="${ROSA_TOKEN}"

                        EXISTING_CLUSTERS=$(rosa list clusters --region="${REGION}" -o json | jq -r '[.[] | select(.name | startswith("pmm-ha-openshift-"))] | length')

                        if [ "${EXISTING_CLUSTERS}" -ge 3 ]; then
                            echo "ERROR: Maximum limit of 3 ROSA test clusters reached."
                            echo "Please delete existing clusters before creating new ones."
                            rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("pmm-ha-openshift-")) | "  - \\(.name) (\\(.state))"'
                            exit 1
                        fi

                        echo "Existing PMM OpenShift test clusters: ${EXISTING_CLUSTERS} / 3"
                    '''
                }
            }
        }

        stage('Create ROSA Cluster') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        rosa login --token="${ROSA_TOKEN}"

                        # Resolve latest patch version for the requested major.minor
                        echo "Resolving latest patch version for OpenShift ${OPENSHIFT_VERSION}..."
                        RESOLVED_VERSION=$(rosa list versions --region="${REGION}" -o json | \
                            jq -r --arg ver "${OPENSHIFT_VERSION}" \
                            '.[] | select(.raw_id | startswith($ver + ".")) | .raw_id' | sort -V | tail -1)

                        if [ -z "${RESOLVED_VERSION}" ]; then
                            echo "ERROR: Could not find available version for OpenShift ${OPENSHIFT_VERSION}"
                            rosa list versions --region="${REGION}"
                            exit 1
                        fi
                        echo "Resolved version: ${RESOLVED_VERSION}"

                        echo "Creating ROSA cluster ${CLUSTER_NAME} (this takes 30-45 minutes)..."

                        rosa create cluster \
                            --cluster-name="${CLUSTER_NAME}" \
                            --region="${REGION}" \
                            --version="${RESOLVED_VERSION}" \
                            --replicas=3 \
                            --compute-machine-type=c5a.xlarge \
                            --tags="iit-billing-tag=pmm,retention-days=${RETENTION_DAYS},created-by=jenkins,build-number=${BUILD_NUMBER},purpose=pmm-openshift-testing" \
                            --sts \
                            --mode=auto \
                            --yes
                    '''
                }
            }
        }

        stage('Wait for Cluster Ready') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        rosa login --token="${ROSA_TOKEN}"

                        echo "Waiting for cluster to be ready..."
                        rosa logs install --cluster="${CLUSTER_NAME}" --region="${REGION}" --watch &
                        LOGS_PID=$!

                        # Wait for cluster to be ready (timeout handled by pipeline)
                        while true; do
                            STATE=$(rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" -o json | jq -r '.state')
                            echo "Cluster state: ${STATE}"

                            if [ "${STATE}" = "ready" ]; then
                                echo "Cluster is ready!"
                                break
                            elif [ "${STATE}" = "error" ]; then
                                echo "ERROR: Cluster creation failed"
                                rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}"
                                kill $LOGS_PID 2>/dev/null || true
                                exit 1
                            fi

                            sleep 60
                        done

                        kill $LOGS_PID 2>/dev/null || true
                    '''
                }
            }
        }

        stage('Configure Cluster Access') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        sh '''
                            rosa login --token="${ROSA_TOKEN}"

                            echo "Creating cluster admin..."
                            rosa create admin --cluster="${CLUSTER_NAME}" --region="${REGION}" --yes > admin-output.txt 2>&1 || true
                            cat admin-output.txt
                        '''

                        // Extract login credentials from output
                        def adminOutput = readFile('admin-output.txt')
                        def apiUrl = sh(
                            returnStdout: true,
                            script: "grep -oP '(?<=--server=)[^\\s]+' admin-output.txt || rosa describe cluster --cluster=${CLUSTER_NAME} --region=${REGION} -o json | jq -r '.api.url'"
                        ).trim()

                        env.CLUSTER_API_URL = apiUrl

                        sh '''
                            # Wait for admin user to be ready
                            echo "Waiting for cluster-admin user to be ready..."
                            sleep 60

                            # Get login command and execute
                            ADMIN_USER=$(grep -oP '(?<=--username=)[^\\s]+' admin-output.txt || echo "cluster-admin")
                            ADMIN_PASS=$(grep -oP '(?<=--password=)[^\\s]+' admin-output.txt)

                            echo "${ADMIN_PASS}" > "${WORKSPACE}/kubeadmin-password"

                            # Login and save kubeconfig
                            oc login "${CLUSTER_API_URL}" \
                                --username="${ADMIN_USER}" \
                                --password="${ADMIN_PASS}" \
                                --insecure-skip-tls-verify=true

                            cp ~/.kube/config "${KUBECONFIG}"

                            echo "Verifying cluster access..."
                            oc get nodes -o wide
                            oc get clusterversion
                        '''
                    }
                }
            }
        }

        stage('Install Storage Class') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        export KUBECONFIG="${KUBECONFIG}"

                        # Check if gp3-csi already exists
                        if oc get storageclass gp3-csi &>/dev/null; then
                            echo "Storage class gp3-csi already exists"
                        else
                            echo "Creating GP3 storage class..."
                            cat <<EOF | oc apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: gp3-csi
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  encrypted: "true"
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
EOF

                            # Remove default annotation from gp2 if it exists
                            oc patch storageclass gp2 -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' 2>/dev/null || true
                            oc patch storageclass gp3 -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' 2>/dev/null || true
                        fi

                        oc get storageclass
                    '''
                }
            }
        }

        stage('Install PMM via Helm') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        // Clone Helm charts repo
                        git poll: false, branch: HELM_CHART_BRANCH, url: 'https://github.com/percona/percona-helm-charts.git'

                        sh '''
                            export KUBECONFIG="${KUBECONFIG}"

                            # Create PMM namespace
                            oc create namespace pmm || true

                            # Grant anyuid SCC (required for PMM on OpenShift)
                            oc adm policy add-scc-to-user anyuid -z default -n pmm
                            oc adm policy add-scc-to-user anyuid -z pmm -n pmm 2>/dev/null || true

                            # Generate password if not provided
                            if [ -n "${PMM_ADMIN_PASSWORD}" ]; then
                                PMM_PW="${PMM_ADMIN_PASSWORD}"
                            else
                                PMM_PW="$(openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 16)"
                            fi

                            echo "${PMM_PW}" > "${WORKSPACE}/pmm-password.txt"

                            # Add Helm repos
                            helm repo add percona https://percona.github.io/percona-helm-charts/ || true
                            helm repo update

                            # Install PMM using Helm
                            helm upgrade --install pmm charts/pmm \
                                --namespace pmm \
                                --set platform=openshift \
                                --set service.type=ClusterIP \
                                --set secret.pmm_password="${PMM_PW}" \
                                ${PMM_IMAGE_REPOSITORY:+--set image.repository=${PMM_IMAGE_REPOSITORY}} \
                                ${PMM_IMAGE_TAG:+--set image.tag=${PMM_IMAGE_TAG}} \
                                --wait --timeout 15m

                            # Create route for PMM access
                            oc create route passthrough pmm-https \
                                --service=monitoring-service \
                                --port=https \
                                -n pmm 2>/dev/null || true

                            # Wait for PMM to be ready
                            oc rollout status deployment/monitoring -n pmm --timeout=600s

                            # Get PMM URL
                            PMM_HOST=$(oc get route pmm-https -n pmm -o jsonpath='{.spec.host}')
                            echo "https://${PMM_HOST}" > "${WORKSPACE}/pmm-url.txt"

                            echo "PMM deployed successfully!"
                            oc get pods -n pmm
                        '''
                    }
                }
            }
        }

        stage('Configure External Access') {
            when {
                expression { params.ENABLE_EXTERNAL_ACCESS }
            }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        sh '''
                            export KUBECONFIG="${KUBECONFIG}"

                            # Patch service to LoadBalancer
                            oc patch svc monitoring-service -n pmm --type='merge' -p '{
                                "spec": {
                                    "type": "LoadBalancer"
                                },
                                "metadata": {
                                    "annotations": {
                                        "service.beta.kubernetes.io/aws-load-balancer-type": "nlb",
                                        "service.beta.kubernetes.io/aws-load-balancer-scheme": "internet-facing"
                                    }
                                }
                            }'

                            echo "Waiting for LoadBalancer hostname..."
                            sleep 120
                        '''

                        def lbHost = sh(
                            returnStdout: true,
                            script: '''
                                export KUBECONFIG="${KUBECONFIG}"
                                oc get svc monitoring-service -n pmm \
                                    -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
                            '''
                        ).trim()

                        if (lbHost) {
                            env.PMM_EXTERNAL_URL = "https://${lbHost}"
                            sh "echo '${env.PMM_EXTERNAL_URL}' > '${WORKSPACE}/pmm-external-url.txt'"
                        }
                    }
                }
            }
        }

        stage('Cluster Summary') {
            steps {
                script {
                    env.PMM_URL = readFile("${WORKSPACE}/pmm-url.txt").trim()
                    env.PMM_PASSWORD = readFile("${WORKSPACE}/pmm-password.txt").trim()
                    env.KUBEADMIN_PASSWORD = readFile("${WORKSPACE}/kubeadmin-password").trim()
                }

                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set +x

                        export KUBECONFIG="${KUBECONFIG}"

                        echo ""
                        echo "====================================================================="
                        echo "ROSA Cluster Summary"
                        echo "====================================================================="
                        echo ""
                        echo "Cluster Name:        ${CLUSTER_NAME}"
                        echo "OpenShift Version:   ${OPENSHIFT_VERSION}"
                        echo "Region:              ${REGION}"
                        echo "Build Number:        ${BUILD_NUMBER}"
                        echo ""

                        oc get nodes -o wide
                        echo ""

                        echo "====================================================================="
                        echo "Access Information"
                        echo "====================================================================="
                        echo ""
                        echo "OpenShift Console:   $(rosa describe cluster --cluster=${CLUSTER_NAME} --region=${REGION} -o json | jq -r '.console.url')"
                        echo "API Server:          ${CLUSTER_API_URL}"
                        echo ""
                        echo "Cluster Admin:       cluster-admin"
                        echo "Cluster Password:    ${KUBEADMIN_PASSWORD}"
                        echo ""
                        echo "PMM URL:             ${PMM_URL}"
                        echo "PMM Username:        admin"
                        echo "PMM Password:        ${PMM_PASSWORD}"
                        echo ""
                    '''

                    script {
                        if (fileExists("${WORKSPACE}/pmm-external-url.txt")) {
                            def externalUrl = readFile("${WORKSPACE}/pmm-external-url.txt").trim()
                            echo "PMM External URL:    ${externalUrl}"
                        }
                    }

                    sh '''
                        echo ""
                        echo "====================================================================="
                        echo "Local Access Commands"
                        echo "====================================================================="
                        echo ""
                        echo "# Download kubeconfig from Jenkins artifacts, then:"
                        echo "export KUBECONFIG=./kubeconfig"
                        echo "oc login ${CLUSTER_API_URL} -u cluster-admin -p ${KUBEADMIN_PASSWORD}"
                        echo ""
                    '''
                }
            }
        }

        stage('Archive Artifacts') {
            steps {
                archiveArtifacts artifacts: 'kubeconfig', fingerprint: true
                archiveArtifacts artifacts: 'kubeadmin-password', fingerprint: true
                archiveArtifacts artifacts: 'pmm-password.txt', fingerprint: true
                archiveArtifacts artifacts: 'pmm-url.txt', fingerprint: true
                archiveArtifacts artifacts: 'pmm-external-url.txt', fingerprint: true, allowEmptyArchive: true
            }
        }
    }

    post {
        success {
            script {
                def pmmUrl = readFile("${WORKSPACE}/pmm-url.txt").trim()
                currentBuild.description = "Cluster: ${env.CLUSTER_NAME} | PMM: ${pmmUrl}"
            }
            echo "Cluster ${CLUSTER_NAME} created successfully."
            echo "Download the kubeconfig artifact to access the cluster."
        }
        failure {
            echo "Build FAILED - cleaning up cluster"
            archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
            cleanupCluster()
        }
        aborted {
            echo "Build ABORTED - cleaning up cluster"
            cleanupCluster()
        }
    }
}
