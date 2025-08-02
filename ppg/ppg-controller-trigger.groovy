library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh "sudo rm -rf ./* || true"
}

def AWS_STASH_PATH
def jobsConfig = [:]

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }

    parameters {
        string(name: 'CONFIG_REPO', defaultValue: 'https://github.com/Percona-Lab/postgres-packaging', description: 'Job config repo')
        string(name: 'CONFIG_BRANCH', defaultValue: 'main', description: 'Job config branch')
        string(name: 'CLOUD', defaultValue: 'Hetzner', description: 'Cloud target')
    }

    environment {
        CONFIG_FILE = 'postgres-packaging/job_configs/all-jobs-full.yaml'
    }

    stages {
        stage('Checkout Job Config Repo') {
            steps {
                script {
                    echo "[INFO] Installing jq and yq"
                    sh "sudo apt-get update -qq"
                    sh "sudo apt-get install -y jq"

                    sh "wget -q https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64"
                    sh "sudo mv yq_linux_amd64 /usr/local/bin/yq"
                    sh "sudo chmod +x /usr/local/bin/yq"

                    sh "echo '[✓] jq version: \$(jq --version)'"
                    sh "echo '[✓] yq version: \$(yq --version)'"

                    echo "[INFO] Cloning CONFIG_REPO: ${params.CONFIG_REPO} (${params.CONFIG_BRANCH})"
                    dir('postgres-packaging') {
                        git branch: params.CONFIG_BRANCH, url: params.CONFIG_REPO
                    }

                    if (!fileExists(env.CONFIG_FILE)) {
                        error "❌ Config file not found: ${env.CONFIG_FILE}"
                    }

                    echo "[✓] Repo checked out and config file exists."
                    def criticalJobs = [
                        'hetzner-postgresql-common-RELEASE',
                        'hetzner-postgresql-server-autobuild-RELEASE',
                        'hetzner-pg_percona_telemetry-autobuild-RELEASE'
                    ]

                    criticalJobs.each { jobKey ->
                        def exists = sh(script: "yq eval '.\"${jobKey}\"' ${env.CONFIG_FILE} > /dev/null 2>&1", returnStatus: true) == 0
                        if (!exists) {
                            echo "[SKIP] ${jobKey} not found in config."
                            return
                        }

                        def paramsJSON = sh(script: "yq eval -o=json '.\"${jobKey}\".parameters // {}' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        
                        // Parse parameters using jq instead of readJSON
                        def paramKeys = sh(script: "echo '${paramsJSON}' | jq -r 'keys[]' 2>/dev/null || echo ''", returnStdout: true).trim().split('\n').findAll { it }
                        def buildParams = []
                        
                        paramKeys.each { key ->
                            def value = sh(script: "echo '${paramsJSON}' | jq -r '.\"${key}\"'", returnStdout: true).trim()
                            def valueType = sh(script: "echo '${paramsJSON}' | jq -r 'type_of(.\"${key}\")'", returnStdout: true).trim()
                            
                            if (valueType == "boolean") {
                                buildParams.add(booleanParam(name: key, value: value == "true"))
                            } else {
                                buildParams.add(string(name: key, value: value))
                            }
                        }

                        echo "[▶] Running critical job: ${jobKey}"
                        build job: jobKey,
                              parameters: buildParams,
                              wait: true,
                              propagate: true
                    }

                    def jobKeys = sh(script: "yq eval 'keys' ${env.CONFIG_FILE}", returnStdout: true)
                        .split('\n')
                        .collect { it.trim().replaceAll(/^[- ]/, '') }
                        .findAll { it && !criticalJobs.contains(it) }

                    def parallelJobs = [:]

                    jobKeys.each { jobKey ->
                        def trigger = sh(script: "yq eval '.\"${jobKey}\".trigger // false' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        if (trigger != "true") return

                        def paramsJSON = sh(script: "yq eval -o=json '.\"${jobKey}\".parameters // {}' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        
                        // Parse parameters using jq instead of readJSON
                        def paramKeys = sh(script: "echo '${paramsJSON}' | jq -r 'keys[]' 2>/dev/null || echo ''", returnStdout: true).trim().split('\n').findAll { it }
                        def buildParams = []
                        
                        paramKeys.each { key ->
                            def value = sh(script: "echo '${paramsJSON}' | jq -r '.\"${key}\"'", returnStdout: true).trim()
                            def valueType = sh(script: "echo '${paramsJSON}' | jq -r 'type_of(.\"${key}\")'", returnStdout: true).trim()
                            
                            if (valueType == "boolean") {
                                buildParams.add(booleanParam(name: key, value: value == "true"))
                            } else {
                                buildParams.add(string(name: key, value: value))
                            }
                        }

                        parallelJobs[jobKey] = {
                            echo "[▶] Running parallel job: ${jobKey}"
                            build job: jobKey,
                                  parameters: buildParams,
                                  wait: true,
                                  propagate: true
                        }
                    }

                    if (parallelJobs) {
                        parallel parallelJobs
                    } else {
                        echo "[✓] No parallel jobs to run."
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
                "[${env.JOB_NAME}]: ✅ Build finished successfully for branch ${params.CONFIG_BRANCH} → ${env.BUILD_URL}"
            )
            script {
                currentBuild.description = "Built from ${params.CONFIG_BRANCH}"
            }
            cleanUpWS()
        }

        failure {
            slackNotify(
                "#releases-ci",
                "#FF0000",
                "[${env.JOB_NAME}]: ❌ Build failed for branch ${params.CONFIG_BRANCH} → ${env.BUILD_URL}"
            )
            cleanUpWS()
        }

        always {
            cleanUpWS()
        }
    }
}