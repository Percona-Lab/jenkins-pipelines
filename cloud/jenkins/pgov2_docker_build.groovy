void checkImageForDocker(String IMAGE_SUFFIX){
    try {
             withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), string(credentialsId: 'SNYK_ID', variable: 'SNYK_ID')]) {
                sh """
                    IMAGE_TAG=\$(echo ${IMAGE_SUFFIX} | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
                    IMAGE_NAME="percona-postgresql-operator"
                    PATH_TO_DOCKERFILE="/source/build/postgres-operator"

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

pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-postgresql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-postgresql-operator',
            description: 'percona/percona-postgresql-operator repository',
            name: 'GIT_REPO')
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
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_TOKEN')]) {
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    sh """
                    curl -sL https://static.snyk.io/cli/latest/snyk-linux -o snyk
                    chmod +x snyk
                    sudo mv ./snyk /usr/local/bin/

                    sudo npm install snyk-to-html -g
                        export GIT_REPO=\$(echo \${GIT_REPO} | sed "s#github.com#\${GITHUB_TOKEN}@github.com#g")

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
        }
        stage('Build and push PGO docker images') {
            steps {
                retry(3) {
                    timeout(time: 30, unit: 'MINUTES') {
                        unstash "sourceFILES"
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key'),string(credentialsId: 'SYSDIG-API-KEY', variable: 'SYSDIG_API_KEY')]) {
                            sh """
                                cd ./source/
                                TAG_PREFIX=\$(echo $GIT_BRANCH | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
                                IMAGE_NAME="percona-postgresql-operator"
                                sg docker -c "
                                    set -e

                                    if [ ! -d ~/.docker/trust/private ]; then
                                        mkdir -p ~/.docker/trust/private
                                        cp "${docker_key}" ~/.docker/trust/private/
                                    fi

                                    docker login -u '${USER}' -p '${PASS}'
                                    docker buildx create --use

                                    DOCKER_DEFAULT_PLATFORM='linux/amd64,linux/arm64' make build-docker-image

                                    docker logout
                                    echo "perconalab/\$IMAGE_NAME:\${TAG_PREFIX}" >> list-of-images.txt
                               "
                            """
                        }
                    }
                }
            }
        }
        stage('Snyk CVEs Checks') {
            parallel {
                stage('postgres-operator'){
                    steps {
                        checkImageForDocker('\$GIT_BRANCH')
                    }
                }
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
                sudo rm -rf ./source/build
            '''
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PGv2 docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PGv2 docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
