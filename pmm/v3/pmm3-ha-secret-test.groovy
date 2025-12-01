/**
 * Temporary test job to validate pmm-secret structure for PMM HA helm chart.
 * This tests locally using helm template without needing a real cluster.
 */
pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
        timeout(time: 10, unit: 'MINUTES')
    }

    stages {
        stage('Setup') {
            steps {
                sh '''
                    # Install helm if needed
                    if ! command -v helm &>/dev/null; then
                        curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
                    fi
                    helm version

                    # Clone the helm charts
                    rm -rf percona-helm-charts
                    git clone --depth 1 --branch pmmha-v3 https://github.com/percona/percona-helm-charts.git

                    # Add repos and update dependencies
                    helm repo add percona https://percona.github.io/percona-helm-charts/ || true
                    helm repo add vm https://victoriametrics.github.io/helm-charts/ || true
                    helm repo add altinity https://docs.altinity.com/helm-charts/ || true
                    helm repo add haproxytech https://haproxytech.github.io/helm-charts/ || true
                    helm repo update

                    helm dependency update percona-helm-charts/charts/pmm-ha-dependencies
                    helm dependency update percona-helm-charts/charts/pmm-ha
                '''
            }
        }

        stage('Test Secret Keys') {
            steps {
                sh '''
                    echo "=== Testing helm template with secret.create=true (default) ==="
                    helm template pmm-ha percona-helm-charts/charts/pmm-ha \
                        --namespace pmm \
                        --set image.repository=perconalab/pmm-server \
                        --set image.tag=dev-latest \
                        > /tmp/template-default.yaml 2>&1 || true

                    echo ""
                    echo "=== Checking generated secret keys ==="
                    grep -A 30 "kind: Secret" /tmp/template-default.yaml | head -40

                    echo ""
                    echo "=== Looking at vmauth.yaml template ==="
                    cat percona-helm-charts/charts/pmm-ha/templates/vmauth.yaml | head -20

                    echo ""
                    echo "=== Testing with secret.create=false (our scenario) ==="
                    echo "This should fail because the secret does not exist for lookup"
                    helm template pmm-ha percona-helm-charts/charts/pmm-ha \
                        --namespace pmm \
                        --set secret.create=false \
                        --set secret.name=pmm-secret \
                        2>&1 || echo "Expected failure - secret lookup returns nil"
                '''
            }
        }

        stage('Identify Required Secret Keys') {
            steps {
                sh '''
                    echo "=== Extracting all secret key references from templates ==="
                    echo ""
                    echo "Keys referenced in pmm-ha templates:"
                    grep -rhoE 'index .existingSecret.data "[^"]+"|.data.[A-Z_]+' \
                        percona-helm-charts/charts/pmm-ha/templates/ 2>/dev/null | sort -u || true

                    echo ""
                    echo "=== From secret.yaml (the canonical list) ==="
                    grep -E "^[[:space:]]+[A-Z_]+:" percona-helm-charts/charts/pmm-ha/templates/secret.yaml | head -20

                    echo ""
                    echo "=== Keys that use b64dec (will fail if nil) ==="
                    grep -rn "b64dec" percona-helm-charts/charts/pmm-ha/templates/ || true
                '''
            }
        }

        stage('Proposed Secret Structure') {
            steps {
                script {
                    echo '''
=== Proposed pmm-secret structure for pre-creation ===

Based on chart analysis, the secret needs these keys:

# Required by secret.yaml and vmauth.yaml:
PMM_ADMIN_PASSWORD          - PMM admin password
PMM_CLICKHOUSE_USER         - ClickHouse username (default: clickhouse_pmm)
PMM_CLICKHOUSE_PASSWORD     - ClickHouse password
VMAGENT_remoteWrite_basicAuth_username  - VM username (default: victoriametrics_pmm)
VMAGENT_remoteWrite_basicAuth_password  - VM password
PG_PASSWORD                 - PostgreSQL password
GF_PASSWORD                 - Grafana password

=== Sample oc create secret command ===
oc create secret generic pmm-secret -n pmm \\
    --from-literal=PMM_ADMIN_PASSWORD=<password> \\
    --from-literal=PMM_CLICKHOUSE_USER=clickhouse_pmm \\
    --from-literal=PMM_CLICKHOUSE_PASSWORD=<password> \\
    --from-literal=VMAGENT_remoteWrite_basicAuth_username=victoriametrics_pmm \\
    --from-literal=VMAGENT_remoteWrite_basicAuth_password=<password> \\
    --from-literal=PG_PASSWORD=<password> \\
    --from-literal=GF_PASSWORD=<password>
'''
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
