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

void pushImageToDocker(String IMAGE_SUFFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=${IMAGE_SUFFIX}
            sg docker -c "
                set -e
                echo "\$PASS" | docker login -u "\$USER" --password-stdin
                docker push perconalab/percona-server-mongodb-operator:${GIT_PD_BRANCH}-${IMAGE_SUFFIX}
                docker logout
            "
            echo "perconalab/percona-server-mongodb-operator:${GIT_PD_BRANCH}-${IMAGE_SUFFIX}" >> list-of-images.txt
        """
    }
}
void checkImagesForDocker(String imagesListPath){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGES_LIST_PATH='${imagesListPath}'

            while IFS= read -r IMAGE || [ -n "\$IMAGE" ]; do
                [ -z "\$IMAGE" ] && continue
                IMAGE_ID=\$(echo "\$IMAGE" | sed 's#[/:]#-#g')
                TrivyLog="$WORKSPACE/trivy-hight-\${IMAGE_ID}.xml"

                sg docker -c "
                    echo "\$PASS" | docker login -u "\$USER" --password-stdin
                    /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \$TrivyLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL \$IMAGE
                    docker logout
                "
            done < "\$IMAGES_LIST_PATH"

            for REPORT in trivy-hight-*.xml; do
                [ -f "\$REPORT" ] || continue
                IMAGE_ID=\$(basename "\$REPORT" .xml | sed 's/^trivy-hight-//')
                perl -pi -e 's/<testcase classname="/<testcase classname="'"\$IMAGE_ID"' :: /g; s/<testcase name="/<testcase name="'"\$IMAGE_ID"' :: /g' "\$REPORT"
            done
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

String getTrivyCveSummary(String reportGlob) {
    int highCount = 0
    int criticalCount = 0
    String perImageSummary = ''

    findFiles(glob: reportGlob).each { file ->
        def report = readFile(file.path)
        int imageHighCount = report.split('\\[HIGH\\]', -1).size() - 1
        int imageCriticalCount = report.split('\\[CRITICAL\\]', -1).size() - 1
        String imageName = file.name.replaceFirst('^trivy-hight-', '').replaceFirst('\\.xml$', '')

        highCount += imageHighCount
        criticalCount += imageCriticalCount
        perImageSummary += "*${imageName}*\n*CRITICAL* `${imageCriticalCount}` *HIGH* `${imageHighCount}`\n"
    }

    if (highCount == 0 && criticalCount == 0) {
        return ''
    }

    return "\n*CVEs found:*\n${perImageSummary}\n"
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
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
        TRIVY_VERSION = '0.69.3'
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
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    rm -f trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz

                    if [ ! -f /tmp/junit.tpl ]; then
                        wget --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    fi

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
                                    echo "\$PASS" | docker login -u "\$USER" --password-stdin
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
        stage('Check PSMDB docker images') {
            steps {
                checkImagesForDocker('list-of-images.txt')
            }
            post {
                always {
                    junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "trivy-hight-*.xml"
                    archiveArtifacts artifacts: "trivy-hight-*.xml", allowEmptyArchive: true
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
        }
        unstable {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-*.xml')
                slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of *PSMDB* operator docker images unstable.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        failure {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-*.xml')
                slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of *PSMDB* operator docker images failed.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        cleanup {
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./source/build
            '''
            deleteDir()
        }
    }
}
