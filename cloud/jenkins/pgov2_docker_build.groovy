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
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_TOKEN')]) {
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    sh """
                        wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                        sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                        rm -f trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz

                        if [ ! -f /tmp/junit.tpl ]; then
                            wget --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                        fi

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

                                    echo "\$PASS" | docker login -u "\$USER" --password-stdin
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
        stage('Check PGv2 docker images') {
            steps {
                checkImagesForDocker('./source/list-of-images.txt')
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
        }
        unstable {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-*.xml')
                slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of *PGv2* operator docker images unstable.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        failure {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-*.xml')
                slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of *PGv2* operator docker images failed.${trivySummary} Please check the log ${BUILD_URL}"
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
