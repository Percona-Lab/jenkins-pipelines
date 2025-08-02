library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh "sudo rm -rf ./* || true"
}

def AWS_STASH_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }

    parameters {
        string(name: 'CLOUD', defaultValue: 'Hetzner', description: 'Cloud infra: Hetzner or AWS')
        string(name: 'CONFIG_REPO', defaultValue: 'https://github.com/percona/postgres-packaging.git', description: 'Git repo with job_configs/all-jobs-full.yaml')
        string(name: 'CONFIG_BRANCH', defaultValue: 'main', description: 'Branch to pull job config from')
    }

    environment {
        CONFIG_FILE = 'postgres-packaging/job_configs/all-jobs-full.yaml'
    }

    stages {
        stage('Install yq & jq') {
            steps {
                sh '''
                    command -v yq >/dev/null 2>&1 || sudo apt-get update && sudo apt-get install -y yq
                    command -v jq >/dev/null 2>&1 || sudo apt-get install -y jq
                '''
            }
        }

        stage('Checkout Job Config Repo') {
            steps {
                script {
                    echo "[INFO] Cloning CONFIG_REPO: ${params.CONFIG_REPO} (${params.CONFIG_BRANCH})"
                    dir('postgres-packaging') {
                        git branch: params.CONFIG_BRANCH, url: params.CONFIG_REPO
                    }
                    if (!fileExists(env.CONFIG_FILE)) {
                        error "❌ Config file not found: ${env.CONFIG_FILE}"
                    }
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
                        def jobExists = sh(script: "yq eval '.\"${jobKey}\"' ${env.CONFIG_FILE}", returnStatus: true) == 0
                        if (!jobExists) {
                            echo "[SKIP] ${jobKey} not found in config."
                            return
                        }

                        def trigger = sh(script: "yq eval '.\"${jobKey}\".trigger' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        if (trigger != "true") {
                            echo "[SKIP] ${jobKey} not marked for triggering."
                            return
                        }

                        def jobName = sh(script: "yq eval '.\"${jobKey}\".job_name' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        def paramMap = sh(script: "yq eval '.\"${jobKey}\".parameters' ${env.CONFIG_FILE} | jq -c", returnStdout: true).trim()
                        def parsed = readJSON text: paramMap

                        def paramList = parsed.collect { k, v ->
                            v instanceof Boolean ? booleanParam(name: k, value: v) : string(name: k, value: v.toString())
                        }

                        if (!parsed.containsKey('GIT_BRANCH')) {
                            paramList << string(name: 'GIT_BRANCH', value: params.CONFIG_BRANCH)
                        }

                        echo "[▶] Triggering critical job: ${jobKey}"
                        retry(2) {
                            build job: jobName, parameters: paramList, wait: true, propagate: true
                        }
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

                    def allJobKeys = sh(script: "yq eval 'keys' ${env.CONFIG_FILE} | sed 's/- //g'", returnStdout: true).trim().split("\n")
                    def parallelJobs = [:]

                    allJobKeys.each { jobKey ->
                        if (critical.contains(jobKey)) return

                        def trigger = sh(script: "yq eval '.\"${jobKey}\".trigger' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        if (trigger != "true") return

                        def jobName = sh(script: "yq eval '.\"${jobKey}\".job_name' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        def paramMap = sh(script: "yq eval '.\"${jobKey}\".parameters' ${env.CONFIG_FILE} | jq -c", returnStdout: true).trim()
                        def parsed = readJSON text: paramMap

                        def paramList = parsed.collect { k, v ->
                            v instanceof Boolean ? booleanParam(name: k, value: v) : string(name: k, value: v.toString())
                        }

                        if (!parsed.containsKey('GIT_BRANCH')) {
                            paramList << string(name: 'GIT_BRANCH', value: params.CONFIG_BRANCH)
                        }

                        parallelJobs[jobKey] = {
                            echo "[▶] Running parallel job: ${jobKey}"
                            retry(2) {
                                build job: jobName, parameters: paramList, wait: true, propagate: true
                            }
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
            slackNotify("#releases-ci", "#00FF00", "[${env.JOB_NAME}]: ✅ Build successful for branch `${params.CONFIG_BRANCH}` → ${env.BUILD_URL}")
            script {
                currentBuild.description = "Built on ${params.CONFIG_BRANCH}"
            }
            cleanUpWS()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${env.JOB_NAME}]: ❌ Build failed for branch `${params.CONFIG_BRANCH}` → ${env.BUILD_URL}")
            cleanUpWS()
        }
        always {
            cleanUpWS()
        }
    }
}
