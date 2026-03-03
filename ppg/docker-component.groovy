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
            defaultValue: '18.3',
            description: 'TAG of the server docker from perconalab/percona to use from hub.docker.com. For example, 16, 16.1, 16.1-multi.',
            name: 'DOCKER_SERVER_TAG'
        )
        string(
            defaultValue: '18.3',
            description: 'Docker Server PG version being used, including both major and minor version. For example, 15.4.',
            name: 'SERVER_VERSION'
        )
        booleanParam(
            name: 'WITH_TDE',
            description: "Enable if testing the component with pg_tde enabled. Only works with PSP 17+ versions."
        )
        string(
            defaultValue: '2.58.0',
            description: 'TAG of the component, pgBackrest or pgBouncer, docker from perconalab/percona to use from hub.docker.com.. For example, 16, 16.1, 16.1-multi.',
            name: 'DOCKER_COMPONENT_TAG'
        )
        string(
            defaultValue: '2.58',
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
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-docker-${env.SERVER_VERSION}-${env.PLATFORM}-${env.COMPONENT}-${env.COMPONENT_VERSION}"
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
                if (params.DESTROY_ENV) {
                    echo "DESTROY_ENV is true. Cleaning up resources..."
                    moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "destroy", env.PLATFORM)
                } else {
                    echo "DESTROY_ENV is false. Leaving VMs active for debugging."
                }
            }
        }
    }
}
