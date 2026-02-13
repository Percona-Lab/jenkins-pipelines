def CANDIDATE_DOCKER_REPO = 'perconalab/percona-server-mongodb-operator'
def PRODUCTION_DOCKER_REPO = 'percona/percona-server-mongodb-operator'
def RELEASE_BRANCH_PATTERN = /^release-(\d+\.\d+\.\d+)$/
def SEMVER_PATTERN = /^\d+\.\d+\.\d+$/

String extractReleaseVersion(String branchName) {
    def match = branchName =~ RELEASE_BRANCH_PATTERN
    if (!match) {
        return null
    }
    return match[0][1]
}

void build(String IMAGE_SUFFIX){
    sh """
        set -e

        cd ./source/
        if [ ${IMAGE_SUFFIX} = backup ]; then
            docker build --no-cache --progress plain --squash -t ${CANDIDATE_DOCKER_REPO}:${GIT_PD_BRANCH}-${IMAGE_SUFFIX} \
                         -f percona-backup-mongodb/Dockerfile percona-backup-mongodb
        else
            DOCKER_FILE_PREFIX=\$(echo ${IMAGE_SUFFIX} | tr -d 'mongod')
            docker build --no-cache --progress plain --squash -t ${CANDIDATE_DOCKER_REPO}:${GIT_PD_BRANCH}-${IMAGE_SUFFIX} \
                         -f percona-server-mongodb-\$DOCKER_FILE_PREFIX/Dockerfile percona-server-mongodb-\$DOCKER_FILE_PREFIX

            docker build --build-arg DEBUG=1 --no-cache --progress plain --squash -t ${CANDIDATE_DOCKER_REPO}:${GIT_PD_BRANCH}-${IMAGE_SUFFIX}-debug \
                         -f percona-server-mongodb-\$DOCKER_FILE_PREFIX/Dockerfile percona-server-mongodb-\$DOCKER_FILE_PREFIX
        fi
    """
}

void pushImageToDocker(String IMAGE_SUFFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=${IMAGE_SUFFIX}
            sg docker -c "
                set -e
                docker login -u '${USER}' -p '${PASS}'
                docker push ${CANDIDATE_DOCKER_REPO}:${GIT_PD_BRANCH}-${IMAGE_SUFFIX}
                docker logout
            "
            echo "${CANDIDATE_DOCKER_REPO}:${GIT_PD_BRANCH}-${IMAGE_SUFFIX}" >> list-of-images.txt
        """
    }
}
void generateImageSummary(filePath) {
    def images = readFile(filePath).trim().split("\n")

    def report = "<h2>Image Summary Report</h2>\n"
    report += "<p><strong>Total Images:</strong> ${images.size()}</p>\n"
    report += "<ul>\n"

    images.each { image ->
        report += "<li>${image}</li>\n"
    }

    report += "</ul>\n"
    return report
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona/percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-docker repository',
            name: 'GIT_PD_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-docker',
            description: 'percona/percona-docker repository',
            name: 'GIT_PD_REPO')
        booleanParam(
            defaultValue: false,
            description: 'Allow promotion from perconalab to percona for release branches',
            name: 'PROMOTE_TO_PERCONA')
    }
    agent {
         label 'docker-x64-min'
    }
    environment {
        PATH = "${WORKSPACE}/node_modules/.bin:$PATH" // Add local npm bin to PATH
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout
                """
                stash includes: "cloud/**" , name: "checkout"
                stash includes: "source/**", name: "sourceFILES"

                sh '''
                    rm -rf cloud
                '''
                script {
                    def releaseVersion = extractReleaseVersion(params.GIT_BRANCH)
                    env.RELEASE_VERSION = releaseVersion ?: ''
                    env.IS_RELEASE_BRANCH = releaseVersion ? 'YES' : 'NO'
                    echo "Release branch detected: ${env.IS_RELEASE_BRANCH}"
                    if (params.PROMOTE_TO_PERCONA) {
                        if (env.IS_RELEASE_BRANCH != 'YES') {
                            error("PROMOTE_TO_PERCONA=true is allowed only for release branches (release-x.y.z). GIT_BRANCH=${params.GIT_BRANCH}")
                        }
                        if (params.GIT_PD_BRANCH != params.GIT_BRANCH) {
                            error("PROMOTE_TO_PERCONA=true requires GIT_PD_BRANCH to match GIT_BRANCH. Got GIT_BRANCH=${params.GIT_BRANCH}, GIT_PD_BRANCH=${params.GIT_PD_BRANCH}")
                        }
                        if (!(env.RELEASE_VERSION ==~ SEMVER_PATTERN)) {
                            error("Extracted release version '${env.RELEASE_VERSION}' is not strict semver (x.y.z)")
                        }
                    }
                }
            }
        }

        stage('Build and push PSMDB operator docker image') {
            steps {
                retry(3) {
                    timeout(time: 30, unit: 'MINUTES') {
                        unstash "sourceFILES"
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh """
                                docker buildx create --use
                                sg docker -c "
                                    docker login -u '\${USER}' -p '\${PASS}'
                                    pushd source
                                    export IMAGE=${CANDIDATE_DOCKER_REPO}:\${GIT_BRANCH}
                                    DOCKER_DEFAULT_PLATFORM='linux/amd64,linux/arm64' ./e2e-tests/build
                                    popd
                                    docker logout
                                "
                                echo "${CANDIDATE_DOCKER_REPO}:\${GIT_BRANCH}" >> list-of-images.txt
                            """
                        }
                    }
                }
            }
        }


        stage('Build PSMDB docker images') {
            steps {
                unstash "checkout"
                sh """
                    sudo mv ./source ./operator-source
                    export GIT_REPO=$GIT_PD_REPO
                    export GIT_BRANCH=$GIT_PD_BRANCH
                    ./cloud/local/checkout
                """
                echo 'Build PBM docker image'
                retry(3) {
                    build('backup')
                }
                echo 'Build PSMDB docker images'
                retry(3) {
                    build('mongod6.0')
                }
                retry(3) {
                    build('mongod7.0')
                }
                retry(3) {
                    build('mongod8.0')
                }
            }
        }

        stage('Push PSMDB images to Docker registry') {
            steps {
                pushImageToDocker('mongod6.0')
                pushImageToDocker('mongod6.0-debug')
                pushImageToDocker('mongod7.0')
                pushImageToDocker('mongod7.0-debug')
                pushImageToDocker('mongod8.0')
                pushImageToDocker('mongod8.0-debug')
                pushImageToDocker('backup')
            }
        }

        stage('Approve percona promotion') {
            when {
                expression { params.PROMOTE_TO_PERCONA && env.IS_RELEASE_BRANCH == 'YES' }
            }
            steps {
                script {
                    def approval = input(
                        message: "Promote release ${env.RELEASE_VERSION} images to ${PRODUCTION_DOCKER_REPO}?",
                        ok: 'Promote',
                        submitterParameter: 'APPROVED_BY',
                        parameters: [
                            string(
                                defaultValue: env.RELEASE_VERSION,
                                description: 'Type the release version to confirm promotion',
                                name: 'RELEASE_VERSION_CONFIRM'
                            )
                        ]
                    )
                    def approvedBy = 'unknown'
                    def confirmedVersion = ''
                    if (approval instanceof Map) {
                        approvedBy = approval.get('APPROVED_BY', 'unknown')
                        confirmedVersion = approval.get('RELEASE_VERSION_CONFIRM', '')
                    } else {
                        approvedBy = approval ?: 'unknown'
                        confirmedVersion = env.RELEASE_VERSION
                    }
                    if (confirmedVersion != env.RELEASE_VERSION) {
                        error("Confirmation mismatch. Expected '${env.RELEASE_VERSION}', got '${confirmedVersion}'")
                    }
                    env.PROMOTION_APPROVED_BY = approvedBy
                    echo "Promotion approved by: ${env.PROMOTION_APPROVED_BY}"
                }
            }
        }

        stage('Promote release images to percona') {
            when {
                expression { params.PROMOTE_TO_PERCONA && env.IS_RELEASE_BRANCH == 'YES' }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        set -euo pipefail

                        if ! [[ "\${RELEASE_VERSION}" =~ ^[0-9]+\\.[0-9]+\\.[0-9]+$ ]]; then
                            echo "Invalid RELEASE_VERSION: \${RELEASE_VERSION}"
                            exit 1
                        fi

                        promote_image() {
                            local source_tag="$1"
                            local destination_tag="$2"
                            local source_ref="${CANDIDATE_DOCKER_REPO}:\${source_tag}"
                            local destination_ref="${PRODUCTION_DOCKER_REPO}:\${destination_tag}"

                            sg docker -c "
                                set -e
                                docker login -u '\${USER}' -p '\${PASS}'
                                docker manifest inspect \${source_ref} >/dev/null
                                source_digest=\$(docker buildx imagetools inspect \${source_ref} | awk '/Digest:/ {print \$2; exit}')
                                if docker manifest inspect \${destination_ref} >/dev/null 2>&1; then
                                    echo 'Destination image already exists: '\${destination_ref}
                                    docker logout
                                    exit 1
                                fi
                                docker buildx imagetools create --tag \${destination_ref} \${source_ref}
                                destination_digest=\$(docker buildx imagetools inspect \${destination_ref} | awk '/Digest:/ {print \$2; exit}')
                                echo 'Source digest: '\${source_digest}
                                echo 'Destination digest: '\${destination_digest}
                                docker logout
                            "
                            echo "\${destination_ref}" >> list-of-images.txt
                            echo "Promoted \${source_ref} -> \${destination_ref}"
                        }

                        promote_image "\${GIT_BRANCH}" "\${RELEASE_VERSION}"
                    """
                }
                echo "Release promotion completed by ${env.PROMOTION_APPROVED_BY}"
            }
        }
    }

    post {
        always {
            script {
                def summary = generateImageSummary('list-of-images.txt')

                addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
                    text: "<pre>${summary}</pre>"
                )
                // Also save as a file if needed
                 writeFile(file: 'image-summary.html', text: summary)
            }
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./source/build
            '''
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PSMDB docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PSMDB docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
