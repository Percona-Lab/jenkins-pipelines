library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh "sudo rm -rf ./* || true"
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }

    parameters {
        string(name: 'CLOUD', defaultValue: 'Hetzner', description: 'Target cloud: Hetzner or AWS')
        string(name: 'CONFIG_REPO', defaultValue: 'https://github.com/percona/postgres-packaging.git', description: 'Git repo with YAML config')
        string(name: 'CONFIG_BRANCH', defaultValue: 'main', description: 'Branch to pull YAML config from')
        string(name: 'CONFIG_FILE', defaultValue: 'job_configs/all-jobs-full.yaml', description: 'Relative path to job config YAML file')
    }

    environment {
        CONFIG_DIR = 'job-config'
        JSON_OUT = 'job_config.json'
    }

    stages {
        stage('Install Tools') {
            steps {
                sh '''
                    if ! command -v jq >/dev/null; then
                        echo "[INFO] Installing jq..."
                        sudo apt-get update && sudo apt-get install -y jq
                    fi
                    if ! command -v yq >/dev/null; then
                        echo "[INFO] Installing yq..."
                        sudo wget -qO /usr/local/bin/yq https://github.com/mikefarah/yq/releases/download/v4.47.1/yq_linux_amd64
                        sudo chmod +x /usr/local/bin/yq
                    fi
                    jq --version
                    yq --version
                '''
            }
        }

        stage('Checkout Config') {
            steps {
                script {
                    echo "[INFO] Cloning CONFIG_REPO: ${params.CONFIG_REPO} (${params.CONFIG_BRANCH})"
                }
                dir("${CONFIG_DIR}") {
                    git branch: params.CONFIG_BRANCH, url: params.CONFIG_REPO
                }
                script {
                    def yamlPath = "${CONFIG_DIR}/${params.CONFIG_FILE}"
                    if (!fileExists(yamlPath)) {
                        error "❌ Config file not found: ${yamlPath}"
                    }
                    echo "[✓] YAML config found: ${yamlPath}"

                    sh """
                        echo '[INFO] Converting YAML to JSON...'
                        yq eval -o=json '${yamlPath}' > ${JSON_OUT}
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
                        def trigger = sh(script: "jq -r '.\"${jobKey}\".trigger // false' ${JSON_OUT}", returnStdout: true).trim()
                        if (trigger != "true") {
                            echo "[SKIP] ${jobKey} not marked for triggering"
                            return
                        }

                        def paramJson = sh(script: "jq -c '.\"${jobKey}\".parameters' ${JSON_OUT}", returnStdout: true).trim()
                        def parsed = readJSON text: paramJson

                        def paramList = parsed.collect { k, v ->
                            v instanceof Boolean ? booleanParam(name: k, value: v) : string(name: k, value: v.toString())
                        }

                        build job: parsed.job_name,
                              parameters: paramList,
                              wait: true,
                              propagate: true
                    }
                }
            }
        }

        stage('Run Remaining Jobs (in parallel)') {
            steps {
                script {
                    def critical = [
                        'hetzner-postgresql-common-RELEASE',
                        'hetzner-postgresql-server-autobuild-RELEASE',
                        'hetzner-pg_percona_telemetry-autobuild-RELEASE'
                    ]

                    def keys = sh(script: "jq -r 'keys[]' ${JSON_OUT}", returnStdout: true).trim().split('\n')
                    def parallelJobs = [:]

                    keys.each { jobKey ->
                        if (critical.contains(jobKey)) return

                        def trigger = sh(script: "jq -r '.\"${jobKey}\".trigger // false' ${JSON_OUT}", returnStdout: true).trim()
                        if (trigger != "true") {
                            echo "[SKIP] ${jobKey} not marked for triggering"
                            return
                        }

                        parallelJobs[jobKey] = {
                            def paramJson = sh(script: "jq -c '.\"${jobKey}\".parameters' ${JSON_OUT}", returnStdout: true).trim()
                            def parsed = readJSON text: paramJson

                            def paramList = parsed.collect { k, v ->
                                v instanceof Boolean ? booleanParam(name: k, value: v) : string(name: k, value: v.toString())
                            }

                            build job: parsed.job_name,
                                  parameters: paramList,
                                  wait: parsed.wait ?: false,
                                  propagate: true
                        }
                    }

                    if (parallelJobs) {
                        parallel parallelJobs
                    } else {
                        echo "[✓] No remaining jobs to run"
                    }
                }
            }
        }
    }

    post {
        success {
            slackNotify(
                "#releases-ci",
                "#00FF00",
                "[${env.JOB_NAME}]: ✅ Build finished successfully for branch ${env.GIT_BRANCH} → ${env.BUILD_URL}"
            )
            script {
                currentBuild.description = "Built on ${env.GIT_BRANCH}"
            }
            cleanUpWS()
        }

        failure {
            slackNotify(
                "#releases-ci",
                "#FF0000",
                "[${env.JOB_NAME}]: ❌ Build failed for branch ${env.GIT_BRANCH} → ${env.BUILD_URL}"
            )
            cleanUpWS()
        }

        always {
            cleanUpWS()
        }
    }
}
