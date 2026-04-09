library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Helper function
def sendSlackNotification(repo, old_server_version, new_server_version, old_version_docker_tag, new_version_docker_tag, upgrade_docker_tag, with_postgis, milestone) {
    // Note: Result might be null if the build fails early, default to FAILURE
    def status = currentBuild.result ?: "FAILURE"
    def color = (status == "SUCCESS") ? "good" : "danger"

    def buildSummary = """Job: ${env.JOB_NAME}
Old Server Version: ${old_server_version}
New Server Version: ${new_server_version}
Old Version Docker Tag: ${old_version_docker_tag}
New Version Docker Tag: ${new_version_docker_tag}
Upgrade Docker Tag: ${upgrade_docker_tag}
Repo: ${repo}
With_PostGIS: ${with_postgis}
Milestone: ${milestone}
Status: *${status}*
Build Report: ${env.BUILD_URL}"""

    slackSend color: color, message: buildSummary, channel: '#postgresql-test'
}

pipeline {
    agent { label 'min-ol-9-x64' }

    parameters {
        string(name: 'OLD_SERVER_VERSION', defaultValue: '16.13', description: 'Old server version that needs to be upgraded: 16.13, 17.9, 18.3, etc.')
        string(name: 'NEW_SERVER_VERSION', defaultValue: '17.9', description: 'New server version to upgrade to: 17.9, 18.3. etc.')
        string(name: 'OLD_VERSION_DOCKER_TAG', defaultValue: '16.13-v2', description: 'Old version docker tag to test: 16.13-v2, 17.9-v2, 18.3-v2. etc.')
        string(name: 'NEW_VERSION_DOCKER_TAG', defaultValue: '17.9-v2', description: 'New version docker tag to test: 16.13-v2, 17.9-v2, 18.3-v2. etc.')
        string(name: 'UPGRADE_DOCKER_TAG', defaultValue: '18.3-17.9-16.13-1', description: 'Upgrade docker tag to use: 18.3-17.9-16.13-1, 18.3-17.9-16.13-2. etc.')
        string(name: 'TESTING_BRANCH', defaultValue: 'main', description: 'Branch for testing repository')
        choice(name: 'REPOSITORY', choices: ['perconalab', 'percona'], description: 'Docker hub repository.')
        choice(name: 'MILESTONE', choices: ['1', '2', '3'], description: 'DO Milestone.')
        booleanParam(name: 'WITH_POSTGIS', defaultValue: true, description: "Enable PostGIS testing.")
        booleanParam(name: 'DESTROY_ENV', defaultValue: true, description: 'Destroy VM after tests')
    }

    environment {
        PATH = "/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:${env.HOME}/.local/bin"
        MOLECULE_DIR = "docker/ppg-docker-DO-upgrade"
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
                    currentBuild.displayName = "#${env.BUILD_NUMBER}-docker-DO-upgrade-${params.OLD_SERVER_VERSION}-to-${params.NEW_SERVER_VERSION}"
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
                    def distros = [
                        'rocky-9',
                        'rhel-10',
                        'debian-12',
                        'debian-13',
                        'ubuntu-jammy',
                        'rocky-9-arm64',
                        'rhel-10-arm64',
                        'debian-12-arm64',
                        'debian-13-arm64',
                        'ubuntu-jammy-arm64',
                    ]
                    moleculeParallelTestPPG(distros, env.MOLECULE_DIR)
                }
            }
        }
    }

    post {
        always {
            script {
                if (params.DESTROY_ENV) {
                    def distros = [
                        'rocky-9',
                        'rhel-10',
                        'debian-12',
                        'debian-13',
                        'ubuntu-jammy',
                        'rocky-9-arm64',
                        'rhel-10-arm64',
                        'debian-12-arm64',
                        'debian-13-arm64',
                        'ubuntu-jammy-arm64',
                    ]
                    moleculeParallelPostDestroyPPG(distros, env.MOLECULE_DIR)
                }
                // FIX: Passing params explicitly
                sendSlackNotification(params.REPOSITORY, params.OLD_SERVER_VERSION, params.NEW_SERVER_VERSION, params.OLD_VERSION_DOCKER_TAG, params.NEW_VERSION_DOCKER_TAG, params.UPGRADE_DOCKER_TAG, params.WITH_POSTGIS, params.MILESTONE)
            }
        }
    }
}
