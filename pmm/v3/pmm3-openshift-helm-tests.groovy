library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def runOpenshiftClusterCreate(String OPENSHIFT_VERSION) {
    def clusterName = "helm-test-${env.BUILD_NUMBER}"

    clusterCreateJob = build job: 'openshift-cluster-create', parameters: [
        string(name: 'CLUSTER_NAME', value: clusterName),
        string(name: 'OPENSHIFT_VERSION', value: OPENSHIFT_VERSION),
        booleanParam(name: 'DEPLOY_PMM', value: false),
        string(name: 'TEAM_NAME', value: 'pmm'),
        string(name: 'PRODUCT_TAG', value: 'pmm'),
    ]

    env.VM_IP = clusterCreateJob.buildVariables.IP
    env.VM_NAME = clusterCreateJob.buildVariables.VM_NAME
    env.WORK_DIR = clusterCreateJob.buildVariables.WORK_DIR
    env.FINAL_CLUSTER_NAME = clusterCreateJob.buildVariables.FINAL_CLUSTER_NAME
}


def destroyOpenshift(CLUSTER_NAME) {
    build job: 'openshift-cluster-destroy', parameters: [
        string(name: 'CLUSTER_NAME', value: CLUSTER_NAME),
        string(name: 'DESTROY_REASON', value: 'testing-complete'),
    ]
}

pipeline {
    agent {
        label 'min-noble-x64'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: 'latest',
            description: 'Helm chart branch to use. Leave "latest" to use latest released chart',
            name: 'PMM_CHART_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server',
            description: 'PMM Server image repository',
            name: 'IMAGE_REPO')
        string(
            defaultValue: '3-dev-latest',
            description: 'PMM Server image tag',
            name: 'IMAGE_TAG')
        choice(
            choices: ['latest', '4.19.6', '4.19.5', '4.19.4', '4.19.3', '4.19.2', '4.18.9', '4.18.8', '4.18.7', '4.18.6', '4.18.5', '4.17.9', '4.17.8', '4.17.7', '4.17.6', '4.17.5', '4.16.9', '4.16.8', '4.16.7', '4.16.6', '4.16.5'],
            description: 'OpenShift version to install (specific version or channel)',
            name: 'OPENSHIFT_VERSION')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    currentBuild.description = "OpenShift version ${env.OPENSHIFT_VERSION}. Image repo ${env.IMAGE_REPO}. Image tag ${env.IMAGE_TAG}"
                }
                // clean up workspace and fetch pmm-qa repository
                deleteDir()
                git poll: false, branch: PMM_QA_GIT_BRANCH, url: 'https://github.com/percona/pmm-qa.git'

                sh '''
                    sudo mkdir -p /srv/pmm-qa || :
                    sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git /srv/pmm-qa
                    sudo chmod -R 755 /srv/pmm-qa

                    sudo mkdir -p /opt/bats || :
                    sudo git clone https://github.com/bats-core/bats-core.git /opt/bats
                    sudo chmod -R 755 /opt/bats
                    sudo /opt/bats/install.sh /usr/local

                    cd /srv/pmm-qa/k8s
                    sudo ./setup_bats_libs.sh

                    sudo chown -R "$(id -un)":"$(id -gn)" /srv/pmm-qa

                    # Install kubectl
                    bash -x ./install_k8s_tools.sh --kubectl --helm --sudo
                    sleep 5
                    kubectl version --client
                '''
            }
        }
        stage('Create OpenShift Cluster') {
            steps {
                runOpenshiftClusterCreate(OPENSHIFT_VERSION)
            }
        }
        stage('Copy Artifacts') {
            steps {
                script {

                    copyArtifacts filter: "openshift-clusters/${env.FINAL_CLUSTER_NAME}/auth/kubeconfig", projectName: 'openshift-cluster-create', target: 'cluster-artifacts'
                    sh "ls -la cluster-artifacts/openshift-clusters/${env.FINAL_CLUSTER_NAME}/auth"
                    // Validate cluster access
                    def kubeconfig = "${WORKSPACE}/cluster-artifacts/openshift-clusters/${env.FINAL_CLUSTER_NAME}/auth/kubeconfig"
                    env.KUBECONFIG = kubeconfig

                    if (!fileExists(kubeconfig)) {
                        error "Failed to copy kubeconfig file from cluster creation job"
                    }

                    withEnv(["KUBECONFIG=${kubeconfig}"]) {
                        sh """
                            # Wait for up to 1 minute for the cluster to be accessible
                            for i in {1..6}; do
                                if kubectl get nodes &>/dev/null; then
                                    echo "Successfully connected to OpenShift cluster"
                                    kubectl get nodes -o wide
                                    exit 0
                                fi
                                echo "Waiting for cluster to be accessible... (attempt \$i/6)"
                                sleep 10
                            done
                            echo "Failed to connect to OpenShift cluster after 1 minute"
                            exit 1
                        """
                    }
                }
            }
        }
        stage('Run Helm Tests') {
            options {
                timeout(time: 40, unit: "MINUTES")
            }
            steps {
                sh """
                    cd /srv/pmm-qa/k8s
                    export BATS_LIB_PATH="/srv/pmm-qa/k8s/lib"
                    export IMAGE_REPO=${IMAGE_REPO}
                    export IMAGE_TAG=${IMAGE_TAG}
                    export PMM_CHART_BRANCH=${PMM_CHART_BRANCH}
                    bats --tap helm-test.bats
                """
            }
        }
    }
    post {
        always {
            // destroy cluster
            script {
                if (env.FINAL_CLUSTER_NAME) {
                    destroyOpenshift(env.FINAL_CLUSTER_NAME)
                }
            }
        }
    }
}
