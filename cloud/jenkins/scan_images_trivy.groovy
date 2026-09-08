def operatorMap = [
    'percona-server-mongodb-operator': 'psmdb',
    'percona-xtradb-cluster-operator': 'pxc',
    'percona-server-mysql-operator': 'ps',
    'percona-postgresql-operator': 'pg'
]

List<String> parseImages(String rawInput) {
    def images = []
    rawInput.split("\n").each { line ->
        line = line.trim()
        if (!line || line.startsWith('#')) {
            return
        }
        // accept both "KEY=image:tag" and plain "image:tag" lines
        def image = line
        if (line.contains('=')) {
            def parts = line.split('=', 2)
            if (!parts[0].startsWith('IMAGE_')) {
                return
            }
            image = parts[1].trim()
        }
        if (image) {
            images << image
        }
    }
    return images
}

void scanImage(String image) {
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE='${image}'
            IMAGE_ID=\$(echo "\$IMAGE" | sed 's#[/:]#-#g')
            TrivyLog="$WORKSPACE/trivy-\${IMAGE_ID}.xml"

            sg docker -c "
                echo "\$PASS" | docker login -u "\$USER" --password-stdin
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \$TrivyLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL \$IMAGE
                docker logout
            "

            perl -pi -e 's/<testcase classname="/<testcase classname="'"\$IMAGE_ID"' :: /g; s/<testcase name="/<testcase name="'"\$IMAGE_ID"' :: /g' "\$TrivyLog"
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
    def rows = []

    findFiles(glob: reportGlob).each { file ->
        def report = readFile(file.path)
        int imageHighCount = report.split('\\[HIGH\\]', -1).size() - 1
        int imageCriticalCount = report.split('\\[CRITICAL\\]', -1).size() - 1
        String imageName = file.name.replaceFirst('^trivy-', '').replaceFirst('\\.xml$', '')

        highCount += imageHighCount
        criticalCount += imageCriticalCount
        rows << [name: imageName, critical: imageCriticalCount, high: imageHighCount]
    }

    if (highCount == 0 && criticalCount == 0) {
        return ''
    }

    int nameWidth = 'IMAGE'.length()
    rows.each { r ->
        if (r.name.length() > nameWidth) {
            nameWidth = r.name.length()
        }
    }
    String header = "${'IMAGE'.padRight(nameWidth)}  ${'CRITICAL'.padLeft(8)}  ${'HIGH'.padLeft(4)}"
    String table = header + '\n'
    rows.each { r ->
        table += "${r.name.padRight(nameWidth)}  ${r.critical.toString().padLeft(8)}  ${r.high.toString().padLeft(4)}\n"
    }

    return "\n*CVEs found:*\n```\n${table}```\n"
}

pipeline {
    parameters {
        choice(
            name: 'OPERATOR',
            choices: ['percona-server-mysql-operator', 'percona-server-mongodb-operator', 'percona-xtradb-cluster-operator', 'percona-postgresql-operator'],
            description: 'Operator to use when IMAGES is empty. The latest image versions will be detected automatically.'
        )
        text(
            defaultValue: '',
            description: '''List of images to scan, one per line. Accepts "KEY=image:tag" or plain "image:tag" lines, e.g.:
IMAGE_OPERATOR=percona/percona-server-mysql-operator:1.2.0
IMAGE_MYSQL84=percona/percona-server:8.4.10-10.1''',
            name: 'IMAGES')
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
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    TRIVY_CHECKSUM="1816b632dfe529869c740c0913e36bd1629cb7688bd5634f4a858c1d57c88b75"
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    echo "\${TRIVY_CHECKSUM}  trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz" | sha256sum -c -
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    rm -f trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz

                    if [ ! -f /tmp/junit.tpl ]; then
                        wget --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    fi

                    if ! command -v uv >/dev/null 2>&1; then
                        curl -LsSf https://astral.sh/uv/install.sh | sh
                    fi
                """
            }
        }

        stage('Scan images') {
            steps {
                script {
                    def images = parseImages(params.IMAGES)
                    if (images.isEmpty()) {
                        sh """
                            export PATH="\$HOME/.local/bin:\$PATH"
                            OPERATOR_REPO='percona/${params.OPERATOR}'
                            OPERATOR_KEY='${operatorMap[params.OPERATOR]}'
                            OPERATOR_VERSION=\$(uv run --with requests --with packaging python3 - "\$OPERATOR_REPO" <<'PY'
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("generate_release_images_file", "cloud/scripts/generate_release_images_file.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

version = module.fetch_dockerhub_tag(sys.argv[1])
if not version:
    raise SystemExit(f"Could not find latest tag for {sys.argv[1]}")
print(version)
PY
                            )
                            uv run --with requests --with packaging cloud/scripts/generate_release_images_file.py "\$OPERATOR_KEY" "\$OPERATOR_VERSION"
                        """
                        archiveArtifacts artifacts: 'release_versions.txt', allowEmptyArchive: false, fingerprint: true
                        images = parseImages(readFile('release_versions.txt'))
                    }
                    if (images.isEmpty()) {
                        error('No images found in IMAGES or generated release_versions.txt')
                    }
                    writeFile(file: 'list-of-images.txt', text: images.join("\n") + "\n")
                    images.each { image ->
                        echo "Scanning ${image}"
                        scanImage(image)
                    }
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "trivy-*.xml"
                    archiveArtifacts artifacts: "trivy-*.xml", allowEmptyArchive: true
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
                writeFile(file: 'image-summary.html', text: summary)
            }
        }
        success {
            script {
                def trivySummary = getTrivyCveSummary('trivy-*.xml')
                if (trivySummary) {
                    slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Image vulnerability scan finished with findings.${trivySummary} Please check the log ${BUILD_URL}"
                } else {
                    slackSend channel: '#cloud-dev-ci', color: '#00FF00', message: "Image vulnerability scan finished, no HIGH/CRITICAL CVEs found. ${BUILD_URL}"
                }
            }
        }
        unstable {
            script {
                def trivySummary = getTrivyCveSummary('trivy-*.xml')
                slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Image vulnerability scan unstable.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        failure {
            script {
                def trivySummary = getTrivyCveSummary('trivy-*.xml')
                slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Image vulnerability scan failed.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        cleanup {
            sh '''
                docker logout || true
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
    }
}
