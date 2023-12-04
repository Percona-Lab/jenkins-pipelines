pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm3-submodules repository',
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
        upstream upstreamProjects: 'pmm3-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Start PMM3 Server Autobuilds') {
             steps {
                 build job: 'pmm3-server-autobuild-el9', parameters: [
                 string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                 string(name: 'DESTINATION', value: params.DESTINATION)
                 ]
             }
        }
        stage('Trigger a devcontainer build') {
            when {
                // a guard to avoid unnecessary builds
                expression { params.GIT_BRANCH == "v3" && params.DESTINATION == "experimental" }
            }          
            steps {
                script {
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        sh '''
                            # 'ref' is a required parameter, it should always equal 'v3' (or 'main' for v2)
                            curl -L -X POST \
                                -H "Accept: application/vnd.github+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/percona/pmm/actions/workflows/devcontainer.yml/dispatches" \
                                -d '{"ref":"v3"}'
                        '''
                    }
                }
            }
        }
    }
}
