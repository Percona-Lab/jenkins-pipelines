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
                    env.REPO_PATH = repoPath
                    env.GIT_REPO_URL = "https://github.com/${repoPath}.git"
                    env.GITHUB_API_URL = "https://api.github.com/repos/${repoPath}/releases"
                    env.VS_REPO_URL = "https://github.com/Percona-Lab/percona-version-service.git"
                    env.VS_FILE_NAME = "operator.${params.VERSION}.${operatorMap[params.OPERATOR]}-operator.json"
                    env.VS_DEP_FILE_NAME = "operator.${params.VERSION}.${operatorMap[params.OPERATOR]}-operator.dep.json"
                    env.VS_BRANCH = "release-${operatorMap[params.OPERATOR]}-${params.VERSION}"

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
                sh '''
                    curl -LsSf https://astral.sh/uv/install.sh | sh
                    export PATH="$HOME/.local/bin:$PATH"
                    uv init
                    uv add requests packaging

                    GO_VERSION=$(curl -s https://go.dev/VERSION?m=text | head -1)
                    curl -LO "https://go.dev/dl/${GO_VERSION}.linux-amd64.tar.gz"
                    tar -xzf ${GO_VERSION}.linux-amd64.tar.gz
                    rm ${GO_VERSION}.linux-amd64.tar.gz
                    export PATH="$WORKSPACE/go/bin:$PATH"
                    go install sigs.k8s.io/crdify@latest

                    curl -LO https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64
                    chmod +x yq_linux_amd64
                    mv yq_linux_amd64 $HOME/.local/bin/yq
                '''
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
        stage('Generate VS JSON files') {
            steps {
                script {
                    sh """
                        export PATH="\$HOME/.local/bin:\$PATH"
                        uv run cloud/scripts/generate_vs.py release release_versions.txt ${params.PREVIOUS_VERSION} ${env.VS_FILE_NAME}
                    """
                    archiveArtifacts artifacts: "${env.VS_FILE_NAME}, ${env.VS_DEP_FILE_NAME}", allowEmptyArchive: false, fingerprint: true
                }
            }
        }
        stage('Generate Test Plan') {
            steps {
                sh """
                    export PATH="\$HOME/.local/bin:\$PATH"
                    uv run cloud/scripts/generate_test_plan.py release_versions.txt
                """
                archiveArtifacts artifacts: 'test_plan.json, test_plan.md', allowEmptyArchive: false, fingerprint: true
            }
        }
        stage('Exit When Not Creating Branch') {
            when {
                expression { params.CREATE_BRANCH == 'NO' }
            }
            steps {
                echo 'CREATE_BRANCH=NO, branch creation stages will be skipped. Artifacts were generated successfully.'
            }
        }
        stage('Create Operator Release Branch') {
            when {
                expression { params.CREATE_BRANCH == 'YES' }
            }
            steps {
                script {
                    echo 'Checking out operator repository...'
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/main']],
                        extensions: [
                            [$class: 'CleanBeforeCheckout'],
                            [$class: 'CloneOption', depth: 1, noTags: true, shallow: false],
                            [$class: 'RelativeTargetDirectory', relativeTargetDir: 'operator-repo']
                        ],
                        userRemoteConfigs: [[
                            url: env.GIT_REPO_URL
                        ]]
                    ])
                }
                dir('operator-repo') {
                    sh "cp ../release_versions.txt e2e-tests/release_versions"
                    script {
                        echo "Creating release branch: ${env.RELEASE_BRANCH}"
                        sh "git checkout -b ${env.RELEASE_BRANCH}"
                        sh """
                            export PATH="\$HOME/.local/bin:\$WORKSPACE/go/bin:\$PATH"
                            export GOPATH="\$WORKSPACE/gopath"
                            export PATH="\$GOPATH/bin:\$PATH"
                            make release VERSION=${params.VERSION} IMAGE_TAG_BASE=percona/${params.OPERATOR}
                        """
                        sh """
                            export PATH="\$HOME/.local/bin:\$WORKSPACE/go/bin:\$HOME/go/bin:\$PATH"
                            git fetch --depth=1 origin "refs/tags/v${params.PREVIOUS_VERSION}:refs/tags/v${params.PREVIOUS_VERSION}"
                            crdify 'git://v${params.PREVIOUS_VERSION}?path=deploy/crd.yaml' 'git://${env.RELEASE_BRANCH}?path=deploy/crd.yaml' > ../crd_diff.txt
                        """
                    }
                    archiveArtifacts artifacts: 'crd_diff.txt', allowEmptyArchive: false, fingerprint: true
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_TOKEN')]) {
                        script {
                            def updateBranch = "${env.RELEASE_BRANCH}-update_versions"
                            echo "Creating branch '${updateBranch}' and committing changes..."
                            sh """
                                git config user.email "jenkins@example.com"
                                git config user.name "Jenkins CI"
                                git remote set-url origin https://x-access-token:\${GITHUB_TOKEN}@github.com/${env.REPO_PATH}.git
                                if git ls-remote --exit-code --heads origin ${env.RELEASE_BRANCH} >/dev/null 2>&1; then
                                    echo "Release branch ${env.RELEASE_BRANCH} already exists on origin"
                                else
                                    git push origin ${env.RELEASE_BRANCH}
                                fi
                                git checkout -b ${updateBranch}

                                git add .
                                if ! git diff --cached --exit-code; then
                                    git commit -m "Update images for ${params.VERSION} release"
                                    git push origin ${updateBranch}
                                    echo "Changes pushed to ${updateBranch}"
                                    echo "Please create a PR from ${updateBranch} to ${env.RELEASE_BRANCH}"
                                else
                                    echo "No changes to commit"
                                fi
                            """
                        }
                    }
                }
            }
        }
        stage('Create Version Service Branch') {
            when {
                expression { params.CREATE_BRANCH == 'YES' }
            }
            steps {
                script {
                    echo 'Checking out version service repository...'
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/main']],
                        extensions: [
                            [$class: 'CleanBeforeCheckout'],
                            [$class: 'CloneOption', depth: 1, noTags: true, shallow: false],
                            [$class: 'RelativeTargetDirectory', relativeTargetDir: 'version-service-repo']
                        ],
                        userRemoteConfigs: [[
                            url: env.VS_REPO_URL
                        ]]
                    ])
                    sh "cp ${env.VS_FILE_NAME} version-service-repo/sources/${env.VS_FILE_NAME}"
                    sh "cp ${env.VS_DEP_FILE_NAME} version-service-repo/sources/${env.VS_DEP_FILE_NAME}"
                }
                dir('version-service-repo') {
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_TOKEN')]) {
                        script {
                            echo "Creating branch '${env.VS_BRANCH}' and committing changes..."
                            sh """
                                git config user.email "jenkins@example.com"
                                git config user.name "Jenkins CI"
                                git checkout -b ${env.VS_BRANCH}

                                git add sources/${env.VS_FILE_NAME} sources/${env.VS_DEP_FILE_NAME}
                                if ! git diff --cached --exit-code; then
                                    git commit -m "Update ${operatorMap[params.OPERATOR].toUpperCase()} operator versions for ${params.VERSION} release"
                                    git remote set-url origin https://x-access-token:\${GITHUB_TOKEN}@github.com/Percona-Lab/percona-version-service.git
                                    git push origin ${env.VS_BRANCH}
                                    echo "Changes pushed to ${env.VS_BRANCH}"
                                else
                                    echo "No changes to commit"
                                fi
                            """
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                def updateBranch = "${env.RELEASE_BRANCH}-update_versions"
                def message
                if (params.CREATE_BRANCH == 'YES') {
                    message = """
                        :white_check_mark: *Pre-Release Build Successful*
                        *Version:* ${params.VERSION}
                        *Operator:* ${params.OPERATOR}
                        *Release Branch:* ${env.RELEASE_BRANCH}
                        *Update Branch:* ${updateBranch}
                        *Build:* ${env.BUILD_URL}
                        *Next Step:* Create PR from ${updateBranch} to ${env.RELEASE_BRANCH}
                    """.stripIndent()
                } else {
                    message = """
                        :white_check_mark: *Pre-Release Build Successful (No Branch Creation)*
                        *Version:* ${params.VERSION}
                        *Operator:* ${params.OPERATOR}
                        *Build:* ${env.BUILD_URL}
                        *Note:* Generated artifacts only. Branch creation was skipped by request.
                    """.stripIndent()
                }

                slackSend(channel: '#cloud-dev-ci',color: 'good',message: message)
            }
        }
        aborted {
            script {
                def message = """
                    :warning: *Pre-Release Build Aborted*
                    *Version:* ${params.VERSION}
                    *Operator:* ${params.OPERATOR}
                    *Build:* ${env.BUILD_URL}
                """.stripIndent()

                slackSend channel: '#cloud-dev-ci', color: '#FFA500', message: message
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

                slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: message
            }
        }
        always {
            deleteDir()
        }
    }
}
