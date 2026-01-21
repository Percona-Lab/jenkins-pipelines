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
        string(
            name: 'PREVIOUS_VERSION',
            defaultValue: '',
            description: 'Previous Version string for the Version Service to be based in(e.g., 1.2.3)'
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

                    if (!params.PREVIOUS_VERSION || !(params.PREVIOUS_VERSION ==~ /^\d+\.\d+\.\d+$/)) {
                        error("PREVIOUS_VERSION must follow semantic versioning format (x.y.z). Provided: ${params.PREVIOUS_VERSION}")
                    }

                    def repoPath = "percona/${params.OPERATOR}"
                    env.GIT_REPO_URL = "https://github.com/${repoPath}.git"
                    env.GITHUB_API_URL = "https://api.github.com/repos/${repoPath}/releases"

                    echo "Validating version v${params.VERSION} for ${params.OPERATOR}..."

                    sh """
                        curl -s ${env.GITHUB_API_URL} > /tmp/releases.json
                    """
                    def versionExists = sh(
                        script: "jq -r '.[].tag_name' /tmp/releases.json | grep -x 'v${params.VERSION}' || true",
                        returnStdout: true
                    ).trim()

                    if (versionExists) {
                        error("Version v${params.VERSION} already exists in GitHub releases.")
                    }

                    def latestVersion = sh(
                        script: "jq -r '.[0].tag_name' /tmp/releases.json",
                        returnStdout: true
                    ).trim()

                    if (latestVersion && latestVersion != 'null') {
                        def latestVersionClean = latestVersion.replaceAll(/^v/, '')
                        def currentVersionClean = params.VERSION
                        
                        def compareResult = sh(
                            script: "printf '%s\\n%s' '${latestVersionClean}' '${currentVersionClean}' | sort -V | tail -1",
                            returnStdout: true
                        ).trim()

                        if (compareResult != currentVersionClean) {
                            error("New version v${params.VERSION} must be greater than latest release ${latestVersion}")
                        }
                        echo "Version v${params.VERSION} > ${latestVersion} ✓"
                    } else {
                        error("No existing releases found in repository. Cannot proceed without a baseline release to compare against.")
                    }
                    sh "rm -f /tmp/releases.json"
                
                }
            }
        }
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
                        export PATH="\$HOME/.local/bin:\$PATH"
                        uv run cloud/scripts/generate_release_images_file.py ${operatorMap[params.OPERATOR]} ${params.VERSION}
                    """
                    archiveArtifacts artifacts: 'release_versions.txt', allowEmptyArchive: false, fingerprint: true
                }
            }
        }
        stage('Generate Version Service JSON fragment') {
            steps {
                script {
                    def vsFileName = "operator.${params.VERSION}.${operatorMap[params.OPERATOR]}-operator.json"
                    sh """
                        export PATH="\$HOME/.local/bin:\$PATH"
                        uv run cloud/scripts/generate_vs.py release release_versions.txt ${params.PREVIOUS_VERSION} ${vsFileName}
                    """
                    archiveArtifacts artifacts: "${vsFileName}", allowEmptyArchive: false, fingerprint: true
                }
            }
        }
        stage('Generate Test Plan') {
            steps {
                sh """
                    export PATH="\$HOME/.local/bin:\$PATH"
                    uv run cloud/scripts/generate_test_plan.py release_versions.txt
                """
                archiveArtifacts artifacts: 'test-plan.json, test-plan.md', allowEmptyArchive: false, fingerprint: true
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
        //         expression { params.CREATE_BRANCH == 'YES' }
        //     }
        //     steps {
        //         script {
        //             echo "Creating release branch: ${env.RELEASE_BRANCH}"
        //             sh """
        //                 git config user.email "jenkins@example.com"
        //                 git config user.name "Jenkins CI"
        //                 git checkout -b ${env.RELEASE_BRANCH}
        //             """
        //         }
        //     }
        // }

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
