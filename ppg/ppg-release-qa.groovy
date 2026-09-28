library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Started by ppg-release-watcher, never by hand. Runs every downstream job
// the watcher planned for one package set, then records the outcome:
//   all SUCCESS -> VERSIONS.yml verified moves forward, build team is told
//                  the packages are ready to release
//   otherwise   -> verified stays where it was (rollback), QA is told what
//                  failed; the watcher will not re-run the same packages
// Holds no executor while waiting for downstream jobs.

launch = null
results = []
recorded = false

// The planned params as build() parameters: booleans stay booleans (the
// downstream jobs declare MAJOR_REPO etc. as booleanParam), the rest are sent
// as strings, which Jenkins also accepts for choice parameters.
def paramList(Map p) {
    def out = []
    def keys = p.keySet() as List
    for (int i = 0; i < keys.size(); i++) {
        def k = keys[i]
        def v = p[k]
        if (v instanceof Boolean) {
            out << booleanParam(name: k, value: v)
        } else {
            out << string(name: k, value: v.toString())
        }
    }
    return out
}

def runOne(Map j) {
    def tries = (j.retries ?: 0) + 1
    def b = null
    def t = 0
    def err = null
    while (t < tries) {
        t++
        try {
            b = build(job: j.job, parameters: paramList(j.params), propagate: false, wait: true)
            err = null
            if (b.result == 'SUCCESS') {
                break
            }
        } catch (e) {
            err = e.toString()
            echo "could not run ${j.job}: ${err}"
        }
    }
    return [id: j.id, job: j.job, params: j.params, attempts: t,
            result: b ? b.result : 'ERROR',
            url: b ? b.absoluteUrl : '',
            error: err ?: '']
}

def record(String extra) {
    node('min-ol-9-x64') {
        ppgReleaseWatch.setupTool(params.TESTING_BRANCH)
        ppgReleaseWatch.checkoutState(params.STATE_BRANCH)
        writeJSON(file: 'builds.json', json: results)
        ppgReleaseWatch.commitState(params.STATE_BRANCH, "result ${params.ATTEMPT_ID} ${env.BUILD_URL}") {
            ppgReleaseWatch.run("record --stream ${params.STREAM} --attempt-id ${params.ATTEMPT_ID} " +
                "--builds ${env.WORKSPACE}/builds.json --message ${env.WORKSPACE}/msg.json ${extra}")
        }
        def m = readJSON(file: 'msg.json')
        m.text = m.text + "\n<${env.BUILD_URL}|QA run>"
        slackSend(channel: m.channel, color: m.level, message: m.text)
        archiveArtifacts(artifacts: 'builds.json', allowEmptyArchive: true)
    }
    recorded = true
}

pipeline {
    agent none
    parameters {
        string(name: 'STREAM', defaultValue: '', description: 'Set by ppg-release-watcher')
        string(name: 'ATTEMPT_ID', defaultValue: '', description: 'Set by ppg-release-watcher')
        text(name: 'LAUNCH_JSON', defaultValue: '', description: 'Set by ppg-release-watcher')
        string(name: 'TESTING_BRANCH', defaultValue: 'main', description: 'ppg-testing branch with the tool')
        string(name: 'STATE_BRANCH', defaultValue: 'release-watch-state', description: 'ppg-testing branch with VERSIONS.yml')
    }
    options {
        timeout(time: 16, unit: 'HOURS')   // below stale_run_hours in config.yml
        buildDiscarder(logRotator(daysToKeepStr: '90', numToKeepStr: '300'))
    }
    stages {
        stage('Plan') {
            steps {
                script {
                    if (!params.LAUNCH_JSON?.trim()) {
                        error('LAUNCH_JSON is empty; this job is started by ppg-release-watcher')
                    }
                    // readJSON needs a workspace; the rest of this job runs
                    // without holding an agent.
                    node('min-ol-9-x64') {
                        launch = readJSON(text: params.LAUNCH_JSON)
                    }
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${launch.version}"
                    currentBuild.description = launch.jobs.collect { it.id }.join(', ')
                }
            }
        }
        stage('Run QA jobs') {
            steps {
                script {
                    def jobs = launch.jobs
                    int width = (launch.max_parallel ?: 3) as int
                    def byId = [:]
                    for (int i = 0; i < jobs.size(); i += width) {
                        def branches = [:]
                        for (int k = i; k < Math.min(i + width, jobs.size()); k++) {
                            def j = jobs[k]
                            branches[j.id] = { byId[j.id] = runOne(j) }
                        }
                        parallel(branches)
                    }
                    for (def j in jobs) {
                        results << byId[j.id]
                    }
                }
            }
        }
        stage('Record') {
            steps {
                script {
                    record('')
                    if (results.any { it.result != 'SUCCESS' }) {
                        currentBuild.result = 'FAILURE'
                    }
                }
            }
        }
    }
    post {
        unsuccessful {
            script {
                if (!recorded && params.ATTEMPT_ID?.trim()) {
                    record("--aborted 'ppg-release-qa ended ${currentBuild.currentResult} before recording'")
                }
            }
        }
    }
}
