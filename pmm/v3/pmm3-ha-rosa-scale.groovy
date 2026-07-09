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
            description: 'Cluster to scale, e.g. pmm-ha-rosa-54'
        )
        choice(
            name: 'WORKER_NODE_COUNT',
            choices: ['3', '4', '5', '6'],
            description: 'Target worker node count (baseline is 3, max 6)'
        )
    }

    environment {
        REGION = "us-east-2"
        CREATE_JOB = "pmm3-ha-rosa"
        MACHINEPOOL = "workers"
        KUBECONFIG = "${HOME}/.kube/rosa-scale-${BUILD_NUMBER}"
        PATH = "${HOME}/.local/bin:${PATH}"
    }

    stages {
        stage('Install Tools') {
            steps {
                withCredentials([string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')]) {
                    sh '''
                        mkdir -p $HOME/.local/bin

                        if ! command -v rosa &>/dev/null; then
                            curl -sL https://mirror.openshift.com/pub/openshift-v4/clients/rosa/latest/rosa-linux.tar.gz -o rosa.tar.gz
                            tar xzf rosa.tar.gz -C $HOME/.local/bin rosa
                            chmod +x $HOME/.local/bin/rosa
                            rm -f rosa.tar.gz
                        fi

                        if ! command -v oc &>/dev/null; then
                            curl -sL https://mirror.openshift.com/pub/openshift-v4/clients/ocp/stable/openshift-client-linux.tar.gz -o oc.tar.gz
                            tar xzf oc.tar.gz -C $HOME/.local/bin oc kubectl
                            chmod +x $HOME/.local/bin/oc $HOME/.local/bin/kubectl
                            rm -f oc.tar.gz
                        fi

                        rosa login --token="${ROSA_TOKEN}"
                    '''
                }
            }
        }

        stage('Fetch Admin Credentials') {
            steps {
                script {
                    def cluster = params.CLUSTER_NAME.trim()
                    if (!(cluster ==~ /^pmm-ha-rosa-\d+$/)) {
                        error("CLUSTER_NAME must look like pmm-ha-rosa-<build-number>")
                    }
                    env.CLUSTER_NAME = cluster
                    def buildNumber = cluster.substring('pmm-ha-rosa-'.length())
                    // Reuse cluster-admin password, the one create build already generated
                    copyArtifacts(
                        projectName: env.CREATE_JOB,
                        selector: specific(buildNumber),
                        filter: 'api-url.txt,cluster-admin-password.txt'
                    )
                }
            }
        }

        stage('Scale') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        if ! rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" -o json > cluster.json 2>/dev/null; then
                            echo "ERROR: Cluster '${CLUSTER_NAME}' not found in ${REGION}."
                            exit 1
                        fi
                        STATE=$(jq -r '.status.state' cluster.json)
                        if [ "${STATE}" != "ready" ]; then
                            echo "ERROR: Cluster '${CLUSTER_NAME}' is not ready (state: ${STATE}). Refusing to scale."
                            exit 1
                        fi

                        rosa edit machinepool \
                            --cluster="${CLUSTER_NAME}" \
                            --region="${REGION}" \
                            --replicas="${WORKER_NODE_COUNT}" \
                            "${MACHINEPOOL}"

                        oc login "$(cat api-url.txt)" \
                            --username=cluster-admin \
                            --password="$(cat cluster-admin-password.txt)" \
                            --insecure-skip-tls-verify=true

                        echo "Waiting for exactly ${WORKER_NODE_COUNT} Ready worker nodes (timeout: 20m)..."
                        for i in $(seq 1 40); do
                            TOTAL=$(oc get nodes --no-headers | wc -l)
                            READY=$(oc get nodes --no-headers | grep -c " Ready " || true)
                            echo "Nodes: total=${TOTAL}, ready=${READY}, target=${WORKER_NODE_COUNT}"
                            [ "${TOTAL}" -eq "${WORKER_NODE_COUNT}" ] && [ "${READY}" -eq "${WORKER_NODE_COUNT}" ] && break
                            if [ "$i" -eq 40 ]; then
                                echo "ERROR: Did not converge to ${WORKER_NODE_COUNT} Ready nodes."
                                oc get nodes -o wide
                                exit 1
                            fi
                            sleep 30
                        done

                        oc get nodes -o wide
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
