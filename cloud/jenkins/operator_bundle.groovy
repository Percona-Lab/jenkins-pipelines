pipeline {
    parameters {
        choice(
            choices: ['percona-server-mongodb-operator', 'percona-xtradb-cluster-operator', 'percona-postgresql-operator', 'percona-server-mysql-operator'],
            description: 'Which operator to generate the bundle for',
            name: 'OPERATOR')
        choice(
            choices: ['community', 'redhat'],
            description: 'Which bundle to generate',
            name: 'BUNDLE_TYPE')
        string(
            defaultValue: '',
            description: 'The version of the operator to build',
            name: 'VERSION')
        string(
            defaultValue: '',
            description: 'The range of supported OpenShift versions, e.g. "v4.17-v4.21"',
            name: 'OPENSHIFT_VERSIONS')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for the operator repository',
            name: 'GIT_BRANCH')
        booleanParam(
            defaultValue: false,
            description: 'Build and push the generated bundle image',
            name: 'PUSH_BUNDLE')
        booleanParam(
            defaultValue: false,
            description: 'Create branch and push bundle to community/certified operator repos',
            name: 'PUSH_TO_REPOS')
    }
    agent {
        label 'docker-x64-min'
    }
    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'

                sh """
                    rm -rf source
                    git clone -b ${GIT_BRANCH} https://github.com/percona/${params.OPERATOR} source
                    cd source/installers/olm
                    make tools
                """
            }
        }
        stage('Generate bundle') {
            steps {
                script {
                    if (params.OPERATOR == 'percona-postgresql-operator') {
                        sh """
                            cd source
                            make generate-crd-without-description
                        """
                    }
                    sh """
                        cd source/installers/olm
                        make bundles/${params.BUNDLE_TYPE} \
                            VERSION=${params.VERSION} \
                            OPENSHIFT_VERSIONS=${params.OPENSHIFT_VERSIONS}
                    """
                }
            }
        }
        stage('Prepare version folder') {
            steps {
                sh """
                    BUNDLE_DIR=source/installers/olm/bundles/${params.BUNDLE_TYPE}
                    rm -rf ${params.VERSION}
                    mkdir -p ${params.VERSION}
                    cp -a \${BUNDLE_DIR}/manifests ${params.VERSION}/
                    cp -a \${BUNDLE_DIR}/metadata ${params.VERSION}/
                """
                archiveArtifacts artifacts: "${params.VERSION}/**", allowEmptyArchive: false
            }
        }
        stage('Create hub branches') {
            when {
                expression { return params.PUSH_TO_REPOS }
            }
            steps {
                script {
                    def packageName = params.BUNDLE_TYPE == 'redhat' ? "${params.OPERATOR}-certified" : params.OPERATOR
                    def branchName = "${packageName}-${params.VERSION}"
                    def repos = params.BUNDLE_TYPE == 'community' ? [
                        'redhat-openshift-ecosystem/community-operators-prod',
                        'k8s-operatorhub/community-operators'
                    ] : [
                        'redhat-openshift-ecosystem/certified-operators'
                    ]
                    def branchLinks = []

                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_TOKEN')]) {
                        repos.each { repo ->
                            def dirName = "hub-${repo.tokenize('/').last()}"
                            sh """
                                rm -rf ${dirName}
                                git clone --depth=1 https://github.com/${repo}.git ${dirName}
                            """
                            dir(dirName) {
                                sh """
                                    git config user.email "jenkins@percona.com"
                                    git config user.name "JNKPercona"
                                    git checkout -b ${branchName}
                                    mkdir -p operators/${packageName}
                                    rm -rf operators/${packageName}/${params.VERSION}
                                    cp -a ../${params.VERSION} operators/${packageName}/${params.VERSION}
                                    git add operators/${packageName}/${params.VERSION}
                                    git commit -m "operator ${packageName} (${params.VERSION})"
                                    git remote set-url origin https://x-access-token:\${GITHUB_TOKEN}@github.com/${repo}.git
                                    git push -u origin ${branchName}
                                """
                            }
                            branchLinks.add("https://github.com/${repo}/tree/${branchName}")
                        }
                    }

                    def linksText = branchLinks.collect { "- ${it}" }.join('\n')
                    slackSend(
                        channel: '#cloud-dev-ci',
                        color: 'good',
                        message: """
                            :white_check_mark: *Operator Bundle Hub Branches Created*
                            *Operator:* ${params.OPERATOR}
                            *Package:* ${packageName}
                            *Version:* ${params.VERSION}
                            *Bundle Type:* ${params.BUNDLE_TYPE}
                            *Build:* ${env.BUILD_URL}
                            *Branches:*
                            ${linksText}
                        """.stripIndent()
                    )
                }
            }
        }
        stage('Build') {
            when {
                expression { return params.PUSH_BUNDLE }
            }
            steps {
                sh """
                    cd source/installers/olm
                    make build \
                        VERSION=${params.VERSION}
                """
            }
        }
        stage('Push') {
            when {
                expression { return params.PUSH_BUNDLE }
            }
            steps {
                 sh """
                    cd source/installers/olm
                    make push \
                        VERSION=${params.VERSION} \
                        CONFIRM_PUSH=0
                """
            }
        }
    }
}
