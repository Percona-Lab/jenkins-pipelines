def certificationTests = []
def certification

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
            return target(image, operatorProjectId, "${params.RELEASE}-postgres-operator", operatorCredentials)

        case 'IMAGE_PMM3_CLIENT':
            return target(image, containersProjectId, "${params.RELEASE}-pmm3", containersCredentials)

        case 'IMAGE_UPGRADE':
            return target(image, containersProjectId, "${params.RELEASE}-upgrade", containersCredentials)

        case 'IMAGE_POSTGRESQL14':
        case 'IMAGE_POSTGRESQL15':
        case 'IMAGE_POSTGRESQL16':
        case 'IMAGE_POSTGRESQL17':
        case 'IMAGE_POSTGRESQL18':
            return target(image, containersProjectId, "${params.RELEASE}-postgres-${imageTag(image)}", containersCredentials)

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

        choice(
            name: 'IMAGE',
            choices: [
                'ALL',
                'IMAGE_OPERATOR',
                'IMAGE_PMM3_CLIENT',
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
            ],
            description: 'Select image to certify'
        )

        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
    }

    stages {
        stage('Prepare Sources') {
            steps {
                script {
                    certification = load "cloud/common/imageCertification.groovy"

                    if (params.RELEASE?.trim()) {
                        currentBuild.displayName = params.RELEASE.trim()
                    }

                    def branch = params.BRANCH?.trim() ? params.BRANCH.trim() : "release-${params.RELEASE}"
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
                    echo "Selection: ${params.IMAGE}"

                    def failedImages = []
                    def skippedImages = []
                    def imagesToCertify = images

                    if (params.IMAGE == 'ALL') {
                        echo "Running certification for ALL images"
                    } else {
                        def selectedImage = images[params.IMAGE]
                        if (!selectedImage) {
                            error("Image not found in release_versions: ${params.IMAGE}")
                        }

                        imagesToCertify = [(params.IMAGE): selectedImage]
                    }

                    imagesToCertify.each { key, image ->
                        def imageTarget = buildTargetImage(key, image, params)
                        if (!imageTarget) {
                            skippedImages.add(key)
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
