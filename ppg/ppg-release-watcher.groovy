library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

import groovy.json.JsonOutput

// Polls the PPG testing repositories every 15 minutes. When a stream (ppg-18,
// ppg-17, ...) has a complete, settled package set newer than the last one
// that passed QA, it marks the stream as running in VERSIONS.yml and starts
// ppg-release-qa for it. Streams, packages and jobs: ppg-testing
// release_watch/config.yml. Logic: ppg-testing tools/release_watch.py.

plan = [launches: [], notifications: [], summary: []]

pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    triggers {
        cron('H/15 * * * *')
    }
    parameters {
        // Ships as true so the cron only reports at first. Flip to false to go live.
        booleanParam(name: 'DRY_RUN', defaultValue: true,
            description: 'Show what would be launched; write nothing, launch nothing')
        booleanParam(name: 'BASELINE', defaultValue: false,
            description: 'Record what is in the repos now as already verified (first setup). Launches nothing')
        string(name: 'FORCE_STREAMS', defaultValue: '',
            description: 'Space separated streams to test now with all their jobs, e.g. "ppg-18"')
        string(name: 'RETRY_STREAMS', defaultValue: '',
            description: 'Space separated streams whose failed attempt should be run again')
        string(name: 'STREAMS', defaultValue: '',
            description: 'Limit this run to these streams (default: all enabled)')
        string(name: 'TESTING_BRANCH', defaultValue: 'main',
            description: 'ppg-testing branch with tools/release_watch.py and its config')
        string(name: 'STATE_BRANCH', defaultValue: 'release-watch-state',
            description: 'ppg-testing branch holding VERSIONS.yml')
    }
    options {
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(daysToKeepStr: '14', numToKeepStr: '500'))
    }
    stages {
        stage('Setup') {
            steps {
                script {
                    ppgReleaseWatch.setupTool(params.TESTING_BRANCH)
                    ppgReleaseWatch.checkoutState(params.STATE_BRANCH)
                }
            }
        }
        stage('Baseline') {
            when { expression { params.BASELINE } }
            steps {
                script {
                    ppgReleaseWatch.commitState(params.STATE_BRANCH, "baseline by ${env.BUILD_URL}") {
                        ppgReleaseWatch.run("baseline ${params.STREAMS}")
                    }
                    currentBuild.description = 'baseline recorded'
                }
            }
        }
        stage('Poll') {
            when { expression { !params.BASELINE } }
            steps {
                script {
                    def force = params.FORCE_STREAMS.tokenize().collect { "--force ${it}" }.join(' ')
                    def cmd = "poll --plan ${env.WORKSPACE}/plan.json ${force} ${params.STREAMS}"
                    if (params.DRY_RUN) {
                        ppgReleaseWatch.run("${cmd} --dry-run")
                    } else {
                        ppgReleaseWatch.commitState(params.STATE_BRANCH, "poll ${env.BUILD_URL}") {
                            if (params.RETRY_STREAMS.trim()) {
                                ppgReleaseWatch.run("retry ${params.RETRY_STREAMS}")
                            }
                            ppgReleaseWatch.run(cmd)
                        }
                    }
                    plan = readJSON(file: 'plan.json')
                    def starting = plan.launches.collect { it.version }
                    currentBuild.description = starting ? "launched: ${starting.join(', ')}" : 'no changes'
                }
            }
        }
        stage('Launch QA') {
            when { expression { !params.BASELINE && !params.DRY_RUN && plan.launches } }
            steps {
                script {
                    for (def l in plan.launches) {
                        try {
                            build(job: 'ppg-release-qa', wait: false, parameters: [
                                string(name: 'STREAM', value: l.stream),
                                string(name: 'ATTEMPT_ID', value: l.attempt_id),
                                text(name: 'LAUNCH_JSON', value: JsonOutput.toJson(l)),
                                string(name: 'TESTING_BRANCH', value: params.TESTING_BRANCH),
                                string(name: 'STATE_BRANCH', value: params.STATE_BRANCH),
                            ])
                        } catch (err) {
                            // Do not leave the stream "running" for 18h.
                            ppgReleaseWatch.commitState(params.STATE_BRANCH, "launch failed for ${l.attempt_id}") {
                                ppgReleaseWatch.run("record --stream ${l.stream} --attempt-id ${l.attempt_id} " +
                                    "--aborted 'could not start ppg-release-qa: ${err.toString().replace("'", '')}' " +
                                    "--message ${env.WORKSPACE}/msg.json")
                            }
                            def m = readJSON(file: 'msg.json')
                            slackSend(channel: m.channel, color: m.level, message: m.text)
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                for (def n in plan.notifications) {
                    if (params.DRY_RUN) {
                        echo "[dry run] would notify ${n.channel}: ${n.text}"
                    } else {
                        slackSend(channel: n.channel, color: n.level, message: n.text)
                    }
                }
            }
        }
        failure {
            script {
                // Alert on the first failure only, not every 15 minutes.
                if (currentBuild.previousBuild?.result == 'SUCCESS') {
                    slackSend(channel: '#postgresql-test', color: 'danger',
                        message: ":x: ppg-release-watcher is failing, packages are not being watched: ${env.BUILD_URL}")
                }
            }
        }
        fixed {
            slackSend(channel: '#postgresql-test', color: 'good',
                message: ":white_check_mark: ppg-release-watcher is back to normal: ${env.BUILD_URL}")
        }
    }
}
