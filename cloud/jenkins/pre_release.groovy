def operatorMap = [
    'percona-server-mongodb-operator': 'psmdb',
    'percona-xtradb-cluster-operator': 'pxc',
    'percona-server-mysql-operator': 'ps',
    'percona-postgresql-operator': 'pg'
]

pipeline {
    agent {
        label 'docker-x64-min'
    }

    parameters {
        choice(
            name: 'OPERATOR',
            choices: ['percona-server-mongodb-operator', 'percona-xtradb-cluster-operator', 'percona-server-mysql-operator', 'percona-postgresql-operator'],
            description: 'Select the operator'
        )
        string(
            name: 'VERSION',
            defaultValue: '',
            description: 'Version string for the release (e.g., 1.2.3)'
        )
        choice(
            name: 'CREATE_BRANCH',
            choices: ['NO', 'YES'],
            description: 'Create a new release branch?'
        )
    }
        

    environment {
        RELEASE_BRANCH = "release-${params.VERSION}"
        RESULT_FOLDER = 'release-artifacts'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
        timeout(time: 10, unit: 'MINUTES')
    }

    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.VERSION || !(params.VERSION ==~ /^\d+\.\d+\.\d+$/)) {
                        error("VERSION must follow semantic versioning format (x.y.z). Provided: ${params.VERSION}")
                    }

                    def repoPath = "percona/${params.OPERATOR}"
                    env.GIT_REPO_URL = "https://github.com/${repoPath}.git"
                    env.GITHUB_API_URL = "https://api.github.com/repos/${repoPath}/releases"

                    echo "Validating version v${params.VERSION} for ${params.OPERATOR}..."

                    def apiResponse = sh(
                        script: "curl -s ${env.GITHUB_API_URL}",
                        returnStdout: true
                    ).trim()

                    if (apiResponse.contains("\"tag_name\": \"v${params.VERSION}\"")) {
                        error("Version v${params.VERSION} already exists in GitHub releases.")
                    }

                    def latestVersion = sh(
                        script: "echo '${apiResponse}' | jq -r '.[0].tag_name'",
                        returnStdout: true
                    ).trim()

                    if (latestVersion) {
                        def compareResult = sh(
                            script: "printf '%s\\n%s' '${latestVersion}' '${params.VERSION}' | sort -V | tail -1",
                            returnStdout: true
                        ).trim()

                        if (compareResult != params.VERSION) {
                            error("New version v${params.VERSION} must be greater than latest release v${latestVersion}")
                        }
                        echo "Version v${params.VERSION} > v${latestVersion} ✓"
                    } else {
                        error("No existing releases found in repository. Cannot proceed without a baseline release to compare against.")
                    }
                }
            }
        }

        // stage('Checkout Code') {
        //     steps {
        //         script {
        //             echo 'Checking out operator repository...'
        //             checkout([
        //                 $class: 'GitSCM',
        //                 branches: [[name: '*/main']],
        //                 extensions: [
        //                     [$class: 'CleanBeforeCheckout'],
        //                     [$class: 'CloneOption', depth: 0, noTags: false, shallow: false]
        //                 ],
        //                 userRemoteConfigs: [[
        //                     credentialsId: 'git-credentials',
        //                     url: env.GIT_REPO_URL
        //                 ]]
        //             ])
        //         }
        //     }
        // }

        // stage('Create Release Branch') {
        //     when {
        //         expression { params.CREATE_BRANCH == 'yes' }
        //     }
        //     steps {
        //         script {
        //             echo "Creating release branch: ${env.RELEASE_BRANCH}"
        //             sh """
        //                 git config user.email "jenkins@example.com"
        //                 git config user.name "Jenkins CI"
        //                 git checkout -b ${env.RELEASE_BRANCH}

        //                 git checkout ${env.RELEASE_BRANCH}
        //             """
        //         }
        //     }
        // }

        stage('Install dependencies') {
            steps {
                script {
                    echo 'Installing Python dependencies...'
                    sh '''
                        curl -LsSf https://astral.sh/uv/install.sh | sh
                        export PATH="$HOME/.local/bin:$PATH"
                        uv init
                        uv add requests packaging
                    '''
                }
            }
        }
        stage('Fetch release images') {
            steps {
                script {
                    echo 'Generating release images file'
                    sh """
                        uv run scripts/generate_release_images_file.py operatorMap[params.OPERATOR] ${params.VERSION}
                    """

                    archiveArtifacts artifacts: 'release_versions.txt', allowEmptyArchive: false, fingerprint: true
                }
            }
        }

        // stage('Execute Makefile Release') {
        //     steps {
        //         script {
        //             echo 'Executing Makefile release rule...'
        //             sh """
        //                 make release VERSION=${params.VERSION} IMAGE_TAG_BASE=percona/${params.OPERATOR}
        //             """
        //         }
        //     }
        // }

        // stage('Commit and Push Changes') {
        //     steps {
        //         script {
        //             def updateBranch = "${env.RELEASE_BRANCH}-update_versions"
        //             echo "Creating branch '${updateBranch}' from ${env.RELEASE_BRANCH} and committing changes..."
        //             sh """
        //                 git config user.email "jenkins@example.com"
        //                 git config user.name "Jenkins CI"
        //                 git checkout -b ${updateBranch}
                        
        //                 git add .
        //                 if ! git diff --cached --exit-code; then
        //                     git commit -m "Update images for ${params.VERSION} release"
        //                     git push origin ${updateBranch}
        //                     echo "Changes pushed to ${updateBranch}"
        //                     echo "Please create a PR from ${updateBranch} to ${env.RELEASE_BRANCH}"
        //                 else
        //                     echo "No changes to commit"
        //                 fi
        //             """
        //         }
        //     }
        // }

        // stage('Generate Version Service JSON fragment') {
        //     steps {
        //         sh """
        //             uv run generate_vs_json.py e2e-tests/release_versions operator.{params.VERSION}.${params.OPERATOR}-frag.json
        //         """
        //     }
        // }
    }

    post {
        success {
            script {
                def updateBranch = "${env.RELEASE_BRANCH}-update_versions"
                def message = """
                    :white_check_mark: *Pre-Release Build Successful*
                    *Version:* ${params.VERSION}
                    *Operator:* ${params.OPERATOR}
                    *Release Branch:* ${env.RELEASE_BRANCH}
                    *Update Branch:* ${updateBranch}
                    *Build:* ${env.BUILD_URL}
                    *Next Step:* Create PR from ${updateBranch} to ${env.RELEASE_BRANCH}
                """.stripIndent()

                //slackSend(channel: '#cloud-dev-ci',color: 'good',message: message)
            }
        }
        failure {
            script {
                def message = """
                    :x: *Pre-Release Build Failed*
                    *Version:* ${params.VERSION}
                    *Operator:* ${params.OPERATOR}
                    *Release Branch:* ${env.RELEASE_BRANCH}
                    *Build:* ${env.BUILD_URL}
                """.stripIndent()

                //slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: message
            }
        }
        always {
            deleteDir()
        }
    }
}
