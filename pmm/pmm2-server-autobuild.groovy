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
        stage('Start PMM2 Server Autobuilds') {
            parallel {
                stage('EL7') {
                    steps {
                        build job: 'pmm2-server-autobuild-el7', parameters: [
                            string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                            string(name: 'DESTINATION', value: params.DESTINATION)
                        ]
                    }
                }
                stage('EL9') {
                    steps {
                        build job: 'pmm2-server-autobuild-el9', parameters: [
                            string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                            string(name: 'DESTINATION', value: params.DESTINATION)
                        ]
                    }
                }
            }
        }
    }
}