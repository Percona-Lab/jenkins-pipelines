library changelog: false, identifier: 'lib@feature/pmm-ha-rosa', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
])

pipeline {
    agent {
        label 'agent-amd64-ol9'
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
            defaultValue: 'main',
            description: 'Branch from percona-helm-charts repository'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: 'dev-latest',
            description: 'PMM Server image tag'
        )
        string(
            name: 'PMM_IMAGE_REPOSITORY',
            defaultValue: 'perconalab/pmm-server',
            description: 'PMM Server image repository'
        )
    }

    environment {
        CLUSTER_NAME = "pmm-ha-rosa-${BUILD_NUMBER}"
        REGION = 'us-east-2'
        PMM_NAMESPACE = 'pmm'
        KUBECONFIG = "${WORKSPACE}/kubeconfig/config"
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 60, unit: 'MINUTES')
    }

    stages {
        stage('Install CLI Tools') {
            steps {
                script {
                    // Install ROSA CLI
                    pmmHaRosa.installRosaCli()

                    // Install OpenShift CLI (oc)
                    pmmHaRosa.installOcCli([
                        version: params.OPENSHIFT_VERSION
                    ])
                }
            }
        }

        stage('Login to ROSA') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        pmmHaRosa.login([
                            token: env.ROSA_TOKEN,
                            region: env.REGION
                        ])
                    }
                }
            }
        }

        stage('Check Existing Clusters') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        def canCreate = pmmHaRosa.checkClusterLimit([
                            maxClusters: 5
                        ])

                        if (!canCreate) {
                            error 'Maximum cluster limit reached. Please delete existing clusters first.'
                        }
                    }
                }
            }
        }

        stage('Create ROSA HCP Cluster') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        def clusterInfo = pmmHaRosa.createCluster([
                            clusterName: env.CLUSTER_NAME,
                            region: env.REGION,
                            openshiftVersion: params.OPENSHIFT_VERSION,
                            replicas: params.REPLICAS.toInteger(),
                            instanceType: params.INSTANCE_TYPE
                        ])

                        echo "Cluster created: ${clusterInfo.clusterName}"
                        echo "Cluster ID: ${clusterInfo.clusterId}"
                    }
                }
            }
        }

        stage('Configure kubectl Access') {
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        def accessInfo = pmmHaRosa.configureAccess([
                            clusterName: env.CLUSTER_NAME,
                            kubeconfigPath: env.KUBECONFIG,
                            region: env.REGION
                        ])

                        env.CLUSTER_ADMIN_PASSWORD = accessInfo.password
                        echo 'Cluster admin configured successfully'
                    }
                }
            }
        }

        stage('Install PMM HA Dependencies') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        // Clone Helm charts repository
                        sh """
                            export PATH="\$HOME/.local/bin:\$PATH"
                            export KUBECONFIG="${env.KUBECONFIG}"

                            rm -rf percona-helm-charts
                            git clone --depth 1 --branch ${params.HELM_CHART_BRANCH} \\
                                https://github.com/percona/percona-helm-charts.git

                            # Verify chart exists
                            ls -la percona-helm-charts/charts/
                        """
                    }
                }
            }
        }

        stage('Install PMM HA') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        def pmmInfo = pmmHaRosa.installPmm([
                            namespace: env.PMM_NAMESPACE,
                            chartBranch: params.HELM_CHART_BRANCH,
                            imageTag: params.PMM_IMAGE_TAG,
                            imageRepository: params.PMM_IMAGE_REPOSITORY
                        ])

                        env.PMM_ADMIN_PASSWORD = pmmInfo.adminPassword
                        echo "PMM HA installed in namespace: ${pmmInfo.namespace}"
                    }
                }
            }
        }

        stage('Setup External Access') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        def routeInfo = pmmHaRosa.createRoute([
                            namespace: env.PMM_NAMESPACE,
                            clusterName: env.CLUSTER_NAME,
                            r53ZoneName: 'cd.percona.com'
                        ])

                        env.PMM_URL = routeInfo.url
                        echo "PMM accessible at: ${env.PMM_URL}"
                    }
                }
            }
        }

        stage('Cluster Summary') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh """
                        set +x
                        export PATH="\$HOME/.local/bin:\$PATH"
                        export KUBECONFIG="${env.KUBECONFIG}"

                        echo "========================================"
                        echo "ROSA HCP Cluster Summary"
                        echo "========================================"
                        echo ""
                        echo "Cluster Name:     ${env.CLUSTER_NAME}"
                        echo "OpenShift:        ${params.OPENSHIFT_VERSION}"
                        echo "Region:           ${env.REGION}"
                        echo "Worker Replicas:  ${params.REPLICAS}"
                        echo "Instance Type:    ${params.INSTANCE_TYPE}"
                        echo ""
                        echo "PMM URL:          ${env.PMM_URL}"
                        echo "PMM Username:     admin"
                        echo "PMM Password:     ${env.PMM_ADMIN_PASSWORD}"
                        echo ""
                        echo "OpenShift Console: \$(rosa describe cluster -c ${env.CLUSTER_NAME} -o json | jq -r '.console.url')"
                        echo "Cluster Admin:     cluster-admin"
                        echo "Cluster Password:  ${env.CLUSTER_ADMIN_PASSWORD}"
                        echo ""
                        echo "========================================"
                        echo "Nodes:"
                        oc get nodes -o wide
                        echo ""
                        echo "PMM Pods:"
                        oc get pods -n ${env.PMM_NAMESPACE}
                        echo ""
                        echo "PMM Services:"
                        oc get svc -n ${env.PMM_NAMESPACE}
                        echo ""
                        echo "PMM Routes:"
                        oc get routes -n ${env.PMM_NAMESPACE}
                        echo "========================================"
                    """
                }
            }
        }

        stage('Archive Artifacts') {
            steps {
                script {
                    // Create cluster info file
                    writeFile file: 'cluster-info.txt', text: """
ROSA HCP Cluster Information
=============================
Cluster Name:     ${env.CLUSTER_NAME}
OpenShift:        ${params.OPENSHIFT_VERSION}
Region:           ${env.REGION}
Build:            ${BUILD_NUMBER}

PMM Access
----------
URL:              ${env.PMM_URL}
Username:         admin
Password:         ${env.PMM_ADMIN_PASSWORD}

OpenShift Access
----------------
Console:          See rosa describe cluster output
Username:         cluster-admin
Password:         ${env.CLUSTER_ADMIN_PASSWORD}

To access via CLI:
  rosa login --token=<your-token>
  rosa describe cluster -c ${env.CLUSTER_NAME}
  oc login <api-url> --username=cluster-admin --password=<password>
"""

                    archiveArtifacts artifacts: 'cluster-info.txt', fingerprint: true
                    archiveArtifacts artifacts: 'kubeconfig/config', fingerprint: true, allowEmptyArchive: true
                }
            }
        }
    }

    post {
        success {
            echo "ROSA HCP cluster ${env.CLUSTER_NAME} created successfully!"
            echo "PMM HA accessible at: ${env.PMM_URL}"
        }
        failure {
            script {
                echo 'Pipeline failed. Cluster cleanup is DISABLED for debugging.'
                echo "Cluster ${env.CLUSTER_NAME} may still be running - use cleanup job to delete."

            // NOTE: Cleanup disabled for debugging. Re-enable when pipeline is stable:
            // try {
            //     withCredentials([
            //         aws(credentialsId: 'pmm-staging-slave'),
            //         string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
            //     ]) {
            //         pmmHaRosa.login([token: env.ROSA_TOKEN])
            //         pmmHaRosa.deleteCluster([
            //             clusterName: env.CLUSTER_NAME
            //         ])
            //     }
            // } catch (Exception e) {
            //     echo "Cleanup failed: ${e.message}"
            //     echo 'Manual cleanup may be required.'
            // }
            }
        }
    }
}
