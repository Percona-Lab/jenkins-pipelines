library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Helper function
def sendSlackNotification(repo, version, tag, with_postgis) {
    // Note: Result might be null if the build fails early, default to FAILURE
    def status = currentBuild.result ?: "FAILURE"
    def color = (status == "SUCCESS") ? "good" : "danger"
    
    def buildSummary = """Job: ${env.JOB_NAME}
Version: ${version}
Repo: ${repo}
Docker-tag: ${tag}
With_PostGIS: ${with_postgis}
Status: *${status}*
Build Report: ${env.BUILD_URL}"""

    slackSend color: color, message: buildSummary, channel: '#postgresql-test'
}

pipeline {
    agent { label 'min-ol-9-x64' }
    
    parameters {
        string(name: 'DOCKER_TAG', defaultValue: '18.3', description: 'TAG of the docker to test.')
        string(name: 'SERVER_VERSION', defaultValue: '18.3', description: 'Docker PG version to test.')
        string(name: 'TESTING_BRANCH', defaultValue: 'main', description: 'Branch for testing repository')
        choice(name: 'REPOSITORY', choices: ['perconalab', 'percona'], description: 'Docker hub repository.')
        booleanParam(name: 'WITH_POSTGIS', defaultValue: true, description: "Enable PostGIS testing.")
        booleanParam(name: 'DESTROY_ENV', defaultValue: true, description: 'Destroy VM after tests')
    }

    environment {
        PATH = "/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:${env.HOME}/.local/bin"
        MOLECULE_DIR = "docker/ppg-docker-DO"
    }

    options {
        // Ensure this shared library function returns the correct wrapper
        withCredentials(moleculeDistributionJenkinsCreds())
        timestamps() 
    }

    stages {
        stage('Set build name') {
            steps {
                script {
                    def suffix = params.WITH_POSTGIS ? "with-postgis" : "no-postgis"
                    currentBuild.displayName = "#${env.BUILD_NUMBER}-docker-DO-${suffix}-${params.SERVER_VERSION}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                // FIX: Accessing param via params object
                git branch: params.TESTING_BRANCH, url: 'https://github.com/Percona-QA/ppg-testing.git'
            }
        }
        stage('Prepare') {
            steps {
                script { installMoleculePython39() }
            }
        }
        stage('Test') {
            steps {
                script {
                    // List of distros to test
                    def distros = ['rocky-9', 'rhel-10', 'debian-12', 'debian-13', 'ubuntu-jammy', 
                                  'rocky-9-arm64', 'rhel-10-arm64', 'debian-12-arm64', 'debian-13-arm64', 'ubuntu-jammy-arm64']
                    moleculeParallelTestPPG(distros, env.MOLECULE_DIR)
                }
            }
        }
    }
    
    post {
        always {
            script {
                if (params.DESTROY_ENV) {
                    def distros = ['rocky-9', 'rhel-10', 'debian-12', 'debian-13', 'ubuntu-jammy', 
                                  'rocky-9-arm64', 'rhel-10-arm64', 'debian-12-arm64', 'debian-13-arm64', 'ubuntu-jammy-arm64']
                    moleculeParallelPostDestroyPPG(distros, env.MOLECULE_DIR)
                }
                // FIX: Passing params explicitly
                sendSlackNotification(params.REPOSITORY, params.SERVER_VERSION, params.DOCKER_TAG, params.WITH_POSTGIS)
            }
        }
    }
}