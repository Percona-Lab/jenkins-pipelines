pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm3-submodules repository',
            name: 'GIT_BRANCH'
        )
        choice(
            // default is choices.get(0) - experimental
            choices: ['experimental', 'testing', 'laboratory'],
            description: 'Repo component to push packages to',
            name: 'DESTINATION'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
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
    }
}
