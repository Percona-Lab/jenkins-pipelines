pipeline {
    agent {
        label 'agent-amd64'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 40, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    parameters {
        string(
            name: 'CLUSTER_NAME',
            defaultValue: '',
            description: 'Cluster to scale, e.g. pmm-ha-test-54'
        )
        choice(
            name: 'WORKER_NODE_COUNT',
            choices: ['6', '7', '8', '9', '10', '11', '12'],
            description: 'Target worker node count, up or down. The baseline is whatever WORKER_COUNT pmm3-ha-eks was run with. Each PMM replica requests 2 CPU / 3Gi, so scaling below what the deployed replicas need will leave pods Pending.'
        )
    }

    environment {
        REGION = "us-east-2"
        CREATE_JOB = "pmm3-ha-eks"
        NODEGROUP = "ng-spot"
        KUBECONFIG = "${WORKSPACE}/kubeconfig"
    }

    stages {
        stage('Fetch kubeconfig') {
            steps {
                script {
                    def cluster = params.CLUSTER_NAME.trim()
                    if (!(cluster ==~ /^pmm-ha-test-\d+$/)) {
                        error("CLUSTER_NAME must look like pmm-ha-test-<build-number>")
                    }
                    env.CLUSTER_NAME = cluster
                    def buildNumber = cluster.substring('pmm-ha-test-'.length())
                    copyArtifacts(
                        projectName: env.CREATE_JOB,
                        selector: specific(buildNumber),
                        filter: 'kubeconfig'
                    )
                }
            }
        }

        stage('Scale') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        # A missing cluster fails this too, so it is the only lookup needed.
                        if ! aws eks describe-nodegroup --region "${REGION}" \
                            --cluster-name "${CLUSTER_NAME}" \
                            --nodegroup-name "${NODEGROUP}" --output json > nodegroup.json 2>/dev/null; then
                            echo "ERROR: Nodegroup '${NODEGROUP}' of cluster '${CLUSTER_NAME}' not found in ${REGION}."
                            exit 1
                        fi
                        STATE=$(jq -r '.nodegroup.status' nodegroup.json)
                        if [ "${STATE}" != "ACTIVE" ]; then
                            echo "ERROR: Nodegroup '${NODEGROUP}' is not active (state: ${STATE}). Refusing to scale."
                            exit 1
                        fi

                        CURRENT=$(jq -r '.nodegroup.scalingConfig.desiredSize' nodegroup.json)
                        echo "Scaling ${NODEGROUP}: ${CURRENT} -> ${WORKER_NODE_COUNT} nodes"

                        # --nodes-min is what makes scaling down possible: pmm3-ha-eks sets minSize to its
                        # WORKER_COUNT, so the floor has to move with the target. --nodes-max is left off to
                        # keep the ceiling that job set - eksctl only sends the sizes whose flags it got.
                        eksctl scale nodegroup \
                            --cluster="${CLUSTER_NAME}" \
                            --region="${REGION}" \
                            --name="${NODEGROUP}" \
                            --nodes="${WORKER_NODE_COUNT}" \
                            --nodes-min="${WORKER_NODE_COUNT}"

                        echo "Waiting for exactly ${WORKER_NODE_COUNT} Ready worker nodes (timeout: 20m)..."
                        for i in $(seq 1 40); do
                            TOTAL=$(kubectl get nodes --no-headers | wc -l)
                            READY=$(kubectl get nodes --no-headers | grep -c " Ready " || true)
                            echo "Nodes: total=${TOTAL}, ready=${READY}, target=${WORKER_NODE_COUNT}"
                            [ "${TOTAL}" -eq "${WORKER_NODE_COUNT}" ] && [ "${READY}" -eq "${WORKER_NODE_COUNT}" ] && break
                            if [ "$i" -eq 40 ]; then
                                echo "ERROR: Did not converge to ${WORKER_NODE_COUNT} Ready nodes."
                                kubectl get nodes -o wide
                                # Spot shortfalls on the way up and PDB-blocked drains on the way down both
                                # stall the nodegroup update, and both surface here, not in the node list.
                                aws eks describe-nodegroup --region "${REGION}" \
                                    --cluster-name "${CLUSTER_NAME}" \
                                    --nodegroup-name "${NODEGROUP}" \
                                    --query 'nodegroup.{status:status,health:health}'
                                exit 1
                            fi
                            sleep 30
                        done

                        kubectl get nodes -o wide
                    '''
                }
            }
        }
    }

    post {
        success {
            script {
                currentBuild.description = "${params.CLUSTER_NAME} -> ${params.WORKER_NODE_COUNT} workers"
            }
        }
    }
}
