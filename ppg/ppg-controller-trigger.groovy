pipeline {
  agent any

  parameters {
    string(name: 'CONFIG_REPO', defaultValue: 'https://github.com/Percona-Lab/jenkins-pipelines.git', description: 'YAML job configuration repo')
    string(name: 'CONFIG_BRANCH', defaultValue: 'hetzner', description: 'Branch to pull job configuration from')
    string(name: 'CONFIG_FILE', defaultValue: 'postgres-packaging/job_configs/all-jobs-full.yaml', description: 'YAML file path inside repo')
  }

  environment {
    YAML_JSON = 'job_config.json'
  }

  stages {
    stage('Install jq and yq') {
      steps {
        sh '''
        if ! command -v yq > /dev/null; then
          echo "Installing yq..."
          sudo wget -qO /usr/local/bin/yq https://github.com/mikefarah/yq/releases/download/v4.47.1/yq_linux_amd64
          sudo chmod +x /usr/local/bin/yq
        fi
        if ! command -v jq > /dev/null; then
          echo "Installing jq..."
          sudo apt-get update && sudo apt-get install -y jq
        fi
        yq --version
        jq --version
        '''
      }
    }

    stage('Checkout Job Config Repo') {
      steps {
        dir('job-config') {
          git branch: "${params.CONFIG_BRANCH}", url: "${params.CONFIG_REPO}"
        }
        script {
          env.CONFIG_PATH = "job-config/${params.CONFIG_FILE}"
          sh """
            echo '[INFO] Converting YAML to JSON...'
            yq eval -o=json ${env.CONFIG_PATH} > ${env.YAML_JSON}
          """
        }
      }
    }

    stage('Run Critical Jobs (in order)') {
      steps {
        script {
          def criticalJobs = [
            'hetzner-postgresql-common-RELEASE',
            'hetzner-postgresql-server-autobuild-RELEASE',
            'hetzner-pg_percona_telemetry-autobuild-RELEASE'
          ]

          criticalJobs.each { jobKey ->
            def trigger = sh(script: "jq -r '.\"${jobKey}\".trigger // false' ${env.YAML_JSON}", returnStdout: true).trim()

            if (trigger != "true") {
              echo "[SKIP] ${jobKey} not marked for trigger"
              return
            }

            def rawParams = sh(script: "jq -c '.\"${jobKey}\".parameters // {}' ${env.YAML_JSON}", returnStdout: true).trim()
            def parsedParams = readJSON text: rawParams

            def buildParams = parsedParams.collect { k, v ->
              v instanceof Boolean ? booleanParam(name: k, value: v) : string(name: k, value: v.toString())
            }

            build job: parsedParams.job_name,
                  parameters: buildParams,
                  wait: true,
                  propagate: true
          }
        }
      }
    }

    stage('Run Remaining Jobs (in parallel)') {
      steps {
        script {
          def allKeys = sh(script: "jq -r 'keys[]' ${env.YAML_JSON}", returnStdout: true).trim().split('\n')
          def criticalJobs = [
            'hetzner-postgresql-common-RELEASE',
            'hetzner-postgresql-server-autobuild-RELEASE',
            'hetzner-pg_percona_telemetry-autobuild-RELEASE'
          ]

          def nonCritical = allKeys.findAll { !(it in criticalJobs) }

          def parallelJobs = [:]
          nonCritical.each { jobKey ->
            def trigger = sh(script: "jq -r '.\"${jobKey}\".trigger // false' ${env.YAML_JSON}", returnStdout: true).trim()

            if (trigger == "true") {
              parallelJobs[jobKey] = {
                def rawParams = sh(script: "jq -c '.\"${jobKey}\".parameters // {}' ${env.YAML_JSON}", returnStdout: true).trim()
                def parsedParams = readJSON text: rawParams

                def buildParams = parsedParams.collect { k, v ->
                  v instanceof Boolean ? booleanParam(name: k, value: v) : string(name: k, value: v.toString())
                }

                build job: parsedParams.job_name,
                      parameters: buildParams,
                      wait: true,
                      propagate: true
              }
            } else {
              echo "[SKIP] ${jobKey} not triggered"
            }
          }

          if (parallelJobs) {
            parallel parallelJobs
          } else {
            echo '[INFO] No remaining jobs to run in parallel.'
          }
        }
      }
    }
  }

  post {
    always {
      echo '[CLEANUP] Removing workspace files...'
      sh 'sudo rm -rf ./job-config ./job_config.json'
    }
    failure {
      slackSend(
        channel: '#releases-ci',
        color: '#FF0000',
        message: "❌ Jenkins job failed: ${env.JOB_NAME} [${env.BUILD_NUMBER}] - ${env.BUILD_URL}",
        tokenCredentialId: '030029ca-99c3-4a0d-b19f-0d6de94966f3'
      )
    }
  }
}
