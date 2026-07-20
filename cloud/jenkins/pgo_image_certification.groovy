def certificationTests = []
def certification
def certifiableImages = [
    'IMAGE_OPERATOR',
    'IMAGE_PMM_CLIENT',
    'IMAGE_UPGRADE',
    'IMAGE_POSTGRESQL14',
    'IMAGE_POSTGRESQL15',
    'IMAGE_POSTGRESQL16',
    'IMAGE_POSTGRESQL17',
    'IMAGE_POSTGRESQL18',
    'IMAGE_POSTGIS14',
    'IMAGE_POSTGIS15',
    'IMAGE_POSTGIS16',
    'IMAGE_POSTGIS17',
    'IMAGE_POSTGIS18',
    'IMAGE_PGBOUNCER14',
    'IMAGE_PGBOUNCER15',
    'IMAGE_PGBOUNCER16',
    'IMAGE_PGBOUNCER17',
    'IMAGE_PGBOUNCER18',
    'IMAGE_BACKREST14',
    'IMAGE_BACKREST15',
    'IMAGE_BACKREST16',
    'IMAGE_BACKREST17',
    'IMAGE_BACKREST18'
]

def imageTag(image) {
    def parts = image.tokenize(":")
    return parts.size() > 1 ? parts[-1] : "latest"
}

def target(src, projectId, tag, credentials) {
    return [
        src: src,
        dest: "quay.io/redhat-isv-containers/${projectId}:${tag}",
        credentials: credentials
    ]
}

def buildTargetImage(key, image, params) {
    def operatorProjectId = '612f4f0b0c5abda5dd37c619'
    def containersProjectId = '6137355a74d3177984523152'
    def operatorCredentials = 'PGO_OPERATOR_REGISTRY'
    def containersCredentials = 'PGO_CONTAINERS_REGISTRY'

    switch (key) {
        case 'IMAGE_OPERATOR':
            return target(image, operatorProjectId, params.RELEASE, operatorCredentials)

        case 'IMAGE_PMM_CLIENT':
            return target(image, containersProjectId, "${params.RELEASE}-pmm3", containersCredentials)

        case 'IMAGE_UPGRADE':
            return target(image, containersProjectId, "${params.RELEASE}-upgrade", containersCredentials)

        case 'IMAGE_POSTGRESQL14':
        case 'IMAGE_POSTGRESQL15':
        case 'IMAGE_POSTGRESQL16':
        case 'IMAGE_POSTGRESQL17':
        case 'IMAGE_POSTGRESQL18':
            return target(image, containersProjectId, "${params.RELEASE}-pg-${imageTag(image)}", containersCredentials)

        case 'IMAGE_POSTGIS14':
        case 'IMAGE_POSTGIS15':
        case 'IMAGE_POSTGIS16':
        case 'IMAGE_POSTGIS17':
        case 'IMAGE_POSTGIS18':
            return target(image, containersProjectId, "${params.RELEASE}-postgis-${imageTag(image)}", containersCredentials)

        case 'IMAGE_PGBOUNCER14':
        case 'IMAGE_PGBOUNCER15':
        case 'IMAGE_PGBOUNCER16':
        case 'IMAGE_PGBOUNCER17':
        case 'IMAGE_PGBOUNCER18':
            return target(image, containersProjectId, "${params.RELEASE}-pgbouncer-${imageTag(image)}", containersCredentials)

        case 'IMAGE_BACKREST14':
        case 'IMAGE_BACKREST15':
        case 'IMAGE_BACKREST16':
        case 'IMAGE_BACKREST17':
        case 'IMAGE_BACKREST18':
            return target(image, containersProjectId, "${params.RELEASE}-pgbackrest-${imageTag(image)}", containersCredentials)

        default:
            echo "Skipping ${key}"
            return null
    }
}

pipeline {
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }

    parameters {
        string(name: 'BRANCH', defaultValue: '', description: 'Optional override. Defaults to release-{RELEASE}')
        string(name: 'RELEASE', defaultValue: '')

        choice(
            name: 'PLATFORM',
            choices: ['amd64', 'arm64', 'multiplatform'],
            description: 'Target platform'
        )

        booleanParam(name: 'IMAGE_OPERATOR', defaultValue: true, description: 'Certify IMAGE_OPERATOR')
        booleanParam(name: 'IMAGE_PMM_CLIENT', defaultValue: true, description: 'Certify IMAGE_PMM_CLIENT')
        booleanParam(name: 'IMAGE_UPGRADE', defaultValue: true, description: 'Certify IMAGE_UPGRADE')
        booleanParam(name: 'IMAGE_POSTGRESQL14', defaultValue: true, description: 'Certify IMAGE_POSTGRESQL14')
        booleanParam(name: 'IMAGE_POSTGRESQL15', defaultValue: true, description: 'Certify IMAGE_POSTGRESQL15')
        booleanParam(name: 'IMAGE_POSTGRESQL16', defaultValue: true, description: 'Certify IMAGE_POSTGRESQL16')
        booleanParam(name: 'IMAGE_POSTGRESQL17', defaultValue: true, description: 'Certify IMAGE_POSTGRESQL17')
        booleanParam(name: 'IMAGE_POSTGRESQL18', defaultValue: true, description: 'Certify IMAGE_POSTGRESQL18')
        booleanParam(name: 'IMAGE_POSTGIS14', defaultValue: true, description: 'Certify IMAGE_POSTGIS14')
        booleanParam(name: 'IMAGE_POSTGIS15', defaultValue: true, description: 'Certify IMAGE_POSTGIS15')
        booleanParam(name: 'IMAGE_POSTGIS16', defaultValue: true, description: 'Certify IMAGE_POSTGIS16')
        booleanParam(name: 'IMAGE_POSTGIS17', defaultValue: true, description: 'Certify IMAGE_POSTGIS17')
        booleanParam(name: 'IMAGE_POSTGIS18', defaultValue: true, description: 'Certify IMAGE_POSTGIS18')
        booleanParam(name: 'IMAGE_PGBOUNCER14', defaultValue: true, description: 'Certify IMAGE_PGBOUNCER14')
        booleanParam(name: 'IMAGE_PGBOUNCER15', defaultValue: true, description: 'Certify IMAGE_PGBOUNCER15')
        booleanParam(name: 'IMAGE_PGBOUNCER16', defaultValue: true, description: 'Certify IMAGE_PGBOUNCER16')
        booleanParam(name: 'IMAGE_PGBOUNCER17', defaultValue: true, description: 'Certify IMAGE_PGBOUNCER17')
        booleanParam(name: 'IMAGE_PGBOUNCER18', defaultValue: true, description: 'Certify IMAGE_PGBOUNCER18')
        booleanParam(name: 'IMAGE_BACKREST14', defaultValue: true, description: 'Certify IMAGE_BACKREST14')
        booleanParam(name: 'IMAGE_BACKREST15', defaultValue: true, description: 'Certify IMAGE_BACKREST15')
        booleanParam(name: 'IMAGE_BACKREST16', defaultValue: true, description: 'Certify IMAGE_BACKREST16')
        booleanParam(name: 'IMAGE_BACKREST17', defaultValue: true, description: 'Certify IMAGE_BACKREST17')
        booleanParam(name: 'IMAGE_BACKREST18', defaultValue: true, description: 'Certify IMAGE_BACKREST18')

        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
    }

    stages {
        stage('Prepare Sources') {
            steps {
                script {
                    certification = load "cloud/common/imageCertification.groovy"

                    def release = certification.requireReleaseVersion(params)
                    currentBuild.displayName = release

                    def branch = params.BRANCH?.trim() ? params.BRANCH.trim() : "release-${release}"
                    certification.prepareSources(
                        branch: branch,
                        repo: 'https://github.com/percona/percona-postgresql-operator.git'
                    )
                }
            }
        }

        stage('Certify Image') {
            steps {
                script {
                    certification = certification ?: load("cloud/common/imageCertification.groovy")

                    def images = certification.loadReleaseVersions()
                    def branch = params.BRANCH?.trim() ? params.BRANCH.trim() : "release-${params.RELEASE}"
                    env.CERTIFICATION_BRANCH = branch

                    echo "Release: ${params.RELEASE}"
                    echo "Branch: ${branch}"
                    echo "Platform: ${params.PLATFORM}"
                    def selectedImageKeys = certifiableImages.findAll { params[it] }

                    echo "Selection: ${selectedImageKeys.join(', ')}"

                    def failedImages = []
                    def skippedImages = []
                    def imagesToCertify = selectedImageKeys.collectEntries { key ->
                        def selectedImage = images[key]
                        if (!selectedImage) {
                            error("Image not found in release_versions: ${key}")
                        }

                        [(key): selectedImage]
                    }

                    if (!imagesToCertify) {
                        error("Select at least one image to certify")
                    }

                    imagesToCertify.each { key, image ->
                        def imageTarget = buildTargetImage(key, image, params)
                        if (!imageTarget) {
                            skippedImages.add(key)
                            certificationTests.add([
                                name: key,
                                cluster: params.PLATFORM,
                                result: 'skipped',
                                time: 0,
                            ])
                            return
                        }

                        echo "Processing ${key} -> ${image}"
                        failedImages += certification.certifyImage(key, imageTarget, params, certificationTests) ? [] : [key]
                    }

                    if (skippedImages) {
                        echo "Certification skipped for: ${skippedImages.join(', ')}"
                    }

                    if (failedImages) {
                        currentBuild.result = 'UNSTABLE'
                        echo "Certification failed for: ${failedImages.join(', ')}"
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                certification = certification ?: load("cloud/common/imageCertification.groovy")

                certification.publishResults()
                certification.sendSlack(certificationTests, env.CERTIFICATION_BRANCH, params.PLATFORM, params.RELEASE)
            }
        }
    }
}
