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
    def operatorProjectId = '5e6265b1b6bf136294e8bb82'
    def containersProjectId = '5e627965fe2231a0c28603fe'
    def operatorCredentials = 'PXCO_OPERATOR_REGISTRY'
    def containersCredentials = 'PXCO_CONTAINERS_REGISTRY'

    switch (key) {
        case 'IMAGE_OPERATOR':
            return target(image, operatorProjectId, params.RELEASE, operatorCredentials)

        case 'IMAGE_PMM3_CLIENT':
            return target(image, containersProjectId, "${params.RELEASE}-pmm3", containersCredentials)

        case 'IMAGE_PXC84':
        case 'IMAGE_PXC80':
        case 'IMAGE_PXC57':
            return target(image, containersProjectId, "${params.RELEASE}-pxc-${imageTag(image)}", containersCredentials)

        case 'IMAGE_BACKUP84':
        case 'IMAGE_BACKUP80':
        case 'IMAGE_BACKUP57':
            return target(image, containersProjectId, "${params.RELEASE}-backup-${imageTag(image)}", containersCredentials)

        case 'IMAGE_HAPROXY':
            return target(image, containersProjectId, "${params.RELEASE}-haproxy", containersCredentials)

        case 'IMAGE_PROXY':
            return target(image, containersProjectId, "${params.RELEASE}-proxysql", containersCredentials)

        case 'IMAGE_LOGCOLLECTOR':
            return target(image, containersProjectId, "${params.RELEASE}-logcollector-${imageTag(image)}", containersCredentials)

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
                'IMAGE_PXC84',
                'IMAGE_PXC80',
                'IMAGE_PXC57',
                'IMAGE_BACKUP84',
                'IMAGE_BACKUP80',
                'IMAGE_BACKUP57',
                'IMAGE_HAPROXY',
                'IMAGE_PROXY',
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

                    def release = certification.requireReleaseVersion(params)
                    currentBuild.displayName = release

                    def branch = params.BRANCH?.trim() ? params.BRANCH.trim() : "release-${release}"
                    certification.prepareSources(
                        branch: branch,
                        repo: 'https://github.com/percona/percona-xtradb-cluster-operator.git'
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
