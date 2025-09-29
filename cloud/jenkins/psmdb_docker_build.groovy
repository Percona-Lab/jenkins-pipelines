void build(String IMAGE_SUFFIX){
    sh """
        set -e

        cd ./source/
        if [ ${IMAGE_SUFFIX} = backup ]; then
            docker build --no-cache --progress plain --squash -t perconalab/percona-server-mongodb-operator:${GIT_PD_BRANCH}-${IMAGE_SUFFIX} \
                         -f percona-backup-mongodb/Dockerfile percona-backup-mongodb
        else
            DOCKER_FILE_PREFIX=\$(echo ${IMAGE_SUFFIX} | tr -d 'mongod')
            docker build --no-cache --progress plain --squash -t perconalab/percona-server-mongodb-operator:${GIT_PD_BRANCH}-${IMAGE_SUFFIX} \
                         -f percona-server-mongodb-\$DOCKER_FILE_PREFIX/Dockerfile percona-server-mongodb-\$DOCKER_FILE_PREFIX

            docker build --build-arg DEBUG=1 --no-cache --progress plain --squash -t perconalab/percona-server-mongodb-operator:${GIT_PD_BRANCH}-${IMAGE_SUFFIX}-debug \
                         -f percona-server-mongodb-\$DOCKER_FILE_PREFIX/Dockerfile percona-server-mongodb-\$DOCKER_FILE_PREFIX
        fi
    """
}
void checkImageForDocker(String IMAGE_SUFFIX){
    try {
             withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), string(credentialsId: 'SNYK_ID', variable: 'SNYK_ID')]) {
                sh """
                    IMAGE_SUFFIX=${IMAGE_SUFFIX}
                    IMAGE_NAME='percona-server-mongodb-operator'
                    MONGODB_VER=\$(echo ${IMAGE_SUFFIX} | tr -d '\\-debug' | tr -d 'mongod')
                    PATH_TO_DOCKERFILE="source/percona-server-mongodb-\${MONGODB_VER}"
                    IMAGE_TAG="\${GIT_PD_BRANCH}-\${IMAGE_SUFFIX}"
                    if [ ${IMAGE_SUFFIX} = backup ]; then
                        PATH_TO_DOCKERFILE="source/percona-backup-mongodb"
                    elif [ ${IMAGE_SUFFIX} = operator ]; then
                        PATH_TO_DOCKERFILE="operator-source/build"
                        IMAGE_TAG=${GIT_BRANCH}
                    fi

                    sg docker -c "
                        set -e
                        docker login -u '${USER}' -p '${PASS}'

                        snyk container test --platform=linux/amd64 --exclude-base-image-vulns --file=./\${PATH_TO_DOCKERFILE}/Dockerfile \
                            --severity-threshold=high --json-file-output=\${IMAGE_SUFFIX}-report.json perconalab/\$IMAGE_NAME:\${IMAGE_TAG}
                    "
                """
             }
    } catch (Exception e) {
        echo "Stage failed: ${e.getMessage()}"
        sh """
            exit 1
        """
    } finally {
         echo "Executing post actions..."
         sh """
             IMAGE_SUFFIX=${IMAGE_SUFFIX}
             snyk-to-html -i \${IMAGE_SUFFIX}-report.json -o \${IMAGE_SUFFIX}-report.html
         """
        archiveArtifacts artifacts: '*.html', allowEmptyArchive: true
    }
}

void pushImageToDocker(String IMAGE_SUFFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=${IMAGE_SUFFIX}
            sg docker -c "
                set -e
                docker login -u '${USER}' -p '${PASS}'
                docker push perconalab/percona-server-mongodb-operator:${GIT_PD_BRANCH}-${IMAGE_SUFFIX}
                docker logout
            "
            echo "perconalab/percona-server-mongodb-operator:${GIT_PD_BRANCH}-${IMAGE_SUFFIX}" >> list-of-images.txt
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
    }
    agent {
         label 'docker-x64-min'
    }
    environment {
        PATH = "${WORKSPACE}/node_modules/.bin:$PATH" // Add local npm bin to PATH
        SNYK_TOKEN=credentials('SNYK_ID')
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
                    curl -sL https://static.snyk.io/cli/latest/snyk-linux -o snyk
                    chmod +x snyk
                    sudo mv ./snyk /usr/local/bin/
                    sudo npm install snyk-to-html -g

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
            }
        }

        stage('Build and push PSMDB operator docker image') {
            steps {
                retry(3) {
                    timeout(time: 30, unit: 'MINUTES') {
                        unstash "sourceFILES"
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh '''
                                docker buildx create --use
                                sg docker -c "
                                    docker login -u '${USER}' -p '${PASS}'
                                    pushd source
                                    export IMAGE=perconalab/percona-server-mongodb-operator:${GIT_BRANCH}
                                    DOCKER_DEFAULT_PLATFORM='linux/amd64,linux/arm64' ./e2e-tests/build
                                    popd
                                    docker logout
                                "
                                echo "perconalab/percona-server-mongodb-operator:${GIT_BRANCH}" >> list-of-images.txt
                            '''
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
       stage('Snyk CVEs Check') {
            parallel {
                stage('psmdb operator'){
                    steps {
                        checkImageForDocker('operator')
                    }
                }
                stage('mongod6.0'){
                    steps {
                        checkImageForDocker('mongod6.0')
                    }
                }
                stage('mongod7.0'){
                    steps {
                        checkImageForDocker('mongod7.0')
                    }
                }
                stage('mongod8.0'){
                    steps {
                        checkImageForDocker('mongod8.0')
                    }
                }
                stage('mongod6.0-debug'){
                    steps {
                        checkImageForDocker('mongod6.0-debug')
                    }
                }
                stage('mongod7.0-debug'){
                    steps {
                        checkImageForDocker('mongod7.0-debug')
                    }
                }
                stage('mongod8.0-debug'){
                    steps {
                        checkImageForDocker('mongod8.0-debug')
                    }
                }
                stage('PBM'){
                    steps {
                        checkImageForDocker('backup')
                    }
                }
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
