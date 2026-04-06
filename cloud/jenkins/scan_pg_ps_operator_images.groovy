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

void generateImageSummary(String pgListPath, String psListPath) {
    def pgImages = fileExists(pgListPath) ? readFile(pgListPath).trim().split("\n").findAll { it } : []
    def psImages = fileExists(psListPath) ? readFile(psListPath).trim().split("\n").findAll { it } : []

    def report = "<h2>Image Summary Report (Check Service recommended)</h2>\n"

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
        string(
            defaultValue: 'https://check.percona.com/versions/v1',
            description: 'Percona Check Service API base (no trailing slash on path segments after v1)',
            name: 'CHECK_SERVICE_BASE')
        string(
            defaultValue: '',
            description: 'Pin PostgreSQL operator version (e.g. 2.9.0). Empty = latest from Check Service.',
            name: 'PG_OPERATOR_VER')
        string(
            defaultValue: '',
            description: 'Pin MySQL (PS) operator version (e.g. 1.0.0). Empty = latest from Check Service.',
            name: 'PS_OPERATOR_VER')
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

                    sudo curl -fsSL https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux64 -o /usr/local/bin/jq && sudo chmod +x /usr/local/bin/jq
                """
            }
        }

        stage('Resolve PG operator images from Check Service') {
            steps {
                sh """
                    set -euo pipefail
                    BASE='${params.CHECK_SERVICE_BASE}'
                    PG_PIN='${params.PG_OPERATOR_VER}'
                    URL="\${BASE}/pg-operator"
                    tmp=\$(mktemp)
                    trap 'rm -f "\$tmp"' EXIT
                    curl -fsS "\$URL" -o "\$tmp"
                    count=\$(jq '.versions | length' "\$tmp")
                    if [ "\$count" -eq 0 ]; then
                        echo "ERROR: Check Service returned no versions (GET \$URL)." >&2
                        exit 1
                    fi
                    if [ -n "\$PG_PIN" ]; then
                        ver="\$PG_PIN"
                    else
                        ver=\$(jq -r '.versions[].operator' "\$tmp" | sort -V | tail -n1)
                    fi
                    if [ -z "\$ver" ]; then
                        echo "ERROR: empty operator version (GET \$URL)." >&2
                        exit 1
                    fi
                    echo "product=pg-operator operator_version=\${ver}" >&2
                    jq -r --arg v "\$ver" '
                        .versions[]
                        | select(.operator == \$v)
                        | .matrix
                        | .. | objects
                        | select(has("imagePath") and .status == "recommended")
                        | .imagePath
                    ' "\$tmp" | sort -u > list-of-pg-images.txt
                    echo "--- PG images to scan (\$(wc -l < list-of-pg-images.txt)) ---" >&2
                    cat list-of-pg-images.txt >&2
                """
            }
        }

        stage('Resolve PS operator images from Check Service') {
            steps {
                sh """
                    set -euo pipefail
                    BASE='${params.CHECK_SERVICE_BASE}'
                    PS_PIN='${params.PS_OPERATOR_VER}'
                    URL="\${BASE}/ps-operator"
                    tmp=\$(mktemp)
                    trap 'rm -f "\$tmp"' EXIT
                    curl -fsS "\$URL" -o "\$tmp"
                    count=\$(jq '.versions | length' "\$tmp")
                    if [ "\$count" -eq 0 ]; then
                        echo "ERROR: Check Service returned no versions (GET \$URL)." >&2
                        exit 1
                    fi
                    if [ -n "\$PS_PIN" ]; then
                        ver="\$PS_PIN"
                    else
                        ver=\$(jq -r '.versions[].operator' "\$tmp" | sort -V | tail -n1)
                    fi
                    if [ -z "\$ver" ]; then
                        echo "ERROR: empty operator version (GET \$URL)." >&2
                        exit 1
                    fi
                    echo "product=ps-operator operator_version=\${ver}" >&2
                    jq -r --arg v "\$ver" '
                        .versions[]
                        | select(.operator == \$v)
                        | .matrix
                        | .. | objects
                        | select(has("imagePath") and .status == "recommended")
                        | .imagePath
                    ' "\$tmp" | sort -u > list-of-ps-images.txt
                    echo "--- PS images to scan (\$(wc -l < list-of-ps-images.txt)) ---" >&2
                    cat list-of-ps-images.txt >&2
                """
            }
        }

        stage('Trivy scan PG operator images') {
            steps {
                checkImagesForDocker('list-of-pg-images.txt', 'PG')
            }
        }

        stage('Trivy scan PS operator images') {
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
                // slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Trivy scan for *PG + PS operator* images (Check Service recommended) unstable.${trivySummary} ${BUILD_URL}"
            }
        }
        failure {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-*.xml')
                // slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Trivy scan for *PG + PS operator* images (Check Service recommended) failed.${trivySummary} ${BUILD_URL}"
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
