library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Separate from the tag job so a no-AI CSV is archived before any model
// call. Re-run this job to redo the AI column without moving S3 state.

def GitHubURL = 'https://github.com'

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            name: 'SOURCE_JOB',
            defaultValue: 'check-mysql-repo',
            description: 'Job that archived csv_output/*_no_ai.csv. Blank is not allowed.'
        )
        choice(
            name: 'MODEL',
            choices: [
                'claude-opus',
                'claude-sonnet',
                'claude-haiku',
                'claude-fable',
                'claude-best',
                'claude-opusplan',
                'claude-opus[1m]',
                'claude-sonnet[1m]',
                'claude-fable[1m]',
                'claude-claude-opus-5-5',
                'claude-claude-opus-5',
                'claude-claude-opus-4-8',
                'claude-claude-opus-4-7',
                'claude-claude-opus-4-6',
                'claude-claude-opus-4-5',
                'claude-claude-opus-4-1',
                'claude-claude-opus-4',
                'claude-claude-sonnet-5-5',
                'claude-claude-sonnet-5',
                'claude-claude-sonnet-4-6',
                'claude-claude-sonnet-4-5',
                'claude-claude-sonnet-4',
                'claude-claude-sonnet-3-7',
                'claude-claude-haiku-5-5',
                'claude-claude-haiku-4-5',
                'claude-claude-haiku-3-5',
                'claude-claude-fable-5-1',
                'claude-claude-fable-5',
                'claude-claude-opus-5-5[1m]',
                'claude-claude-opus-5[1m]',
                'claude-claude-opus-4-8[1m]',
                'claude-claude-opus-4-6[1m]',
                'claude-claude-sonnet-5-5[1m]',
                'claude-claude-sonnet-5[1m]',
                'claude-claude-sonnet-4-6[1m]',
                'claude-claude-fable-5[1m]',
            ],
            description: 'claude-<model id>. claude-opus is the default. A versioned id is claude-claude-opus-5-5. This job only lists Claude because the agent runs the claude CLI. cursor, agy, and codex stay available in check_mysql_repo_ai.py for a local run. Fast mode is forced off.'
        )
        choice(
            name: 'EFFORT',
            choices: ['xhigh', 'low', 'medium', 'high', 'max'],
            description: 'claude --effort. xhigh (extra-high) is the default. The levels are low, medium, high, xhigh, max.'
        )
        booleanParam(
            name: 'USE_BEDROCK',
            defaultValue: true,
            description: 'Set CLAUDE_CODE_USE_BEDROCK=1 for this run. On by default. The agent still needs AWS credentials that can call Bedrock.'
        )
        string(
            name: 'LIMIT',
            defaultValue: '0',
            description: 'Pass --limit to check_mysql_repo_ai.py. 0 analyzes every row of each copied CSV. A positive number stops each file after that many rows.'
        )
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Fetch report') {
            steps {
                copyArtifacts(
                    projectName: params.SOURCE_JOB.trim(),
                    selector: lastSuccessful(),
                    filter: 'csv_output/*_no_ai.csv',
                    flatten: true,
                    optional: false,
                    fingerprintArtifacts: false
                )
            }
        }
        stage('Clone MySQL') {
            steps {
                // Public repo. Blobs are fetched later, when git show reads
                // a commit. The commit graph is still required.
                sh """
                    set -o errexit
                    set -o xtrace
                    git clone --filter=blob:none ${GitHubURL}/mysql/mysql-server mysql-server-repo
                """
            }
        }
        stage('Fetch script') {
            steps {
                withCredentials([string(credentialsId: 'Github_Integration', variable: 'GITHUB_API_TOKEN')]) {
                    script {
                        def sandboxFetch = load 'ps/jenkins/sandbox_fetch.groovy'
                        sandboxFetch.fetch(
                            'https://raw.githubusercontent.com/Percona-Lab/mysql-sandbox/main/jenkins',
                            ['check_mysql_repo_ai.py']
                        )
                    }
                }
            }
        }
        stage('Analyze') {
            steps {
                script {
                    def bedrock = params.USE_BEDROCK ? '1' : '0'
                    def rowLimit = params.LIMIT
                    if (rowLimit == null || !rowLimit.trim()) {
                        rowLimit = '0'
                    } else {
                        rowLimit = rowLimit.trim()
                    }
                    if (!(rowLimit ==~ /[0-9]+/)) {
                        error("LIMIT must be a non-negative integer, got: ${rowLimit}")
                    }
                    withEnv([
                        "CLAUDE_CODE_USE_BEDROCK=${bedrock}",
                        "AI_MODEL=${params.MODEL}",
                        "AI_EFFORT=${params.EFFORT}",
                        "AI_LIMIT=${rowLimit}",
                    ]) {
                        sh '''
                            set -o errexit
                            set -o xtrace
                            mkdir -p csv_ai
                            found=0
                            for csv in *_no_ai.csv; do
                                [ -f "$csv" ] || continue
                                found=1
                                python3 check_mysql_repo_ai.py \
                                    -i mysql-server-repo \
                                    -o csv_ai \
                                    --model "$AI_MODEL" \
                                    --effort "$AI_EFFORT" \
                                    --limit "$AI_LIMIT" \
                                    "$csv"
                            done
                            if [ "$found" -eq 0 ]; then
                                echo "ERROR: no *_no_ai.csv was copied"
                                exit 1
                            fi
                        '''
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'csv_ai/*.csv', allowEmptyArchive: true
            sh '''
                sudo rm -rf ./* || true
            '''
            deleteDir()
        }
    }
}
