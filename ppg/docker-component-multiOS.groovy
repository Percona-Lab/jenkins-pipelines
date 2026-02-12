library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(repo, server_version, server_docker_tag, component, component_docker_tag, component_version) {
    if (currentBuild.result == "SUCCESS") {
        buildSummary = "Job: ${env.JOB_NAME}\nRepo: ${repo}\nServer-Version: ${server_version}\nServer-Docker-tag: ${server_docker_tag}\nComponent: ${component}\nComponent-Docker-tag: ${component_docker_tag}\nComponent-Version: ${component_version}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
        slackSend color: "good", message: "${buildSummary}", channel: '#postgresql-test'
    } else {
        buildSummary = "Job: ${env.JOB_NAME}\nRepo: ${repo}\nServer-Version: ${server_version}\nServer-Docker-tag: ${server_docker_tag}\nComponent: ${component}\nComponent-Docker-tag: ${component_docker_tag}\nComponent-Version: ${component_version}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
        slackSend color: "danger", message: "${buildSummary}", channel: '#postgresql-test'
    }
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        string(
            defaultValue: '18.1',
            description: 'TAG of the server docker from perconalab/percona (hub.docker.com) to use. For example, 16, 16.1, 16.1-multi.',
            name: 'DOCKER_SERVER_TAG'
        )
        string(
            defaultValue: '18.1',
            description: 'Docker Server PG version being used, including both major and minor version. For example, 15.4.',
            name: 'SERVER_VERSION'
        )
        booleanParam(
            name: 'WITH_TDE',
            description: "Enable if testing the component with pg_tde enabled. Only works with PSP 17+ versions."
        )
        string(
            defaultValue: '2.57.0',
            description: 'TAG of the component, pgBackrest or pgBouncer, docker from perconalab/percona (hub.docker.com) to use. For example, 2.57.0.',
            name: 'DOCKER_COMPONENT_TAG'
        )
        string(
            defaultValue: '2.57',
            description: 'Component version to test for component docker, including both major and minor version. For example, 2.6.',
            name: 'COMPONENT_VERSION'
        )
        choice(
            name: 'COMPONENT',
            description: 'Component to test',
            choices: [
                'pgbackrest',
                'pgbouncer'
            ]
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        choice(
            name: 'REPOSITORY',
            description: 'Docker hub repository to use for docker images.',
            choices: [
                'percona',
                'perconalab'
            ]
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "docker/${COMPONENT}"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SERVER_VERSION}-${env.COMPONENT}-${env.COMPONENT_VERSION}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/ppg-testing.git'
            }
        }
        stage('Prepare') {
            steps {
                script {
                    installMoleculePython39()
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    moleculeParallelTestPPG(['rocky-9', 'rhel-10', 'debian-12', 'debian-13', 'ubuntu-jammy', 'rocky-9-arm64', 'rhel-10-arm64', 'debian-12-arm64', 'debian-13-arm64', 'ubuntu-jammy-arm64'], env.MOLECULE_DIR)
                }
            }
        }
    }
    post {
        always {
            script {
                if (params.DESTROY_ENV) {
                    moleculeParallelPostDestroyPPG(['rocky-9', 'rhel-10', 'debian-12', 'debian-13', 'ubuntu-jammy', 'rocky-9-arm64', 'rhel-10-arm64', 'debian-12-arm64', 'debian-13-arm64', 'ubuntu-jammy-arm64'], env.MOLECULE_DIR)
                }
                sendSlackNotification(env.REPOSITORY, env.SERVER_VERSION, env.DOCKER_SERVER_TAG, env.COMPONENT, env.DOCKER_COMPONENT_TAG, env.COMPONENT_VERSION)
            }
        }
    }
}
