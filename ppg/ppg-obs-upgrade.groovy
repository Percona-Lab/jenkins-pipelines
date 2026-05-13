library changelog: false, identifier: "lib@obs", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def platformsList = []
def defaultPlatforms = [
    'debian-12',
    'debian-12-arm64',
    'debian-13',
    'debian-13-arm64',
    'rhel-10',
    'rhel-10-arm64',
    'rocky-9',
    'rocky-9-arm64',
    'ubuntu-jammy',
    'ubuntu-jammy-arm64',
].join('\n')

pipeline {
    agent {
        label 'min-ol-9-x64'
    }

    parameters {
        text(
            name: 'PLATFORMS',
            description: 'Molecule scenarios to test, one per line. Must match subdirectories under docker/ppg-docker-upgrade/molecule/ in the testing repo.',
            defaultValue: defaultPlatforms
        )
        string(
            name: 'REPOSITORY',
            description: 'Docker registry prefix for all images (e.g. perconalab, or a full OBS registry URL)',
            defaultValue: 'perconalab'
        )
        string(
            name: 'OLD_SERVER_VERSION',
            description: 'Source PG version to upgrade from (e.g. 17.9)',
            defaultValue: '17.9'
        )
        string(
            name: 'NEW_SERVER_VERSION',
            description: 'Target PG version to upgrade to (e.g. 18.3)',
            defaultValue: '18.3'
        )
        string(
            name: 'OLD_VERSION_DOCKER_TAG',
            description: 'Docker image tag for the old version image (defaults to OLD_SERVER_VERSION if empty)',
            defaultValue: ''
        )
        string(
            name: 'NEW_VERSION_DOCKER_TAG',
            description: 'Docker image tag for the new version image (defaults to NEW_SERVER_VERSION if empty)',
            defaultValue: ''
        )
        string(
            name: 'UPGRADE_DOCKER_TAG',
            description: 'Tag for the pg_upgrade mediator image (e.g. 18.3-17.9-16.13-1). Required.',
            defaultValue: '18.3-17.9-16.13-1'
        )
        string(
            name: 'OLD_DOCKER_REPOSITORY',
            description: 'Registry prefix override for the old image (defaults to REPOSITORY if empty)',
            defaultValue: ''
        )
        string(
            name: 'NEW_DOCKER_REPOSITORY',
            description: 'Registry prefix override for the new image (defaults to REPOSITORY if empty)',
            defaultValue: ''
        )
        string(
            name: 'UPGRADE_DOCKER_REPOSITORY',
            description: 'Registry prefix override for the mediator image (defaults to NEW_DOCKER_REPOSITORY if empty)',
            defaultValue: ''
        )
        string(
            name: 'PPG_IMAGE_NAME',
            description: 'PPG Docker image name',
            defaultValue: 'percona-distribution-postgresql'
        )
        string(
            name: 'PPG_UPGRADE_IMAGE_NAME',
            description: 'pg_upgrade mediator image name',
            defaultValue: 'percona-distribution-postgresql-upgrade'
        )
        string(
            name: 'MILESTONE',
            description: 'Milestone level for tests (0 = GA, higher = pre-release)',
            defaultValue: '0'
        )
        booleanParam(
            name: 'WITH_POSTGIS',
            defaultValue: false,
            description: 'Enable PostGIS tests'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = 'docker/ppg-docker-upgrade'
        REPOSITORY           = "${params.REPOSITORY}"
        OLD_SERVER_VERSION   = "${params.OLD_SERVER_VERSION}"
        NEW_SERVER_VERSION   = "${params.NEW_SERVER_VERSION}"
        OLD_VERSION_DOCKER_TAG   = "${params.OLD_VERSION_DOCKER_TAG}"
        NEW_VERSION_DOCKER_TAG   = "${params.NEW_VERSION_DOCKER_TAG}"
        UPGRADE_DOCKER_TAG       = "${params.UPGRADE_DOCKER_TAG}"
        OLD_DOCKER_REPOSITORY    = "${params.OLD_DOCKER_REPOSITORY}"
        NEW_DOCKER_REPOSITORY    = "${params.NEW_DOCKER_REPOSITORY}"
        UPGRADE_DOCKER_REPOSITORY = "${params.UPGRADE_DOCKER_REPOSITORY}"
        PPG_IMAGE_NAME           = "${params.PPG_IMAGE_NAME}"
        PPG_UPGRADE_IMAGE_NAME   = "${params.PPG_UPGRADE_IMAGE_NAME}"
        MILESTONE                = "${params.MILESTONE}"
        WITH_POSTGIS             = "${params.WITH_POSTGIS}"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
    }
    stages {
        stage('Set platforms') {
            steps {
                script {
                    platformsList = params.PLATFORMS
                        .split('\n')
                        .collect { it.trim() }
                        .findAll { it }
                    if (platformsList.isEmpty()) {
                        error('PLATFORMS parameter is empty — provide at least one scenario name.')
                    }
                    echo "Targeting ${platformsList.size()} scenario(s): ${platformsList.join(', ')}"
                }
            }
        }
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${params.OLD_SERVER_VERSION}-to-${params.NEW_SERVER_VERSION}-${platformsList.size()}os"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/rjd15372/ppg-testing.git'
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
                    moleculeParallelTestPPG(platformsList, env.MOLECULE_DIR)
                }
            }
        }
    }
    post {
        always {
            script {
                if (params.DESTROY_ENV) {
                    echo "DESTROY_ENV is true. Cleaning up ${platformsList.size()} scenario(s) in parallel..."
                    moleculeParallelPostDestroyPPG(platformsList, env.MOLECULE_DIR)
                } else {
                    echo "DESTROY_ENV is false. Leaving VMs active for debugging."
                }
            }
        }
    }
}
