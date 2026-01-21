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
            defaultValue: 'gemini-2.0-flash',
            description: 'LLM model for AI analysis'
        )
    }
    triggers {
        // Hourly on months 1,4,7,10 from 2nd week to 4th week (days 8-28)
        cron('H * 8-28 1,4,7,10 *')
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

                                if [ -f ${repoName}.last ]; then
                                    # Get current tags
                                    git ls-remote --tags --refs ${repoUrl} | awk -F'/' '{print \$3}' | sort > ${repoName}.current

                                    # Find NEW tags only (in current but not in last)
                                    # Using comm -13: suppress lines unique to file1 and lines in both
                                    # This gives us only lines unique to file2 (new tags)
                                    # Note: both files are already sorted from creation
                                    comm -13 ${repoName}.last ${repoName}.current | \
                                        awk '{print "${repoUrl}/tree/"\$1}' > ${repoName}.newtags || true

                                    mv -fv ${repoName}.current ${repoName}.last
                                else
                                    # First run - just save current state
                                    git ls-remote --tags --refs ${repoUrl} | awk -F'/' '{print \$3}' | sort > ${repoName}.last
                                    touch ${repoName}.newtags
                                fi
                            """
                            pushArtifactFile("${repoName}.last", S3_PATH)
                        }
                    }
                    }
                stash allowEmpty: true, includes: '*.newtags, *.last', name: 'TagFiles'
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

                    // Create directory for CSV output
                    sh 'mkdir -p csv_output'

                    // Define version patterns to track
                    def versionPatterns = ['mysql-5.7', 'mysql-8.0', 'mysql-8.4', 'mysql-9.0', 'mysql-9.1', 'mysql-9.2']

                    versionPatterns.each { versionPattern ->
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
                                def llmModel = params.LLM_MODEL ?: env.LLM_MODEL ?: 'gemini-2.0-flash'
                                def aiFlag = enableAI ? '-a' : ''

                                sh """
                                    set -o errexit
                                    set -o xtrace

                                    python3 ps/scripts/mysql_commit_report.py \
                                        -i mysql-server-repo \
                                        -o csv_output \
                                        -g \
                                        -m ${llmModel} \
                                        ${aiFlag} \
                                        ${oldTag} ${newTag}

                                    # Validate CSV output
                                    CSV_FILE="csv_output/${newTag}_${llmModel}.csv"
                                    if [ ! -f "\${CSV_FILE}" ]; then
                                        echo "ERROR: Expected CSV file not found: \${CSV_FILE}"
                                        exit 1
                                    fi

                                    LINE_COUNT=\$(wc -l < "\${CSV_FILE}")
                                    if [ "\${LINE_COUNT}" -le 1 ]; then
                                        echo "ERROR: CSV file has no data (only header): \${CSV_FILE}"
                                        exit 1
                                    fi

                                    echo "SUCCESS: Generated \${CSV_FILE} with \$((LINE_COUNT - 1)) commits"
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
    }
    post {
        always {
            archiveArtifacts artifacts: '*.newtags, csv_output/**/*.csv', allowEmptyArchive: true
            sh '''
                sudo rm -rf ./* || true
            '''
            deleteDir()
        }
    }
}
