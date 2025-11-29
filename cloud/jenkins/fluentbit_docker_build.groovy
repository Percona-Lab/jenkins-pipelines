void checkImageForDocker(String IMAGE_SUFFIX){
    try {
             withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), string(credentialsId: 'SNYK_ID', variable: 'SNYK_ID')]) {
                sh """
                    IMAGE_TAG=\$(echo ${IMAGE_SUFFIX} | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
                    IMAGE_NAME="fluentbit"
                    PATH_TO_DOCKERFILE="/source/build/fluentbit"

                    sg docker -c "
                        set -e
                        docker login -u '${USER}' -p '${PASS}'

                        snyk container test --platform=linux/amd64 --exclude-base-image-vulns --file=./\${PATH_TO_DOCKERFILE}/Dockerfile \
                            --severity-threshold=high --json-file-output=\${IMAGE_TAG}-report.json perconalab/\$IMAGE_NAME:\${IMAGE_TAG}
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
             IMAGE_TAG=\$(echo ${IMAGE_SUFFIX} | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
             snyk-to-html -i \${IMAGE_TAG}-report.json -o \${IMAGE_TAG}-report.html
         """
        archiveArtifacts artifacts: '*.html', allowEmptyArchive: true
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
void build(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
        sh """
            cd ./source/
            docker login -u '${USER}' -p '${PASS}'
            docker buildx create --use
            docker buildx build --platform linux/amd64,linux/arm64 --progress plain -t perconalab/fluentbit:${GIT_PD_BRANCH}-${IMAGE_PREFIX} --push -f fluentbit/Dockerfile fluentbit
            docker logout
        """
    }
}

void pushImageToDocker(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            sg docker -c "
                if [ ! -d ~/.docker/trust/private ]; then
                    mkdir -p /home/ec2-user/.docker/trust/private
                    cp "${docker_key}" ~/.docker/trust/private/
                fi

                docker login -u '${USER}' -p '${PASS}'
                docker push perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX}
                docker push perconalab/fluentbit:${GIT_PD_BRANCH}-${IMAGE_PREFIX}
                docker logout
            "
        """
    }
}
pipeline {
    parameters {
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
         label 'docker'
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
                """
                stash includes: "cloud/**", name: "cloud"
            }
        }
        stage('Build and push fluentbit docker images') {
            steps {
                sh '''
                    sudo rm -rf cloud
                '''
                unstash "cloud"
                sh """
                   sudo rm -rf source
                   export GIT_REPO=$GIT_PD_REPO
                   export GIT_BRANCH=$GIT_PD_BRANCH
                   ./cloud/local/checkout
                """
                retry(3) {
                    build('logcollector')
                }
            }
        }
        stage('Snyk CVEs Checks') {
            steps {
                checkImageForDocker('\$GIT_BRANCH')
            }
        }
    }
    post {
        always {
            script {
                if (fileExists('./source/list-of-images.txt')) {
                    def summary = generateImageSummary('./source/list-of-images.txt')

                    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
                        text: "<pre>${summary}</pre>"
                    )
                    // Also save as a file if needed
                     writeFile(file: 'image-summary.html', text: summary)
                } else {
                    echo 'No ./source/list-of-images.txt file found - skipping summary generation'
                }
            }
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of fluentbit docker image unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of fluentbit docker image failed. Please check the log ${BUILD_URL}"
        }
    }
}
