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
    def operatorProjectId = '5e62470102235d3f505f60e3'
    def containersProjectId = '5e627846b6bf136294e8bb8b'
    def isOperator = (key == 'IMAGE_OPERATOR')
    def projectId = isOperator ? operatorProjectId : containersProjectId
    def credentials = isOperator ? 'PSMDBO_OPERATOR_REGISTRY' : 'PSMDBO_CONTAINERS_REGISTRY'

    switch (key) {
        case 'IMAGE_OPERATOR':
            return target(image, projectId, params.RELEASE, credentials)

        case 'IMAGE_MONGOD60':
        case 'IMAGE_MONGOD70':
        case 'IMAGE_MONGOD80':
            return target(image, projectId, "${params.RELEASE}-psmdb-${imageTag(image)}", credentials)

        case 'IMAGE_BACKUP':
            return target(image, projectId, "${params.RELEASE}-backup", credentials)

        case 'IMAGE_PMM3_CLIENT':
            return target(image, projectId, "${params.RELEASE}-pmm3", credentials)

        case 'IMAGE_LOGCOLLECTOR':
            return target(image, projectId, "${params.RELEASE}-logcollector-${imageTag(image)}", credentials)

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
                'IMAGE_MONGOD60',
                'IMAGE_MONGOD70',
                'IMAGE_MONGOD80',
                'IMAGE_BACKUP',
                'IMAGE_PMM3_CLIENT',
                'IMAGE_LOGCOLLECTOR'
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
                        repo: 'https://github.com/percona/percona-server-mongodb-operator.git'
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
                        def target = buildTargetImage(key, image, params)
                        if (!target) {
                            skippedImages.add(key)
                            return
                        }

                        echo "Processing ${key} -> ${image}"
                        failedImages += certification.certifyImage(key, target, params, certificationTests) ? [] : [key]
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
