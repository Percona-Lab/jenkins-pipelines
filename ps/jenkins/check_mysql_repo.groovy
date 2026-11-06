library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def GitHubURL = 'https://github.com'
def S3_PATH = 's3://percona-jenkins-artifactory/percona-server/mysql-monitor'

// Repositories to monitor
def ReposPathMap = [
    'MySQL': "${GitHubURL}/mysql/mysql-server"
]

// Slack channels for notifications
def SlackChannels = ['#mysql', '#eol-dev']

void pushArtifactFile(String FILE_NAME, String S3_PATH) {
    sh """
        set -o errexit
        set -o xtrace
        aws s3 cp --quiet ${FILE_NAME} ${S3_PATH}/${FILE_NAME}
    """
}

void popArtifactFile(String FILE_NAME, String S3_PATH) {
    sh """
        set -o xtrace
        aws s3 cp --quiet ${S3_PATH}/${FILE_NAME} ${FILE_NAME} || echo "File not found in S3, starting fresh"
    """
}

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        booleanParam(
            name: 'ENABLE_AI_ANALYSIS',
            defaultValue: false,
            description: 'Enable AI analysis (requires CURSOR_API_KEY env var or credential)'
        )
        string(
            name: 'LLM_MODEL',
            defaultValue: 'gemini-3-pro',
            description: 'LLM model for AI analysis'
        )
        booleanParam(
            name: 'GENERATE_PER_COMPONENT_CSV',
            defaultValue: false,
            description: 'Generate per-component CSV breakdown in addition to the main report'
        )
        booleanParam(
            name: 'FORCE_LATEST_84',
            defaultValue: false,
            description: 'Treat the newest mysql-8.4 tag as new and generate its CSV even when the saved baseline already contains it.'
        )
        string(
            name: 'BUG_MAP_JOB',
            defaultValue: 'check-oracle-cpu',
            description: 'Jenkins job that archives cpu-bug-cve.json. Blank skips the CVE column.'
        )
    }
    triggers {
        // Hourly every month from 2nd week to 4th week (days 8-28)
        cron('H * 8-28 * *')
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Check MySQL Repos') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'AWS_STASH',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    script {
                        ReposPathMap.each { repoName, repoUrl ->
                            popArtifactFile("${repoName}.last", S3_PATH)
                            sh """
                                set -o errexit
                                set -o xtrace
                                rm -f ${repoName}.seeded

                                if [ -f ${repoName}.last ]; then
                                    # Get current tags
                                    git ls-remote --tags --refs ${repoUrl} | awk -F'/' '{print \$3}' | sort > ${repoName}.current

                                    # Find NEW tags only (in current but not in last)
                                    # Using comm -13: suppress lines unique to file1 and lines in both
                                    # This gives us only lines unique to file2 (new tags)
                                    # Note: both files are already sorted from creation
                                    comm -13 ${repoName}.last ${repoName}.current | \
                                        awk '{print "${repoUrl}/tree/"\$1}' > ${repoName}.newtags || true

                                    # State promotion (current -> last) and S3 push deferred to
                                    # 'Persist State' stage so a failure in 'Process New Tags' or
                                    # 'Send Notifications' does not silently advance S3 state and
                                    # cause new tags to be lost without an alert.
                                else
                                    # First run - just save current state
                                    git ls-remote --tags --refs ${repoUrl} | awk -F'/' '{print \$3}' | sort > ${repoName}.last
                                    touch ${repoName}.newtags
                                    touch ${repoName}.seeded
                                fi
                            """
                            if (params.FORCE_LATEST_84) {
                                sh """
                                    set -o errexit
                                    latest=\$(git ls-remote --tags --refs ${repoUrl} | awk -F'/' '{print \$3}' | grep '^mysql-8\\.4\\.' | sort -V | tail -1)
                                    if [ -n "\$latest" ]; then
                                        line="${repoUrl}/tree/\$latest"
                                        touch ${repoName}.newtags
                                        grep -Fxq "\$line" ${repoName}.newtags || echo "\$line" >> ${repoName}.newtags
                                    fi
                                """
                            }
                        }
                        def newTagCount = 0
                        def seeded = false
                        ReposPathMap.each { repoName, repoUrl ->
                            if (fileExists("${repoName}.seeded")) {
                                seeded = true
                            }
                            def tagText = fileExists("${repoName}.newtags") ? readFile("${repoName}.newtags") : ''
                            newTagCount += tagText.readLines().findAll { it.trim() }.size()
                        }
                        env.MYSQL_NEW_TAG_COUNT = newTagCount.toString()
                        env.MYSQL_SEEDED = seeded ? '1' : '0'
                    }
                    }
                stash allowEmpty: true, includes: '*.newtags, *.last, *.current, *.seeded', name: 'TagFiles'
            }
        }
        stage('Process New Tags') {
            when {
                expression {
                    def hasNewTags = false
                    ReposPathMap.each { repoName, repoUrl ->
                        try {
                            def tags = sh(
                                script: "cat ${repoName}.newtags 2>/dev/null | grep -v '^\\s*\$' || echo ''",
                                returnStdout: true
                            ).trim()
                            if (tags) {
                                hasNewTags = true
                            }
                        } catch (Exception e) {
                        // File doesn't exist, continue
                        }
                    }
                    return hasNewTags
                }
            }
            steps {
                script {
                    echo 'New tags detected, cloning repositories and generating reports...'

                    // Clone mysql-server with filter for faster clone (~100MB vs 1.5GB)
                    sh """
                        set -o errexit
                        set -o xtrace

                        git clone --filter=blob:none ${ReposPathMap['MySQL']} mysql-server-repo
                    """

                    // mysql-sandbox is a private repo. Anonymous fetches from
                    // raw.githubusercontent.com return 404. Use a Jenkins-stored
                    // token. main is not a pinned SHA, so each build downloads
                    // the current script.
                    withCredentials([string(credentialsId: 'Github_Integration', variable: 'GITHUB_API_TOKEN')]) {
                        def sandboxFetch = load 'ps/jenkins/sandbox_fetch.groovy'
                        sandboxFetch.fetch(
                            'https://raw.githubusercontent.com/Percona-Lab/mysql-sandbox/main/jenkins',
                            ['check_mysql_repo.py']
                        )
                    }

                    // Create directory for CSV output
                    sh 'mkdir -p csv_output'

                    // cpu-bug-cve.json is the bug-id to CVE map archived by the
                    // Oracle monitor. An anonymous GET of the artifact URL is
                    // 403. That job allows every job to copy its artifacts.
                    // Blank BUG_MAP_JOB leaves the CVE column out.
                    // null means this build started before the parameter
                    // existed, so use the default job.
                    def bugMapJob = params.BUG_MAP_JOB
                    if (bugMapJob == null) {
                        bugMapJob = 'check-oracle-cpu'
                    }
                    bugMapJob = bugMapJob.trim()
                    if (bugMapJob) {
                        sh 'rm -f cpu-bug-cve.json'
                        copyArtifacts(
                            projectName: bugMapJob,
                            selector: lastSuccessful(),
                            filter: 'cpu-bug-cve.json',
                            optional: false,
                            flatten: true,
                            fingerprintArtifacts: false
                        )
                    }

                    // Define version patterns to track
                    def newTagText = ''
                    ReposPathMap.each { repoName, repoUrl ->
                        if (fileExists("${repoName}.newtags")) {
                            newTagText += readFile("${repoName}.newtags")
                        }
                    }
                    def versionPatterns = ['mysql-8.0', 'mysql-8.4']

                    versionPatterns.each { versionPattern ->
                        if (!newTagText.readLines().any { it.contains("/${versionPattern}.") }) {
                            echo "No new tags for ${versionPattern}"
                            return
                        }
                        echo "Processing version series: ${versionPattern}"

                        // Get latest two tags for this version series
                        def tagsOutput = sh(
                            script: """
                                git -C mysql-server-repo tag -l '${versionPattern}.*' | sort -V | tail -2
                            """,
                            returnStdout: true
                        ).trim()

                        if (!tagsOutput) {
                            echo "No tags found for ${versionPattern}"
                            return
                        }

                        def tags = tagsOutput.split('\n')

                        if (tags.size() >= 2) {
                            def oldTag = tags[-2].trim()
                            def newTag = tags[-1].trim()

                            if (oldTag && newTag && oldTag != newTag) {
                                echo "Comparing ${versionPattern}: ${oldTag} -> ${newTag}"

                                // AI analysis: parameter takes precedence, then env var
                                def enableAI = params.ENABLE_AI_ANALYSIS ?: (env.ENABLE_AI_ANALYSIS == 'true')
                                def llmModel = params.LLM_MODEL ?: env.LLM_MODEL ?: 'gemini-3-pro'
                                def aiFlags = enableAI ? "-a -m ${llmModel}" : ''
                                def csvSuffix = enableAI ? "_ai_${llmModel}" : '_no_ai'
                                def componentCsvFlag = params.GENERATE_PER_COMPONENT_CSV ? '-g' : ''

                                sh """
                                    set -o errexit
                                    set -o xtrace

                                    python3 check_mysql_repo.py \
                                        -i mysql-server-repo \
                                        -o csv_output \
                                        ${componentCsvFlag} \
                                        ${aiFlags} \
                                        ${bugMapJob ? '--cve-json cpu-bug-cve.json' : ''} \
                                        ${oldTag} ${newTag}

                                    # Validate CSV output
                                    CSV_FILE="csv_output/${newTag}${csvSuffix}.csv"
                                    if [ ! -f "\${CSV_FILE}" ]; then
                                        echo "ERROR: Expected CSV file not found: \${CSV_FILE}"
                                        exit 1
                                    fi

                                    LINE_COUNT=\$(wc -l < "\${CSV_FILE}")
                                    if [ "\${LINE_COUNT}" -le 1 ]; then
                                        echo "WARNING: CSV file has no commits (only header): \${CSV_FILE}"
                                    else
                                        echo "SUCCESS: Generated \${CSV_FILE} with \$((LINE_COUNT - 1)) commits"
                                    fi
                                """
                            } else {
                                echo "Tags are identical or invalid for ${versionPattern}: old=${oldTag}, new=${newTag}"
                            }
                        } else {
                            echo "Not enough tags found for ${versionPattern} (found: ${tags.size()})"
                        }
                    }
                }
            }
        }
        stage('Send Notifications') {
            steps {
                unstash 'TagFiles'
                script {
                    ReposPathMap.each { repoName, repoUrl ->
                        def tags = sh(
                            script: "cat ${repoName}.newtags 2>/dev/null | grep -v '^\\s*\$' || echo ''",
                            returnStdout: true
                        ).trim()

                        if (tags) {
                            def artifactUrl = "${BUILD_URL}artifact/"
                            def message = """[${JOB_NAME}]: New MySQL tag(s) detected!
Tags: ${tags}

CSV Reports: ${artifactUrl}"""

                            // Send to all configured Slack channels
                            SlackChannels.each { channel ->
                                slackNotify(channel, '#00FF00', message)
                            }
                        }
                    }
                }
            }
        }
        stage('Persist State') {
            // Runs only when all earlier stages succeed. If 'Process New Tags' or
            // 'Send Notifications' fails, this stage is skipped and S3 keeps the
            // old baseline so the next cron run will retry.
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'AWS_STASH',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    script {
                        ReposPathMap.each { repoName, repoUrl ->
                            sh """
                                set -o errexit
                                set -o xtrace
                                if [ -f ${repoName}.current ]; then
                                    mv -fv ${repoName}.current ${repoName}.last
                                fi
                            """
                            pushArtifactFile("${repoName}.last", S3_PATH)
                        }
                    }
                }
            }
        }
        stage('Report status') {
            steps {
                script {
                    def countText = env.MYSQL_NEW_TAG_COUNT ?: '0'
                    def count = countText.isInteger() ? countText.toInteger() : 0
                    def result = currentBuild.currentResult ?: 'SUCCESS'
                    def text
                    if (count == 0 && env.MYSQL_SEEDED == '1') {
                        text = 'Seeded tag baseline. No CSV.'
                    } else if (count == 0) {
                        text = 'No new tags.'
                    } else {
                        def csvs = sh(
                            script: "find csv_output -maxdepth 1 -type f \\( -name '*_no_ai.csv' -o -name '*_ai_*.csv' \\) -printf '%f\\n' | sort | paste -sd, -",
                            returnStdout: true
                        ).trim()
                        text = csvs ? "CSV written: ${csvs}" : "New tags: ${count}. No CSV."
                    }
                    if (count == 0 && result == 'SUCCESS') {
                        currentBuild.result = 'NOT_BUILT'
                    }
                    currentBuild.description = text
                    writeFile file: 'mysql-status.txt', text: text + '\n'
                    echo text
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'mysql-status.txt, *.newtags, csv_output/**/*.csv', allowEmptyArchive: true
            sh '''
                sudo rm -rf ./* || true
            '''
            deleteDir()
        }
    }
}
