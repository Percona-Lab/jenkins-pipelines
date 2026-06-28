library changelog: false, identifier: 'lib@master', retriever: modernSCM([
  $class: 'GitSCMSource',
  remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Track client VM IPs across stages without relying on workspace files.
// pmm.cd's min-noble-x64 template has maxTotalUses=1, so the build agent
// terminates after the work stages finish - workspace files are gone by
// the time the post block runs cleanup.
//
// Map keyed by client label avoids any mutation contention from the
// parallel 'Start Clients' stage (each branch writes only its own key)
// and is CPS-serializable, so the in-memory state survives Jenkins
// master restarts mid-build.
@groovy.transform.Field
Map<String, String> clientVMs = [:]

// ===================================================================================
// INFRASTRUCTURE PROVISIONING
// ===================================================================================

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, PMM_QA_GIT_BRANCH, ADMIN_PASSWORD = "admin", SSH_KEY = "") {
    stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PMM_DATA_RETENTION=48h -e PMM_ENABLE_TELEMETRY=0'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'SSH_KEY', value: SSH_KEY),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_UI_URL = "https://${env.VM_IP}/"
}

void runAMIStagingStart(String AMI_ID) {
    amiStagingJob = build job: 'pmm3-ami-staging-start', parameters: [
        string(name: 'AMI_ID', value: AMI_ID)
    ]
    env.AMI_INSTANCE_ID = amiStagingJob.buildVariables.INSTANCE_ID
    env.AMI_INSTANCE_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.AMI_ADMIN_PASS  = amiStagingJob.buildVariables.INSTANCE_ID
    env.VM_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.PMM_UI_URL = "https://${env.AMI_INSTANCE_IP}/"
}

def runOpenshiftClusterCreate(String OPENSHIFT_VERSION, DOCKER_VERSION, ADMIN_PASSWORD) {
    def clusterName = "manual-deploy-${env.BUILD_NUMBER}"
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
    def pmmHostname = pmmAddress.split("//")[1].replace("/", "")

    env.VM_IP = pmmHostname
    env.FINAL_CLUSTER_NAME = clusterCreateJob.buildVariables.FINAL_CLUSTER_NAME
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
    def pmmHostname = pmmAddress.split("//")[1].replace("/", "")

    env.VM_IP = pmmHostname
    env.CLUSTER_NAME = clusterCreateJob.buildVariables.CLUSTER_NAME
    env.PMM_UI_URL = "${pmmAddress}/"
}


// ===================================================================================
// MAIN PIPELINE
// ===================================================================================

pipeline {
  // 'agent none' at pipeline level releases the executor across the 24h Hold
  // stage. A top-level 'agent { label X }' would hold the executor for the
  // whole run; stage-level 'agent none' on the Hold cannot release it
  // (see JENKINS-59663). All work stages now live under a parent stage
  // 'Build Environment' that allocates one shared EC2 worker.
  agent none

  parameters {
    // --- SERVER CONFIGURATION ---
    choice(name: 'SERVER_TYPE', choices: ['docker', 'ami', 'helm', 'ha'], description: 'Select PMM Server installation type: docker (Basic Setup), ami (AWS EC2 AMI), helm (OpenShift), ha (High Availability).')

    string(name: 'DOCKER_VERSION', defaultValue: 'perconalab/pmm-server:3-dev-latest', description: '[Docker/Helm/HA] PMM Server docker image (image-name:version-tag, e.g., perconalab/pmm-server:3-dev-latest). Ignored if AMI selected.')
    string(name: 'AMI_ID', defaultValue: 'ami-0669b163befffb6c3', description: '[AMI Only] AWS AMI ID (e.g., ami-0669b163befffb6c3). Ignored for others.')

    // --- GLOBAL SETTINGS & VERSIONS ---
    string(name: 'CLIENT_VERSION', defaultValue: '3-dev-latest', description: 'PMM Client version: "3-dev-latest" (main branch), "latest" or "X.X.X" (release), "pmm3-rc" (Release Candidate), or a feature build URL provided in pmm-submodules repo (http://...).')
    choice(name: 'ENABLE_PULL_MODE', choices: ['no', 'yes'], description: 'Enable Pull Mode for Clients')
    string(name: 'ADMIN_PASSWORD', defaultValue: 'pmm3admin!', description: 'Admin password applied after provisioning.')
    string(name: 'SSH_KEY', defaultValue: '', description: 'Public SSH key for "ec2-user". Paste your OpenSSH public key to enable SSH access to AWS instances')

    // --- CLIENT SELECTION ---
    booleanParam(name: 'DEPLOY_EXTERNAL', defaultValue: false,
                 description: '<b>External Group:</b><br>Deploys 1 VM containing an External Node test and HAProxy Node test.')

    booleanParam(name: 'DEPLOY_MYSQL_GROUP', defaultValue: false,
                 description: '<b>MySQL Group:</b><br>Deploys 4 VMs covering: Group Replication, PXC Cluster, Async Replication, and a Standalone node (shared with Mongo PSS).')

    booleanParam(name: 'DEPLOY_POSTGRES_GROUP', defaultValue: false,
                 description: '<b>PostgreSQL Group:</b><br>Deploys 1 VM containing: Standard PostgreSQL, Percona Distribution for PGSQL, and Patroni High Availability stack.')

    booleanParam(name: 'DEPLOY_MONGO_GROUP', defaultValue: false,
                 description: '<b>MongoDB Group:</b><br>Deploys 2 VMs covering: PSMDB Sharded Cluster and a PSS Replica Set (shared with MySQL Single).')

    booleanParam(name: 'DEPLOY_VALKEY', defaultValue: false,
                 description: '<b>Valkey:</b><br>Deploys 1 VM containing a Valkey instance.')
    booleanParam(name: 'GENERATE_DASHBOARD_SCREENSHOTS', defaultValue: false,
                 description: 'If enabled, generate dashboard screenshots at the end of provisioning and skip the 24h hold stage.')
    string(name: 'SCREENSHOTS_SLACK_TARGET', defaultValue: '@catalina.adam', description: '@user or #channel or channelId:thread_ts.')

    // --- DB VERSIONS ---
    choice(name: 'PXC_VERSION', choices: ['8.0', '8.4', '5.7'], description: 'Version for PXC nodes')
    choice(name: 'PS_VERSION', choices: ['8.4', '8.0', '5.7', '5.7.30', '5.6'], description: 'Version for PS nodes')
    choice(name: 'MS_VERSION', choices: ['8.4', '8.0', '5.7', '5.6'], description: 'Version for MySQL nodes')
    choice(name: 'PGSQL_VERSION', choices: ['17', '18', '16', '15', '14', '13'], description: 'Version for PGSQL nodes')
    choice(name: 'PDPGSQL_VERSION', choices: ['17', '18', '16', '15', '14', '13'], description: 'Version for Percona Dist PGSQL nodes')
    choice(name: 'PSMDB_VERSION', choices: ['8.0', '7.0', '6.0', '5.0', '4.4'], description: 'Version for PSMDB nodes')
    choice(name: 'MODB_VERSION', choices: ['8.0', '7.0', '6.0', '5.0', '4.4'], description: 'Version for MongoDB nodes')

    // --- HELM/HA SPECIFIC ---
    string(name: 'HELM_CHART_BRANCH', defaultValue: 'pmmha-v3', description: '[HA Only] Helm chart branch')
    choice(name: 'OPENSHIFT_VERSION', choices: ['latest', '4.19.6', '4.17.9'], description: '[Helm Only] OpenShift Version')
    choice(name: 'K8S_VERSION', choices: ['1.34', '1.33', '1.32'], description: '[HA Only] K8s Version')

    // --- TECHNICAL/ADVANCED PARAMS ---
    string(name: 'WATCHTOWER_VERSION', defaultValue: 'perconalab/watchtower:dev-latest', description: 'WatchTower docker image (image-name:version-tag, e.g., perconalab/watchtower:dev-latest).')
    string(name: 'PMM_QA_GIT_BRANCH', defaultValue: 'main', description: 'pmm-qa Git branch (used by provisioner scripts)')
  }

  options {
    skipDefaultCheckout()
    buildDiscarder(logRotator(numToKeepStr: '30'))
  }

  stages {
    stage('Build Environment') {
      agent { label 'min-noble-x64' }

      stages {
        stage('Prepare') {
          steps {
            script {
              getPMMBuildParams('pmm-')
              def ownerHandle = env.OWNER?.trim() ?: env.OWNER_SLACK?.trim()

              if (!ownerHandle) {
                 try {
                   def cause = currentBuild?.rawBuild?.getCause(hudson.model.Cause$UserIdCause)
                   if (cause?.userId) { ownerHandle = cause.userId }
                 } catch (ignored) { }
              }
              ownerHandle = ownerHandle?.replaceFirst('^@','')
              env.OWNER    = ownerHandle ?: 'unknown'
              env.SLACK_DM = ownerHandle ? "@${ownerHandle}" : ""

              currentBuild.description = "[${params.SERVER_TYPE}] Manual Environment. Expires in 1 day."

              if (env.SLACK_DM) {
                slackSend botUser: true, channel: env.SLACK_DM, color: '#0000FF', message: "[${env.JOB_NAME}]: build started (${params.SERVER_TYPE}), owner: @${env.OWNER}, URL: ${env.BUILD_URL}"
              }
            }
          }
        }

        stage('Start Server') {
          parallel {
              stage('Setup Docker Server') {
                  when { expression { params.SERVER_TYPE == "docker" } }
                  steps {
                      runStagingServer(DOCKER_VERSION, CLIENT_VERSION, '--help', 'no', '127.0.0.1', PMM_QA_GIT_BRANCH, 'admin', SSH_KEY)
                  }
              }
              stage('Setup AMI Server') {
                  when { expression { params.SERVER_TYPE == "ami" } }
                  steps {
                      runAMIStagingStart(AMI_ID)
                  }
              }
              stage('Setup Helm Server') {
                  when { expression { params.SERVER_TYPE == "helm" } }
                  steps {
                      runOpenshiftClusterCreate(OPENSHIFT_VERSION, DOCKER_VERSION, 'admin')
                  }
              }
              stage('Setup HA Server') {
                  when { expression { params.SERVER_TYPE == "ha" } }
                  steps {
                      runHAClusterCreate(K8S_VERSION, DOCKER_VERSION, HELM_CHART_BRANCH, 'admin')
                  }
              }
          }
        }

        stage('Normalize Server Info') {
            steps {
                script {
                    if (env.VM_IP) {
                        env.PMM_SERVER_IP = env.VM_IP
                    } else {
                        error "Could not determine Server IP from the helper functions. Pipeline cannot proceed."
                    }

                    echo "PMM Server Ready. IP: ${env.PMM_SERVER_IP}"

                    if (env.SLACK_DM) {
                      slackSend botUser: true, channel: env.SLACK_DM, color: '#439FE0', message: "[${env.JOB_NAME}]: Server (${params.SERVER_TYPE}) ready: https://${env.PMM_SERVER_IP}\nStarting clients... URL: ${env.BUILD_URL}"
                    }
                }
            }
        }

        stage('Start Clients in parallel') {
          parallel {
            stage('external-haproxy') {
              when { expression { return params.DEPLOY_EXTERNAL.toBoolean() } }
              steps {
                runClientWithRetry('--database external --database haproxy', 'external-haproxy')
              }
            }

            stage('ps-gr-mysql') {
              when { expression { return params.DEPLOY_MYSQL_GROUP.toBoolean() } }
              steps {
                runClientWithRetry('--database ps,SETUP_TYPE=gr --database mysql', 'ps-gr-mysql')
              }
            }

            stage('ps-repl-pxc') {
              when { expression { return params.DEPLOY_MYSQL_GROUP.toBoolean() } }
              steps {
                runClientWithRetry('--database ps,SETUP_TYPE=replication --database pxc', 'ps-repl-pxc')
              }
            }

            stage('ps-single+psmdb-pss') {
              when { expression { return params.DEPLOY_MYSQL_GROUP.toBoolean() || params.DEPLOY_MONGO_GROUP.toBoolean() } }
              steps {
                runClientWithRetry('--database ps,QUERY_SOURCE=slowlog,MY_ROCKS=true --database psmdb,SETUP_TYPE=pss', 'ps-single-psmdb')
              }
            }

            stage('pgsql-stack') {
              when { expression { return params.DEPLOY_POSTGRES_GROUP.toBoolean() } }
              steps {
                runClientWithRetry('--database pdpgsql --database pgsql --database pdpgsql,SETUP_TYPE=patroni', 'pgsql-stack')
              }
            }

            stage('psmdb-sharding') {
              when { expression { return params.DEPLOY_MONGO_GROUP.toBoolean() } }
              steps {
                runClientWithRetry('--database psmdb,SETUP_TYPE=sharding', 'psmdb-sharding')
              }
            }

            stage('pxc-extra') {
              when { expression { return params.DEPLOY_MYSQL_GROUP.toBoolean() } }
              steps {
                runClientWithRetry('--database pxc', 'pxc-extra')
              }
            }

            stage('valkey-psmdb-inmemory') {
              when { expression { return params.DEPLOY_VALKEY.toBoolean() || params.DEPLOY_MONGO_GROUP.toBoolean() } }
              steps {
                runClientWithRetry('--database valkey --database psmdb,SETUP_TYPE=pss,STORAGE_ENGINE=inMemory', 'valkey-psmdb-inmemory')
              }
            }
          }
        }

        stage('Set Final Password & Notify') {
          steps {
            script {
              echo "Environment is stable. Updating password..."

              def currentPass = 'admin'
              if (env.AMI_ADMIN_PASS) {
                 currentPass = env.AMI_ADMIN_PASS
              }

              // Pass values via withEnv so the password is not Groovy-interpolated
              // into the shell command and does not appear in the build log.
              withEnv([
                  "GR_NEW_PASS=${params.ADMIN_PASSWORD}",
                  "GR_CURR_PASS=${currentPass}",
                  "GR_HOST=${env.PMM_SERVER_IP}"
              ]) {
                  try {
                      sh '''
                        set +x
                        curl -fsk -X PUT -H "Content-Type: application/json" \
                          -d "{\\"password\\":\\"${GR_NEW_PASS}\\"}" \
                          "https://admin:${GR_CURR_PASS}@${GR_HOST}/graph/api/admin/users/1/password"
                      '''
                  } catch(e) {
                      echo "Warning: Password update failed: ${e.message}. Proceeding."
                  }
              }

              if (env.SLACK_DM) {
                slackSend botUser: true, channel: env.SLACK_DM, color: '#00FF00',
                message: "[${env.JOB_NAME}]: All systems GO! 🚀\nOwner: @${env.OWNER}\nPMM URL: https://${env.PMM_SERVER_IP}\nPassword: ${params.ADMIN_PASSWORD}\nType: ${params.SERVER_TYPE}\nEnvironment is ready. Pipeline is holding for 24h.\nURL: ${env.BUILD_URL}"
              }
            }
          }
        }
        stage('Generate Dashboard Screenshots') {
          when {
            expression { params.GENERATE_DASHBOARD_SCREENSHOTS.toBoolean() }
          }
          steps {
            script {
              def dockerVersion = params.DOCKER_VERSION?.trim() ?: ''
              if (!dockerVersion.contains(':')) {
                error "DOCKER_VERSION must be in image:tag format (example: perconalab/pmm-server:3-dev-latest)"
              }
              def dockerTag = dockerVersion.substring(dockerVersion.lastIndexOf(':') + 1)
              def zipName = "screenshots-${dockerTag}.zip"

              // Pass Groovy values via withEnv so the shell sees plain
              // POSIX variables — no Groovy interpolation inside sh.
              withEnv([
                  "GR_QA_BRANCH=${params.PMM_QA_GIT_BRANCH}",
                  "GR_PMM_UI_URL=${env.PMM_UI_URL}",
                  "GR_ADMIN_PASSWORD=${params.ADMIN_PASSWORD}",
                  "GR_WORKSPACE=${env.WORKSPACE}",
                  "GR_ZIP_NAME=${zipName}"
              ]) {
                sh '''
                  set -eu
                  git clone --depth 1 --branch "${GR_QA_BRANCH}" https://github.com/percona/pmm-qa.git
                  cd pmm-qa/e2e_tests
                  curl -fsSL https://deb.nodesource.com/setup_22.x -o /tmp/nodesource-setup-22.sh
                  sudo -E bash /tmp/nodesource-setup-22.sh
                  sudo apt-get install -y nodejs gettext zip
                  npm ci
                  npx playwright install
                  sudo npx playwright install-deps
                  set +e
                  PMM_UI_URL="${GR_PMM_UI_URL}" ADMIN_PASSWORD="${GR_ADMIN_PASSWORD}" npx playwright test --config=screenshots.config.ts
                  echo $? > "${GR_WORKSPACE}/.playwright-exit"
                  set -e
                  cd "${GR_WORKSPACE}"
                  zip -rq "${GR_ZIP_NAME}" pmm-qa/e2e_tests/screenshots
                '''
                def playwrightExit = readFile('.playwright-exit').trim() as int
                if (playwrightExit != 0) {
                  unstable('Playwright screenshot tests had failures; partial screenshots were zipped and will be uploaded.')
                }
              }
              def snapTarget = params.SCREENSHOTS_SLACK_TARGET?.trim() ?: ''
              if (snapTarget.contains(':')) {
                slackUploadFile channel: snapTarget, failOnError: true, filePath: zipName
              } else {
                def slackResponse = slackSend(botUser: true, channel: snapTarget, message: "PMM dashboard screenshots for (${dockerVersion})", sendAsText: true)
                def uploadCh = snapTarget.startsWith('#') ? "${slackResponse.channelId}:${slackResponse.ts}" : slackResponse.channelId
                slackUploadFile channel: uploadCh, failOnError: true, filePath: zipName
              }
            }
          }
        }
      } // end inner stages
    } // end Build Environment

    // Hold runs on pipeline-level 'agent none' (flyweight): no EC2 executor
    // is pinned during the wait, so other builds can use min-noble-x64.
    stage('Hold for early abort (24h)') {
      when {
        expression { !params.GENERATE_DASHBOARD_SCREENSHOTS.toBoolean() }
      }
      steps {
        script {
          timeout(time: 1440, unit: 'MINUTES') {
            input(message: "Environment Ready! Click Abort to destroy VMs immediately, otherwise they expire in 24h.")
          }
        }
      }
    }
  }

  // Post runs on flyweight. cleanupResources() and the success handler only
  // do build job:, echo, slackSend - no sh - so no node{} wrapping needed.
  post {
    success {
      script {
        echo "Job finished normally."
        if (params.GENERATE_DASHBOARD_SCREENSHOTS.toBoolean()) {
          echo "Screenshot run completed: cleaning up resources..."
          cleanupResources('completed/cleaned up')
        }
      }
    }
    failure {
      script {
        echo "Build failed: cleaning up resources..."
        cleanupResources()
      }
    }
    aborted {
      script {
        echo "Build aborted: cleaning up resources..."
        cleanupResources()
      }
    }
    always {
      echo "Orchestrator completed."
    }
  }
}

// ===================================================================================
// CUSTOM HELPERS (Cleanup & Retry Logic)
// ===================================================================================

void cleanupResources(String slackStatus = 'aborted/failed/cleaned up') {
    // 1. Clean Server based on Type
    if (env.SERVER_TYPE == "ami" && env.AMI_INSTANCE_ID) {
         build job: 'pmm3-ami-staging-stop', parameters: [ string(name: 'AMI_ID', value: env.AMI_INSTANCE_ID) ]
    }
    else if (env.SERVER_TYPE == "helm" && env.FINAL_CLUSTER_NAME) {
         build job: 'openshift-cluster-destroy', parameters: [
            string(name: 'CLUSTER_NAME', value: env.FINAL_CLUSTER_NAME),
            string(name: 'DESTROY_REASON', value: 'testing-complete')
         ]
    }
    else if (env.SERVER_TYPE == "ha" && env.CLUSTER_NAME) {
         build job: 'pmm3-ha-eks-cleanup', parameters: [
            string(name: 'ACTION', value: 'DELETE_CLUSTER'),
            string(name: 'CLUSTER_NAME', value: env.CLUSTER_NAME)
         ]
    }
    else if (env.VM_NAME) {
         build job: 'aws-staging-stop', parameters: [ string(name: 'VM', value: env.VM_NAME) ]
    }

    // 2. Clean Clients from the in-memory map populated by runClientWithRetry.
    // Used to be a workspace-file scan, but maxTotalUses=1 means the build
    // agent is gone before this post block runs.
    clientVMs.each { label, ip ->
        echo "Stopping Client VM (${label}) with IP ${ip}"
        build job: 'aws-staging-stop', parameters: [ string(name: 'VM', value: ip) ]
    }

    if (env.SLACK_DM) {
      slackSend botUser: true, channel: env.SLACK_DM, color: '#808080', message: "[${env.JOB_NAME}]: ${slackStatus}, owner: @${env.OWNER}\nURL: ${env.BUILD_URL}"
    }
}

void runClientWithRetry(String clientsString, String filenameLabel) {
    script {
        int retries = 3
        int count = 0
        boolean success = false

        while (count < retries && !success) {
            count++
            def b = build job: 'pmm3-aws-staging-start', propagate: false, parameters: [
                string(name: 'DOCKER_VERSION', value: params.DOCKER_VERSION),
                string(name: 'CLIENT_VERSION', value: params.CLIENT_VERSION),
                string(name: 'CLIENT_INSTANCE', value: 'yes'),
                string(name: 'SERVER_IP', value: env.PMM_SERVER_IP),
                string(name: 'SSH_KEY', value: params.SSH_KEY),
                string(name: 'ENABLE_PULL_MODE', value: params.ENABLE_PULL_MODE),
                string(name: 'ADMIN_PASSWORD', value: 'admin'),
                string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
                string(name: 'NOTIFY', value: 'false'),
                string(name: 'DAYS', value: '1'),
                string(name: 'PXC_VERSION', value: params.PXC_VERSION),
                string(name: 'PS_VERSION', value: params.PS_VERSION),
                string(name: 'MS_VERSION', value: params.MS_VERSION),
                string(name: 'PGSQL_VERSION', value: params.PGSQL_VERSION),
                string(name: 'PDPGSQL_VERSION', value: params.PDPGSQL_VERSION),
                string(name: 'PSMDB_VERSION', value: params.PSMDB_VERSION),
                string(name: 'MODB_VERSION', value: params.MODB_VERSION),
                text(name: 'CLIENTS', value: clientsString),
            ]

            if (b.result == 'SUCCESS') {
                success = true
                clientVMs[filenameLabel] = b.buildVariables.IP
                echo "Client ${filenameLabel} started successfully."
            } else {
                echo "Client ${filenameLabel} failed on attempt ${count}. Retrying..."
                sleep 10
            }
        }

        if (!success) {
            error "Client ${filenameLabel} failed after ${retries} attempts."
        }
    }
}
