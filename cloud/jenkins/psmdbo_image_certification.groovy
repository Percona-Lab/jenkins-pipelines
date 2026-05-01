def certificationTests = []
def certification = load "cloud/common/imageCertification.groovy"

def getMongoVersion(image) {
    def tag = certification.getTag(image)
    def matcher = tag =~ /([0-9]+\.[0-9]+\.[0-9]+-[0-9]+)/
    return matcher ? matcher[0][1] : tag
}

def buildTargetImage(key, image, params) {
    def PSMDB_OPERATOR_PROJECT_ID = "5e62470102235d3f505f60e3"
    def PSMDB_CONTAINERS_PROJECT_ID  = "5e627846b6bf136294e8bb8b"
    def REGISTRY = "quay.io/redhat-isv-containers"

    def isOperator = (key == 'IMAGE_OPERATOR')
    env.PROJECT_ID = isOperator ? PSMDB_OPERATOR_PROJECT_ID : PSMDB_CONTAINERS_PROJECT_ID
    env.REGISTRY_USER = isOperator ? params.REGISTRY_USER_OPERATOR : params.REGISTRY_USER_CONTAINERS
    env.REGISTRY_KEY  = isOperator ? params.REGISTRY_KEY_OPERATOR  : params.REGISTRY_KEY_CONTAINERS

    switch (key) {
        case 'IMAGE_OPERATOR':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_MONGOD60':
        case 'IMAGE_MONGOD70':
        case 'IMAGE_MONGOD80':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${getMongoVersion(image)}",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_BACKUP':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-backup",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_PMM3_CLIENT':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-pmm3",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_LOGCOLLECTOR':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-logcollector-${certification.getTag(image)}",
                component: env.PROJECT_ID
            ]

        default:
            echo "Skipping ${key}"
            return null
    }
}

pipeline {
    agent {
        label 'docker'
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

        booleanParam(
            name: 'SKIP_PUBLISHED',
            defaultValue: false,
            description: 'Skip preflight failure if image is already published'
        )

        password(
            name: 'PYXIS_TOKEN', 
            description: 'Access https://connect.redhat.com/account/api-keys to create one'
        )

        password(name: 'REGISTRY_USER_OPERATOR')
        password(name: 'REGISTRY_KEY_OPERATOR')

        password(name: 'REGISTRY_USER_CONTAINERS')
        password(name: 'REGISTRY_KEY_CONTAINERS')
    }

    stages {
        stage('Prepare Sources') {
            steps {
                script {
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
                    def images = certification.loadReleaseVersions()

                    def branch = params.BRANCH?.trim() ? params.BRANCH.trim() : "release-${params.RELEASE}"
                    env.CERTIFICATION_BRANCH = branch

                    echo "Release: ${params.RELEASE}"
                    echo "Branch: ${branch}"
                    echo "Platform: ${params.PLATFORM}"
                    echo "Selection: ${params.IMAGE}"

                    def failedImages = []

                    if (params.IMAGE == 'ALL') {

                        echo "Running certification for ALL images"

                        images.each { key, image ->
                            def target = buildTargetImage(key, image, params)
                            if (target == null) {
                                echo "Skipped ${key}"
                                return
                            }

                            echo "Processing ${key} -> ${image}"
                            if (!certification.certifyImage(key, target, params, certificationTests)) {
                                failedImages.add(key)
                            }
                        }

                    } else {

                        def selectedImage = images[params.IMAGE]
                        if (!selectedImage) {
                            error("Image not found in release_versions: ${params.IMAGE}")
                        }

                        echo "Processing ${params.IMAGE} -> ${selectedImage}"
                        def target = buildTargetImage(params.IMAGE, selectedImage, params)
                        if (!certification.certifyImage(params.IMAGE, target, params, certificationTests)) {
                            failedImages.add(params.IMAGE)
                        }
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
                certification.publishResults()
                certification.sendSlack(certificationTests, env.CERTIFICATION_BRANCH, params.PLATFORM)
            }
        }
    }
}
