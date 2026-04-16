void checkImagesForDocker(String imagesListPath, String operatorFamily) {
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGES_LIST_PATH='${imagesListPath}'
            OP_FAMILY='${operatorFamily}'

            while IFS= read -r IMAGE || [ -n "\$IMAGE" ]; do
                [ -z "\$IMAGE" ] && continue
                IMAGE_ID=\$(echo "\$IMAGE" | sed 's#[/:]#-#g')
                TrivyLog="$WORKSPACE/trivy-hight-\${OP_FAMILY}-\${IMAGE_ID}.xml"

                sg docker -c "
                    echo "\$PASS" | docker login -u "\$USER" --password-stdin
                    /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \$TrivyLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL \$IMAGE
                    docker logout
                "
            done < "\$IMAGES_LIST_PATH"

            for REPORT in "$WORKSPACE"/trivy-hight-\${OP_FAMILY}-*.xml; do
                [ -f "\$REPORT" ] || continue
                IMAGE_ID=\$(basename "\$REPORT" .xml | sed "s/^trivy-hight-\${OP_FAMILY}-//")
                perl -pi -e 's/<testcase classname="/<testcase classname="'"\$OP_FAMILY"' :: '"\$IMAGE_ID"' :: /g; s/<testcase name="/<testcase name="'"\$OP_FAMILY"' :: '"\$IMAGE_ID"' :: /g' "\$REPORT"
            done
        """
    }
}

void resolveImagesFromReleaseVersions(
    String operatorName,
    String repoGitUrl,
    String rawRepoUrl,
    String outputListPath,
    String customListPath = ''
) {
    withEnv([
        "OPERATOR_NAME=${operatorName}",
        "REPO_GIT_URL=${repoGitUrl}",
        "RAW_REPO_URL=${rawRepoUrl}",
        "OUTPUT_LIST_PATH=${outputListPath}",
        "CUSTOM_LIST_PATH=${customListPath}"
    ]) {
        sh '''
            set -euo pipefail

            RELEASE_FILE=$(mktemp)
            trap 'rm -f "$RELEASE_FILE"' EXIT

            if [ -n "$CUSTOM_LIST_PATH" ] && [ -s "$CUSTOM_LIST_PATH" ]; then
                cp "$CUSTOM_LIST_PATH" "$RELEASE_FILE"
                echo "Using custom image list for ${OPERATOR_NAME}: $CUSTOM_LIST_PATH" >&2
            else
                LATEST_TAG=$(git ls-remote --refs --tags "$REPO_GIT_URL" "v*" \
                    | awk -F/ '$3 ~ /^v[0-9]+\\.[0-9]+\\.[0-9]+$/ { print $3 }' \
                    | sort -V \
                    | tail -n1)

                if [ -z "$LATEST_TAG" ]; then
                    echo "ERROR: failed to resolve latest semantic tag for ${OPERATOR_NAME} from $REPO_GIT_URL" >&2
                    exit 1
                fi

                RELEASE_URL="${RAW_REPO_URL}/refs/tags/${LATEST_TAG}/e2e-tests/release_versions"
                echo "Using ${OPERATOR_NAME} tag ${LATEST_TAG}" >&2
                echo "Fetching ${RELEASE_URL}" >&2
                curl -fsS "$RELEASE_URL" -o "$RELEASE_FILE"
            fi

            awk -F= '
                /^[[:space:]]*IMAGE_[A-Za-z0-9_]+[[:space:]]*=/ {
                    value=$2
                    sub(/^[[:space:]]+/, "", value)
                    sub(/[[:space:]]+$/, "", value)
                    gsub(/"/, "", value)
                    if (value ~ /.+\\/.+:.+/) {
                        print value
                    }
                }
            ' "$RELEASE_FILE" | sort -u > "$OUTPUT_LIST_PATH"

            if [ ! -s "$OUTPUT_LIST_PATH" ]; then
                echo "ERROR: no IMAGE_* entries resolved for ${OPERATOR_NAME}" >&2
                exit 1
            fi

            echo "--- ${OPERATOR_NAME} images to scan ($(wc -l < "$OUTPUT_LIST_PATH")) ---" >&2
            cat "$OUTPUT_LIST_PATH" >&2
        '''
    }
}

void generateImageSummary(String pgListPath, String psListPath) {
    def pgImages = fileExists(pgListPath) ? readFile(pgListPath).trim().split("\n").findAll { it } : []
    def psImages = fileExists(psListPath) ? readFile(psListPath).trim().split("\n").findAll { it } : []

    def report = "<h2>Image Summary Report</h2>\n"

    report += "<h3>PostgreSQL operator (${pgImages.size()} images)</h3>\n<ul>\n"
    pgImages.each { image -> report += "<li>${image}</li>\n" }
    report += "</ul>\n"

    report += "<h3>MySQL (PS) operator (${psImages.size()} images)</h3>\n<ul>\n"
    psImages.each { image -> report += "<li>${image}</li>\n" }
    report += "</ul>\n"

    report += "<p><strong>Total:</strong> ${pgImages.size() + psImages.size()}</p>\n"
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
        String stripped = file.name.replaceFirst('^trivy-hight-', '').replaceFirst('\\.xml$', '')
        String imageName
        if (stripped.startsWith('PG-')) {
            imageName = "[PostgreSQL operator] ${stripped.substring(3)}"
        } else if (stripped.startsWith('PS-')) {
            imageName = "[MySQL PS operator] ${stripped.substring(3)}"
        } else {
            imageName = stripped
        }

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
        booleanParam(
            defaultValue: true,
            description: 'Enable PostgreSQL operator image resolution and scan.',
            name: 'SCAN_PG_IMAGES')
        booleanParam(
            defaultValue: true,
            description: 'Enable MySQL (PS) operator image resolution and scan.',
            name: 'SCAN_PS_IMAGES')
        text(
            defaultValue: '',
            description: 'Optional custom PG image list in release_versions format (IMAGE_*=repo/image:tag). If set, only these images are checked.',
            name: 'PG_CUSTOM_IMAGE_LIST')
        text(
            defaultValue: '',
            description: 'Optional custom PS image list in release_versions format (IMAGE_*=repo/image:tag). If set, only these images are checked.',
            name: 'PS_CUSTOM_IMAGE_LIST')
    }
    agent {
        label 'docker-x64-min'
    }
    environment {
        TRIVY_VERSION = '0.69.3'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    set -euo pipefail
                    TRIVY_CHECKSUM="1816b632dfe529869c740c0913e36bd1629cb7688bd5634f4a858c1d57c88b75"
                    wget -q https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    echo "\${TRIVY_CHECKSUM}  trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz" | sha256sum -c -
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    rm -f trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz

                    if [ ! -f /tmp/junit.tpl ]; then
                        wget -q --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    fi

                """
            }
        }

        stage('Resolve PG operator images') {
            when {
                expression { return params.SCAN_PG_IMAGES }
            }
            steps {
                script {
                    String customPath = ''
                    if (params.PG_CUSTOM_IMAGE_LIST?.trim()) {
                        customPath = 'pg-custom-release_versions.txt'
                        writeFile file: customPath, text: params.PG_CUSTOM_IMAGE_LIST.trim() + '\n'
                    }
                    resolveImagesFromReleaseVersions(
                        'PG operator',
                        'https://github.com/percona/percona-postgresql-operator.git',
                        'https://raw.githubusercontent.com/percona/percona-postgresql-operator',
                        'list-of-pg-images.txt',
                        customPath
                    )
                }
            }
        }

        stage('Resolve PS operator images') {
            when {
                expression { return params.SCAN_PS_IMAGES }
            }
            steps {
                script {
                    String customPath = ''
                    if (params.PS_CUSTOM_IMAGE_LIST?.trim()) {
                        customPath = 'ps-custom-release_versions.txt'
                        writeFile file: customPath, text: params.PS_CUSTOM_IMAGE_LIST.trim() + '\n'
                    }
                    resolveImagesFromReleaseVersions(
                        'PS operator',
                        'https://github.com/percona/percona-server-mysql-operator.git',
                        'https://raw.githubusercontent.com/percona/percona-server-mysql-operator',
                        'list-of-ps-images.txt',
                        customPath
                    )
                }
            }
        }

        stage('Trivy scan PG operator images') {
            when {
                expression { return params.SCAN_PG_IMAGES }
            }
            steps {
                checkImagesForDocker('list-of-pg-images.txt', 'PG')
            }
        }

        stage('Trivy scan PS operator images') {
            when {
                expression { return params.SCAN_PS_IMAGES }
            }
            steps {
                checkImagesForDocker('list-of-ps-images.txt', 'PS')
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, skipPublishingChecks: true, testResults: 'trivy-hight-*.xml'
            archiveArtifacts artifacts: 'trivy-hight-*.xml', allowEmptyArchive: true
            script {
                if (fileExists('list-of-pg-images.txt') || fileExists('list-of-ps-images.txt')) {
                    def summary = generateImageSummary('list-of-pg-images.txt', 'list-of-ps-images.txt')
                    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
                        text: "<pre>${summary}</pre>"
                    )
                    writeFile(file: 'image-summary.html', text: summary)
                    archiveArtifacts artifacts: 'image-summary.html,list-of-pg-images.txt,list-of-ps-images.txt', allowEmptyArchive: true
                }
            }
        }
        unstable {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-*.xml')
                // slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Trivy scan for *PG + PS operator* images unstable.${trivySummary} ${BUILD_URL}"
            }
        }
        failure {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-*.xml')
                // slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Trivy scan for *PG + PS operator* images failed.${trivySummary} ${BUILD_URL}"
            }
        }
        cleanup {
            sh '''
                sudo docker rmi -f $(sudo docker images -q) || true
            '''
            deleteDir()
        }
    }
}
