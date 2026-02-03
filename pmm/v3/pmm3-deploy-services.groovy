library changelog: false, identifier: 'lib@master', retriever: modernSCM([
  $class: 'GitSCMSource',
  remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// ===================================================================================
// INFRASTRUCTURE PROVISIONING
// ===================================================================================

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, PMM_QA_GIT_BRANCH, ADMIN_PASSWORD = "admin") {
    stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PMM_DATA_RETENTION=48h'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'), 
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_UI_URL = "https://${env.VM_IP}/"
}

void runOVFStagingStart(String SERVER_VERSION, PMM_QA_GIT_BRANCH) {
    ovfStagingJob = build job: 'pmm3-ovf-staging-start', parameters: [
        string(name: 'OVA_VERSION', value: SERVER_VERSION),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
    ]
    env.OVF_INSTANCE_NAME = ovfStagingJob.buildVariables.VM_NAME
    env.OVF_INSTANCE_IP = ovfStagingJob.buildVariables.IP
    env.VM_IP = ovfStagingJob.buildVariables.IP 
    env.PMM_UI_URL = "https://${env.OVF_INSTANCE_IP}/"
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
  agent { label 'min-noble-x64' }

  parameters {
    // --- SERVER CONFIGURATION ---
    choice(name: 'SERVER_TYPE', choices: ['docker', 'ovf', 'ami', 'helm', 'ha'], description: 'Select PMM Server installation type')
    
    string(name: 'DOCKER_VERSION', defaultValue: 'perconalab/pmm-server:3-dev-latest', description: '[Docker/Helm/HA] Server Image. Ignored if OVF/AMI selected.')
    string(name: 'OVA_VERSION', defaultValue: 'PMM2-Server-OVF-3.0.0-latest.ova', description: '[OVF Only] OVA File Name. Ignored for others.')
    string(name: 'AMI_ID', defaultValue: 'ami-0669b163befffb6c3', description: '[AMI Only] AMI ID. Ignored for others.')
    
    // --- GLOBAL SETTINGS & VERSIONS ---
    string(name: 'CLIENT_VERSION', defaultValue: '3-dev-latest', description: 'PMM Client version (All types)')
    choice(name: 'ENABLE_PULL_MODE', choices: ['no', 'yes'], description: 'Enable Pull Mode for Clients')
    string(name: 'ADMIN_PASSWORD', defaultValue: 'pmm3admin!', description: 'Final Admin Password to set after provisioning')
    choice(name: 'DAYS', choices: ['1', '0', '2', '3', '4', '5', '7', '14', '30'], description: 'Auto-stop instances in X days (0 disables auto-removal)')
    
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
    string(name: 'WATCHTOWER_VERSION', defaultValue: 'perconalab/watchtower:dev-latest', description: 'Internal tool (Watchtower)')
    string(name: 'PMM_QA_GIT_BRANCH', defaultValue: 'main', description: 'QA Integration Git Branch (used by provisioner scripts)')
  }

  options {
    skipDefaultCheckout()
    buildDiscarder(logRotator(numToKeepStr: '30'))
  }

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

          currentBuild.description = "[${params.SERVER_TYPE}] Manual Environment. Expires in ${params.DAYS} days."
          
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
                  runStagingServer(DOCKER_VERSION, CLIENT_VERSION, '--help', 'no', '127.0.0.1', PMM_QA_GIT_BRANCH, 'admin')
              }
          }
          stage('Setup OVF Server') {
              when { expression { params.SERVER_TYPE == "ovf" } }
              steps {
                  runOVFStagingStart(OVA_VERSION, PMM_QA_GIT_BRANCH)
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
                
                writeFile file: "vm_server_${env.BUILD_NUMBER}.txt", text: "${env.PMM_SERVER_IP}"
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

        stage('valkey') {
          when { expression { return params.DEPLOY_VALKEY.toBoolean() } }
          steps {
            runClientWithRetry('--database valkey', 'valkey')
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

          try {
              sh """
                curl -k -X PUT -H "Content-Type: application/json" \
                -d '{"password":"${params.ADMIN_PASSWORD}"}' \
                https://admin:${currentPass}@${env.PMM_SERVER_IP}/graph/api/admin/users/1/password
              """
          } catch(e) {
              echo "Warning: Password update failed. Proceeding."
          }

          if (env.SLACK_DM) {
            slackSend botUser: true, channel: env.SLACK_DM, color: '#00FF00', 
            message: "[${env.JOB_NAME}]: All systems GO! 🚀\nOwner: @${env.OWNER}\nPMM URL: https://${env.PMM_SERVER_IP}\nPassword: ${params.ADMIN_PASSWORD}\nType: ${params.SERVER_TYPE}\nEnvironment is ready. Pipeline is holding for 24h.\nURL: ${env.BUILD_URL}"
          }
        }
      }
    }

    stage('Hold for early abort (24h)') {
      agent none
      steps {
        script {
          timeout(time: 1440, unit: 'MINUTES') {
            input(message: "Environment Ready! Click Abort to destroy VMs immediately, otherwise they expire in 24h.")
          }
        }
      }
    }
  }

  post {
    success {
      script {
        echo "Job finished normally."
        sh "rm -f vm_*_${env.BUILD_NUMBER}.txt"
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

void cleanupResources() {
    // 1. Clean Server based on Type
    if (env.SERVER_TYPE == "ovf" && env.OVF_INSTANCE_NAME) {
         build job: 'pmm-ovf-staging-stop', parameters: [ string(name: 'VM', value: env.OVF_INSTANCE_NAME) ]
    } 
    else if (env.SERVER_TYPE == "ami" && env.AMI_INSTANCE_ID) {
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

    // 2. Clean Clients based on tracking file
    def files = sh(script: "ls vm_*_${env.BUILD_NUMBER}.txt 2>/dev/null || true", returnStdout: true).trim().split(/\s+/)
    for (f in files) {
      if (f && !f.contains("server")) {
        def ip = readFile(f).trim()
        echo "Stopping Client VM with IP ${ip}"
        build job: 'aws-staging-stop', parameters: [ string(name: 'VM', value: ip) ]
      }
    }
    
    sh "rm -f vm_*_${env.BUILD_NUMBER}.txt"
    if (env.SLACK_DM) {
      slackSend botUser: true, channel: env.SLACK_DM, color: '#808080', message: "[${env.JOB_NAME}]: aborted/failed/cleaned up, owner: @${env.OWNER}\nURL: ${env.BUILD_URL}"
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
                string(name: 'ENABLE_PULL_MODE', value: params.ENABLE_PULL_MODE),
                string(name: 'ADMIN_PASSWORD', value: 'admin'),
                string(name: 'PMM_QA_GIT_BRANCH', value: params.PMM_QA_GIT_BRANCH),
                string(name: 'NOTIFY', value: 'false'),
                string(name: 'DAYS', value: params.DAYS),
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
                writeFile file: "vm_${filenameLabel}_${env.BUILD_NUMBER}.txt", text: "${b.buildVariables.IP}"
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