library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def defaultAmiId = pmmVersion('v3-ami').values()[-1]

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, PMM_QA_GIT_BRANCH, ADMIN_PASSWORD = "admin") {
    stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PMM_DATA_RETENTION=48h -e PMM_ENABLE_TELEMETRY=0'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
        env.PMM_UI_URL = "https://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${VM_IP}"
        env.PMM_UI_URL = "https://${VM_IP}/"
    }
}

def runOpenshiftClusterCreate(String OPENSHIFT_VERSION, DOCKER_VERSION, ADMIN_PASSWORD) {
    def clusterName = "nightly-test-${env.BUILD_NUMBER}"
    def pmmImageRepo = DOCKER_VERSION.split(":")[0]
    def pmmImageTag = DOCKER_VERSION.split(":")[1]

    clusterCreateJob = build job: 'openshift-cluster-create', parameters: [
        string(name: 'CLUSTER_NAME', value: clusterName),
        string(name: 'OPENSHIFT_VERSION', value: OPENSHIFT_VERSION),
        booleanParam(name: 'DEPLOY_PMM', value: true),
        string(name: 'TEAM_NAME', value: 'pmm'),
        string(name: 'PRODUCT_TAG', value: 'pmm'),
        string(name: 'PMM_ADMIN_PASSWORD', value: ADMIN_PASSWORD),
        string(name: 'PMM_IMAGE_REPOSITORY', value: pmmImageRepo),
        string(name: 'PMM_IMAGE_TAG', value: pmmImageTag),
    ]

    def pmmAddress = clusterCreateJob.buildVariables.PMM_URL
    def pmmHostname = pmmAddress.split("//")[1]

    env.VM_IP = pmmHostname
    env.VM_NAME = clusterCreateJob.buildVariables.VM_NAME
    env.WORK_DIR = clusterCreateJob.buildVariables.WORK_DIR
    env.FINAL_CLUSTER_NAME = clusterCreateJob.buildVariables.FINAL_CLUSTER_NAME
    env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${pmmHostname}"
    env.PMM_UI_URL = "${pmmAddress}/"
}

def runHAClusterCreate(String K8S_VERSION, DOCKER_VERSION, HELM_CHART_BRANCH, ADMIN_PASSWORD) {
    def pmmImageTag = DOCKER_VERSION.split(":")[1]

    clusterCreateJob = build job: 'pmm3-ha-eks', parameters: [
        string(name: 'K8S_VERSION', value: K8S_VERSION),
        string(name: 'HELM_CHART_BRANCH', value: HELM_CHART_BRANCH),
        string(name: 'PMM_IMAGE_TAG', value: pmmImageTag),
        string(name: 'PMM_ADMIN_PASSWORD', value: ADMIN_PASSWORD),
        booleanParam(name: 'ENABLE_EXTERNAL_ACCESS', value: true),
        string(name: 'RETENTION_DAYS', value: '1'),
    ]

    def pmmAddress = clusterCreateJob.buildVariables.PMM_URL
    def pmmHostname = pmmAddress.split("//")[1]

    env.VM_IP = pmmHostname
    env.VM_NAME = clusterCreateJob.buildVariables.VM_NAME
    env.WORK_DIR = clusterCreateJob.buildVariables.WORK_DIR
    env.CLUSTER_NAME = clusterCreateJob.buildVariables.CLUSTER_NAME
    env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${pmmHostname}"
    env.PMM_UI_URL = "${pmmAddress}/"
}

void runAMIStagingStart(String AMI_ID) {
    amiStagingJob = build job: 'pmm3-ami-staging-start', parameters: [
        string(name: 'AMI_ID', value: AMI_ID)
    ]
    env.AMI_INSTANCE_ID = amiStagingJob.buildVariables.INSTANCE_ID
    env.AMI_INSTANCE_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.ADMIN_PASSWORD = amiStagingJob.buildVariables.INSTANCE_ID
    env.VM_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.VM_NAME = amiStagingJob.buildVariables.INSTANCE_ID
    env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${AMI_INSTANCE_IP}"
    env.PMM_UI_URL = "https://${AMI_INSTANCE_IP}/"
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository (used both for the GH workflow ref and the client setup checkout inside the workers).',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['docker', 'ami', 'helm', 'ha'],
            description: 'PMM Server installation type.',
            name: 'SERVER_TYPE')
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: defaultAmiId,
            description: '[AMI only] AWS AMI ID (e.g., ami-0669b163befffb6c3).',
            name: 'AMI_ID')
        string(
            defaultValue: 'latest-tarball',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: 'pmm3admin!',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: 'main',
            description: 'HA setup branch of percona-helm-charts repo',
            name: 'HELM_CHART_BRANCH')
        choice(
            choices: ['latest', '4.19.6', '4.19.5', '4.19.4', '4.19.3', '4.19.2', '4.18.9', '4.18.8', '4.18.7', '4.18.6', '4.18.5', '4.17.9', '4.17.8', '4.17.7', '4.17.6', '4.17.5', '4.16.9', '4.16.8', '4.16.7', '4.16.6', '4.16.5'],
            description: 'OpenShift version to install (specific version or channel)',
            name: 'OPENSHIFT_VERSION')
        choice(
            choices: ['1.34', '1.33', '1.32', '1.31', '1.30'],
            description: 'HA setup Kubernetes cluster version',
            name: 'K8S_VERSION')
        string(
            defaultValue: '100',
            description: 'Launchable subset confidence % for selecting tests to run.',
            name: 'PTS_CONFIDENCE')
    }
    options {
        skipDefaultCheckout()
    }
    triggers { cron('0 0 * * *') }
    stages {
        stage('Prepare') {
            steps {
                script {
                    currentBuild.description = "[GHA] ${env.SERVER_TYPE} Server: ${env.DOCKER_VERSION}. Client: ${env.CLIENT_VERSION}"
                }
                deleteDir()
                git poll: false, branch: PMM_QA_GIT_BRANCH, url: 'https://github.com/percona/pmm-qa.git'
                slackSend botUser: true, channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Start Server') {
            parallel {
                stage('Setup Docker Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "docker" }
                    }
                    steps {
                        runStagingServer(DOCKER_VERSION, CLIENT_VERSION, '--help', 'no', '127.0.0.1', PMM_QA_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
                stage('Setup AMI PMM Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "ami" }
                    }
                    steps {
                        runAMIStagingStart(AMI_ID)
                    }
                }
                stage('Setup Helm PMM Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "helm" }
                    }
                    steps {
                        runOpenshiftClusterCreate(OPENSHIFT_VERSION, DOCKER_VERSION, ADMIN_PASSWORD)
                    }
                }
                stage('Setup HA PMM Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "ha" }
                    }
                    steps {
                        runHAClusterCreate(K8S_VERSION, DOCKER_VERSION, HELM_CHART_BRANCH, ADMIN_PASSWORD)
                    }
                }
            }
        }
        stage('Sanity check') {
            steps {
                sh '''
                    timeout 100 bash -c 'while [[ ! "$(curl -i -s --insecure -w "%{http_code}" \${PMM_URL}/ping)" =~ "200" ]]; do sleep 5; echo "$(curl -i -s --insecure -w "%{http_code}" \${PMM_URL}/ping)"; done' || false
                '''
            }
        }
        stage('Disable upgrade on nightly PMM instance') {
            steps {
                sh '''
                    #!/bin/bash
                        curl --location -i --insecure --request PUT \
                        --user "admin:$ADMIN_PASSWORD" \
                        "$PMM_UI_URL/v1/server/settings" \
                        --header "Content-Type: application/json" \
                        --data '{ "enable_updates": false }'
                '''
            }
        }
        stage('Trigger nightly GH Actions matrix') {
            options {
                timeout(time: 360, unit: "MINUTES")
            }
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GH_TOKEN')]) {
                    sh '''
                        set -eux
                        # Build dispatch body. The workflow_dispatch payload only takes
                        # the ref + the inputs object — secrets are looked up server-side.
                        BODY=$(jq -nc \
                          --arg ref "$PMM_QA_GIT_BRANCH" \
                          --arg server "$VM_IP" \
                          --arg client "$CLIENT_VERSION" \
                          --arg image "$DOCKER_VERSION" \
                          --arg branch "$PMM_QA_GIT_BRANCH" \
                          --arg pwd "$ADMIN_PASSWORD" \
                          --arg confidence "${PTS_CONFIDENCE}%" \
                          --arg installation_type "$SERVER_TYPE" \
                          '{
                             ref: $ref,
                             inputs: {
                               pmm_server_address: $server,
                               pmm_client_version: $client,
                               pmm_server_image: $image,
                               pmm_qa_branch: $branch,
                               admin_password: $pwd,
                               launchable_confidence: $confidence,
                               installation_type: $installation_type
                             }
                           }')

                        echo "Dispatching nightly-e2e-tests-matrix.yml on percona/pmm-qa (ref=${PMM_QA_GIT_BRANCH}) with body: ${BODY}"

                        # Capture the timestamp BEFORE dispatching so we can disambiguate
                        # the new run from any in-flight runs.
                        DISPATCH_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)

                        curl -fsSL -X POST \
                            -H "Authorization: token ${GH_TOKEN}" \
                            -H "Accept: application/vnd.github+json" \
                            -H "X-GitHub-Api-Version: 2022-11-28" \
                            "https://api.github.com/repos/percona/pmm-qa/actions/workflows/nightly-e2e-tests-matrix.yml/dispatches" \
                            -d "${BODY}"

                        chmod +x .github/scripts/wait-for-gh-run.sh .github/scripts/wait-for-gh-run-completion.sh

                        RUN_ID=$(.github/scripts/wait-for-gh-run.sh \
                            "percona/pmm-qa" \
                            "nightly-e2e-tests-matrix.yml" \
                            "${PMM_QA_GIT_BRANCH}" \
                            "${DISPATCH_AT}")
                        echo "GH Actions run id: ${RUN_ID}"
                        echo "${RUN_ID}" > gh_run_id.txt

                        .github/scripts/wait-for-gh-run-completion.sh "percona/pmm-qa" "${RUN_ID}"
                    '''
                }
            }
        }
    }
    post {
        always {
            script {
                // Always tear down the server first — server lifecycle must
                // match this build's outcome regardless of GH workflow result.
                if (env.SERVER_TYPE == "ami" && env.AMI_INSTANCE_ID) {
                    build job: 'pmm3-ami-staging-stop', parameters: [
                        string(name: 'AMI_ID', value: env.AMI_INSTANCE_ID),
                    ]
                }
                if (env.SERVER_TYPE == "helm" && env.FINAL_CLUSTER_NAME) {
                    build job: 'openshift-cluster-destroy', parameters: [
                        string(name: 'CLUSTER_NAME', value: env.FINAL_CLUSTER_NAME),
                        string(name: 'DESTROY_REASON', value: 'testing-complete'),
                        booleanParam(name: 'FORCE_MODE', value: true),
                    ]
                }
                if (env.SERVER_TYPE == "ha" && env.CLUSTER_NAME) {
                    build job: 'pmm3-ha-eks-cleanup', parameters: [
                        string(name: 'ACTION', value: 'DELETE_CLUSTER'),
                        string(name: 'CLUSTER_NAME', value: env.CLUSTER_NAME),
                    ]
                }
                if (env.VM_NAME && env.SERVER_TYPE == "docker") {
                    destroyStaging(env.VM_NAME)
                }
            }
        }
        success {
            script {
                slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00',
                    message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
            }
        }
        failure {
            script {
                def runId = ''
                try { runId = readFile('gh_run_id.txt').trim() } catch (ignored) {}
                def runLink = runId ? "https://github.com/percona/pmm-qa/actions/runs/${runId}" : "(GH run id not captured)"
                slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000',
                    message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}\nGH Actions run: ${runLink}"
            }
        }
    }
}
