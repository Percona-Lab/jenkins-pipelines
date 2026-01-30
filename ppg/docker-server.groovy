library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'min-ol-9-x64'
    }

    parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: [
                'debian-12',
                'rocky-9',
                'ubuntu-jammy',
                'rhel-10',
                'debian-13',
                'debian-12-arm64',
                'rocky-9-arm64',
                'ubuntu-jammy-arm64',
                'rhel-10-arm64',
                'debian-13-arm64',
            ]
        )
        string(
            defaultValue: '18.1',
            description: 'TAG of the docker to test. For example, 16, 16.1, 16.1-multi.',
            name: 'DOCKER_TAG'
        )
        string(
            defaultValue: '18.1',
            description: 'Docker PG version to test, including both major and minor version. For example, 15.4.',
            name: 'SERVER_VERSION'
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
            name: 'WITH_POSTGIS',
            description: "Enable if testing a psp/ppg server docker that also contains postgis."
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "docker/ppg-docker"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    if (params.WITH_POSTGIS) {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-docker-with-postgis-${env.SERVER_VERSION}-${env.PLATFORM}"
                    } else {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-docker-${env.SERVER_VERSION}-${env.PLATFORM}"
                    }
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
        stage('Create virtual machines') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "create", env.PLATFORM)
                }
            }
        }
        stage('Run playbook for test') {
            steps {
                script {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "converge", env.PLATFORM)
                }
            }
        }
    }
    post {
        always {
            script {
                if (env.DESTROY_ENV) {
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "destroy", env.PLATFORM)
                }
            }
        }
    }
}