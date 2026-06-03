def certificationTests = []
def certification
def certifiableImages = [
    'IMAGE_OPERATOR',
    'IMAGE_MYSQL84',
    'IMAGE_MYSQL80',
    'IMAGE_BACKUP84',
    'IMAGE_BACKUP80',
    'IMAGE_ROUTER84',
    'IMAGE_ROUTER80',
    'IMAGE_BINLOG_SERVER',
    'IMAGE_HAPROXY',
    'IMAGE_ORCHESTRATOR',
    'IMAGE_TOOLKIT',
    'IMAGE_PMM_CLIENT'
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
    def operatorProjectId = '68f123a8583dd663019ea44d'
    def containersProjectId = '68f1227a9e42a645692ae3de'
    def isOperator = (key == 'IMAGE_OPERATOR')
    def projectId = isOperator ? operatorProjectId : containersProjectId
    def credentials = isOperator ? 'PSO_OPERATOR_REGISTRY' : 'PSO_CONTAINERS_REGISTRY'

    switch (key) {
        case 'IMAGE_OPERATOR':
            return target(image, projectId, params.RELEASE, credentials)

        case 'IMAGE_MYSQL84':
        case 'IMAGE_MYSQL80':
            return target(image, projectId, "${params.RELEASE}-ps-${imageTag(image)}", credentials)

        case 'IMAGE_BACKUP84':
        case 'IMAGE_BACKUP80':
            return target(image, projectId, "${params.RELEASE}-backup-${imageTag(image)}", credentials)

        case 'IMAGE_ROUTER84':
        case 'IMAGE_ROUTER80':
            return target(image, projectId, "${params.RELEASE}-router-${imageTag(image)}", credentials)
        
        case 'IMAGE_BINLOG_SERVER':
            return target(image, projectId, "${params.RELEASE}-binlog-server", credentials)

        case 'IMAGE_HAPROXY':
            return target(image, projectId, "${params.RELEASE}-haproxy", credentials)

        case 'IMAGE_ORCHESTRATOR':
            return target(image, projectId, "${params.RELEASE}-orchestrator", credentials)

        case 'IMAGE_TOOLKIT':
            return target(image, projectId, "${params.RELEASE}-toolkit", credentials)
        
        case 'IMAGE_PMM_CLIENT':
            return target(image, projectId, "${params.RELEASE}-pmm3", credentials)

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
        booleanParam(name: 'IMAGE_MYSQL84', defaultValue: true, description: 'Certify IMAGE_MYSQL84')
        booleanParam(name: 'IMAGE_MYSQL80', defaultValue: true, description: 'Certify IMAGE_MYSQL80')
        booleanParam(name: 'IMAGE_BACKUP84', defaultValue: true, description: 'Certify IMAGE_BACKUP84')
        booleanParam(name: 'IMAGE_BACKUP80', defaultValue: true, description: 'Certify IMAGE_BACKUP80')
        booleanParam(name: 'IMAGE_ROUTER84', defaultValue: true, description: 'Certify IMAGE_ROUTER84')
        booleanParam(name: 'IMAGE_ROUTER80', defaultValue: true, description: 'Certify IMAGE_ROUTER80')
        booleanParam(name: 'IMAGE_BINLOG_SERVER', defaultValue: true, description: 'Certify IMAGE_BINLOG_SERVER')
        booleanParam(name: 'IMAGE_HAPROXY', defaultValue: true, description: 'Certify IMAGE_HAPROXY')
        booleanParam(name: 'IMAGE_ORCHESTRATOR', defaultValue: true, description: 'Certify IMAGE_ORCHESTRATOR')
        booleanParam(name: 'IMAGE_TOOLKIT', defaultValue: true, description: 'Certify IMAGE_TOOLKIT')
        booleanParam(name: 'IMAGE_PMM_CLIENT', defaultValue: true, description: 'Certify IMAGE_PMM_CLIENT')

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
                        repo: 'https://github.com/percona/percona-server-mysql-operator.git'
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
                        def target = buildTargetImage(key, image, params)
                        if (!target) {
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
