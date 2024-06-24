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
            choices: ['experimental', 'testing', 'laboratory'],
            description: 'Publish packages to repositories: testing (for RC), experimental: (for dev-latest), laboratory: (for FBs)',
            name: 'DESTINATION')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    // triggers {
    //     upstream upstreamProjects: 'pmm2-submodules-rewind', threshold: hudson.model.Result.SUCCESS
    // }
    stages {
        stage('Start PMM2 Client Autobuilds') {
            parallel {
                stage('Build PMM2 Client for AMD64') {
                    steps {
                        build job: 'pmm2-client-autobuild-amd', parameters: [
                            string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                            string(name: 'DESTINATION', value: params.DESTINATION)
                        ]
                    }
                }
                stage('Build PMM2 Client for ARM64') {
                    steps {
                        build job: 'tbr-pmm2-client-autobuilds-arm', parameters: [
                            string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                            string(name: 'DESTINATION', value: params.DESTINATION)
                        ]
                    }
                }
            }
        }
    }
}
