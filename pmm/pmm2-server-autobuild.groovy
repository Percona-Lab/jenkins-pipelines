pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'GIT_BRANCH')
        choice(
            // default is choices.get(0) - experimental
            choices: ['experimental', 'testing', 'laboratory'],
            description: 'Repo component to push packages to',
            name: 'DESTINATION')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Start PMM2 Server Autobuild') {
            steps {
                build job: 'pmm2-server-autobuild-el9', parameters: [
                    string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                    string(name: 'DESTINATION', value: params.DESTINATION)
                ]
            }
        }
        stage('Trigger a devcontainer build') {
            when {
                // a guard to avoid unnecessary builds
                expression { params.GIT_BRANCH == "PMM-2.0" && params.DESTINATION == "experimental" }
            }          
            steps {
                script {
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        sh '''
                            # 'ref' is a required parameter, it should always equal 'main'
                            curl -L -X POST \
                                -H "Accept: application/vnd.github+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/percona/pmm/actions/workflows/devcontainer.yml/dispatches" \
                                -d '{"ref":"main"}'
                        '''
                    }
                }
            }
        }
    }
}
