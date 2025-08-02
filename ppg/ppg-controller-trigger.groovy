library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh "sudo rm -rf ./* || true"
}

def AWS_STASH_PATH
def jobsConfig = [:]

import org.yaml.snakeyaml.Yaml

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }

    environment {
        CONFIG_FILE = 'job_configs/all-jobs-full.yaml'
    }

    stages {
        stage('Checkout Job Config Repo') {
            steps {
                script {
                    echo "[INFO] Cloning CONFIG_REPO: ${params.CONFIG_REPO} (${params.CONFIG_BRANCH})"
                    dir('postgres-packaging') {
                        git branch: params.CONFIG_BRANCH, url: params.CONFIG_REPO
                    }
                    env.CONFIG_FILE = 'postgres-packaging/job_configs/all-jobs-full.yaml'
                }
            }
        }
        stage('Load Job Config') {
            steps {
                script {
                    if (!fileExists(CONFIG_FILE)) {
                        error "❌ Config file not found: ${CONFIG_FILE}"
                    }

                    def yaml = new Yaml()
                    def input = readFile(file: CONFIG_FILE)
                    jobsConfig = yaml.load(input)
                    echo "[✓] Loaded config for ${jobsConfig.size()} jobs"
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
                        def job = jobsConfig[jobKey]
                        if (!job?.trigger) {
                            echo "[SKIP] ${jobKey} not marked for triggering."
                            return
                        }

                        echo "[▶] Running critical job: ${jobKey}"
                        def params = job.parameters.collect { k, v ->
                            v instanceof Boolean ?
                                booleanParam(name: k, value: v) :
                                string(name: k, value: v.toString())
                        }

                        build job: job.job_name,
                              parameters: params,
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

                    def parallelJobs = [:]

                    jobsConfig.each { jobKey, job ->
                        if (!job.trigger || critical.contains(jobKey)) return

                        def params = job.parameters.collect { k, v ->
                            v instanceof Boolean ?
                                booleanParam(name: k, value: v) :
                                string(name: k, value: v.toString())
                        }

                        parallelJobs[jobKey] = {
                            echo "[▶] Running parallel job: ${jobKey}"
                            build job: job.job_name,
                                  parameters: params,
                                  wait: job.wait,
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
                "[${env.JOB_NAME}]: ✅ Build finished successfully for branch `${env.GIT_BRANCH}` → ${env.BUILD_URL}"
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
                "[${env.JOB_NAME}]: ❌ Build failed for branch `${env.GIT_BRANCH}` → ${env.BUILD_URL}"
            )
            cleanUpWS()
        }

        always {
            cleanUpWS()
        }
    }
}
