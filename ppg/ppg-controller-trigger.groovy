library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh "sudo rm -rf ./* || true"
}

def AWS_STASH_PATH
def jobsList = []

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

        stage('Install yq') {
            steps {
                sh '''
                    if ! command -v yq >/dev/null 2>&1; then
                        wget -q https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -O yq
                        chmod +x yq
                        sudo mv yq /usr/local/bin/
                    fi
                    yq --version
                '''
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

                    criticalJobs.each { jobName ->
                        def trigger = sh(script: "yq eval '.\"${jobName}\".trigger' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        if (trigger != 'true') {
                            echo "[SKIP] ${jobName} not marked for triggering."
                            return
                        }

                        def paramYaml = sh(script: "yq eval -o=json '.\"${jobName}\".parameters // {}' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        def paramMap = readJSON text: paramYaml
                        def buildParams = paramMap.collect { k, v -> string(name: k, value: v.toString()) }

                        echo "[▶] Running critical job: ${jobName}"
                        retry(3) {
                            build job: jobName,
                                  parameters: buildParams,
                                  wait: true,
                                  propagate: true
                        }
                    }
                }
            }
        }

        stage('Run Remaining Jobs (in parallel)') {
            steps {
                script {
                    def criticalJobs = [
                        'hetzner-postgresql-common-RELEASE',
                        'hetzner-postgresql-server-autobuild-RELEASE',
                        'hetzner-pg_percona_telemetry-autobuild-RELEASE'
                    ]

                    def allJobs = sh(script: "yq eval 'keys | .[]' ${env.CONFIG_FILE}", returnStdout: true).trim().split('\n')
                    def parallelJobs = [:]

                    allJobs.each { jobName ->
                        if (criticalJobs.contains(jobName)) return

                        def trigger = sh(script: "yq eval '.\"${jobName}\".trigger' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        if (trigger != 'true') return

                        def paramYaml = sh(script: "yq eval -o=json '.\"${jobName}\".parameters // {}' ${env.CONFIG_FILE}", returnStdout: true).trim()
                        def paramMap = readJSON text: paramYaml
                        def buildParams = paramMap.collect { k, v -> string(name: k, value: v.toString()) }

                        def waitFlag = sh(script: "yq eval '.\"${jobName}\".wait // false' ${env.CONFIG_FILE}", returnStdout: true).trim().toBoolean()

                        parallelJobs[jobName] = {
                            echo "[▶] Running parallel job: ${jobName}"
                            retry(3) {
                                build job: jobName,
                                      parameters: buildParams,
                                      wait: waitFlag,
                                      propagate: true
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
            slackNotify(
                "#releases-ci",
                "#00FF00",
                "[${env.JOB_NAME}]: ✅ Build finished successfully for branch `${params.CONFIG_BRANCH}` → ${env.BUILD_URL}"
            )
            script {
                currentBuild.description = "Built on ${params.CONFIG_BRANCH}"
            }
            cleanUpWS()
        }

        failure {
            slackNotify(
                "#releases-ci",
                "#FF0000",
                "[${env.JOB_NAME}]: ❌ Build failed for branch `${params.CONFIG_BRANCH}` → ${env.BUILD_URL}"
            )
            cleanUpWS()
        }

        always {
            cleanUpWS()
        }
    }
}
