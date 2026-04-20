def prepareSources(params) {
    def branch = params.BRANCH?.trim() ? params.BRANCH.trim() : "release-${params.RELEASE}"

    echo "=========================[ Cloning the sources ]========================="
    echo "Using branch: ${branch}"

    sh """
        set -e
        rm -rf source
        git clone -b ${branch} https://github.com/percona/percona-server-mysql-operator.git source
    """
}

def loadReleaseVersions() {
    def content = readFile("source/e2e-tests/release_versions")
    def images = [:]

    content.readLines().each { line ->
        line = line.trim()
        if (!line || line.startsWith("#")) return

        def parts = line.split("=", 2)
        if (parts.size() == 2) {
            images[parts[0].trim()] = parts[1].trim().replace('"', '')
        }
    }

    return images
}

def getTag(image) {
    def parts = image.tokenize(":")
    return parts.size() > 1 ? parts[-1] : "latest"
}

def buildTargetImage(key, image, params) {
    def PS_OPERATOR_PROJECT_ID = "68f123a8583dd663019ea44d"
    def PS_IMAGES_PROJECT_ID  = "68f1227a9e42a645692ae3de"
    def REGISTRY = "quay.io/redhat-isv-containers"

    def isOperator = (key == 'IMAGE_OPERATOR')
    env.PROJECT_ID = isOperator ? PS_OPERATOR_PROJECT_ID : PS_IMAGES_PROJECT_ID
    env.REGISTRY_USER = isOperator ? params.REGISTRY_USER_OPERATOR : params.REGISTRY_USER_CONTAINERS
    env.REGISTRY_KEY  = isOperator ? params.REGISTRY_KEY_OPERATOR  : params.REGISTRY_KEY_CONTAINERS

    switch (key) {
        case 'IMAGE_OPERATOR':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_MYSQL84':
        case 'IMAGE_MYSQL80':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-ps-${getTag(image)}",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_BACKUP84':
        case 'IMAGE_BACKUP80':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-backup-${getTag(image)}",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_ROUTER84':
        case 'IMAGE_ROUTER80':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-router-${getTag(image)}",
                component: env.PROJECT_ID
            ]
        
        case 'IMAGE_BINLOG_SERVER':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-binlog-server",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_HAPROXY':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-haproxy",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_ORCHESTRATOR':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-orchestrator",
                component: env.PROJECT_ID
            ]

        case 'IMAGE_TOOLKIT':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-toolkit",
                component: env.PROJECT_ID
            ]
        
        case 'IMAGE_PMM_CLIENT':
            return [
                src: image,
                dest: "${REGISTRY}/${env.PROJECT_ID}:${params.RELEASE}-pmm3",
                component: env.PROJECT_ID
            ]

        default:
            echo "Skipping ${key}"
            return null
    }
}

def getPlatforms(platformParam) {
    return platformParam == "multiplatform" ? ["amd64", "arm64"] : [platformParam]
}

def certifyImage(target, params) {
    def platforms = getPlatforms(params.PLATFORM)

    platforms.each { platform ->
        sh """
            set -e
            python3 cloud/scripts/certify_images.py \
              --image ${target.src} \
              --dest_image ${target.dest} \
              --component ${target.component} \
              --platform ${platform}
        """
    }
}

pipeline {
    agent {
        label 'docker'
    }

    parameters {
        string(name: 'BRANCH', defaultValue: '', description: 'Optional override. Defaults to release-{RELEASE}')
        string(name: 'RELEASE', defaultValue: '1.0.0')

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
                    prepareSources(params)
                }
            }
        }

        stage('Certify Image') {
            steps {
                script {
                    def images = loadReleaseVersions()

                    def branch = params.BRANCH?.trim() ? params.BRANCH.trim() : "release-${params.RELEASE}"

                    echo "Release: ${params.RELEASE}"
                    echo "Branch: ${branch}"
                    echo "Platform: ${params.PLATFORM}"
                    echo "Selection: ${params.IMAGE}"

                    if (params.IMAGE == 'ALL') {

                        echo "Running certification for ALL images"

                        images.each { key, image ->
                            def target = buildTargetImage(key, image, params)
                            if (target == null) {
                                echo "Skipped ${key}"
                                return
                            }

                            echo "Processing ${key} -> ${image}"
                            certifyImage(target, params)
                        }

                    } else {

                        def selectedImage = images[params.IMAGE]

                        if (!selectedImage) {
                            error("Image not found in release_versions: ${params.IMAGE}")
                        }

                        echo "Processing ${params.IMAGE} -> ${selectedImage}"

                        def target = buildTargetImage(params.IMAGE, selectedImage, params)
                        certifyImage(target, params)
                    }
                }
            }
        }
    }
}
