Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
This job helps run an image scan with Trivy
'''

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void scanImage(String component, String image, boolean requireMultiArch) {
    sh """
        manifest=\$(docker manifest inspect ${image}) || {
            echo "ERROR: cannot read the manifest of ${image}"
            exit 1
        }

        platforms=\$(echo "\${manifest}" \
            | jq -r '(.manifests // []) | .[] | select(.platform.os == "linux") | .platform.architecture' \
            | grep -Ex 'amd64|arm64' | sort -u)

        if [ -z "\${platforms}" ]; then
            echo "ERROR: ${image} has no linux/amd64 or linux/arm64 image to scan"
            exit 1
        fi

        if [ "${requireMultiArch}" = "true" ]; then
            for required in amd64 arm64; do
                echo "\${platforms}" | grep -qx "\${required}" || {
                    echo "ERROR: ${image} has no \${required} image"
                    exit 1
                }
            done
        fi

        for platform in \${platforms}; do
            trivy image --platform linux/\${platform} --severity HIGH,CRITICAL --format table -o trivy-${component}-report-\${platform}.txt ${image}
            trivy image --platform linux/\${platform} --severity HIGH,CRITICAL --format template --template "@html.tpl" -o trivy-${component}-report-\${platform}.html ${image}
        done
    """
    archiveArtifacts artifacts: "trivy-${component}-report-*.*"
}

pipeline {
    agent {
        label params.USE_ONDEMAND ? 'agent-amd64-ondemand' : 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-client:3-dev-latest',
            description: 'PMM Client image with tag to scan',
            name: 'PMM_CLIENT_IMAGE')
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server image with tag to scan',
            name: 'PMM_SERVER_IMAGE')
        booleanParam(
            defaultValue: false,
            description: 'Use on-demand instances instead of spot (for RC/Release builds)',
            name: 'USE_ONDEMAND'
        )
        booleanParam(
            defaultValue: false,
            description: 'Fail unless both amd64 and arm64 are present in the scanned images',
            name: 'REQUIRE_MULTIARCH'
        )
    }
    stages {
        stage('Install Trivy') {
            steps {
                script {
                    installTrivy(htmlTpl: true)
                }
            }
        }
        stage('Scan PMM Server') {
            steps {
                script {
                    scanImage('server', params.PMM_SERVER_IMAGE, params.REQUIRE_MULTIARCH)
                }
            }
        }
        stage('Scan PMM Client') {
            steps {
                script {
                    scanImage('client', params.PMM_CLIENT_IMAGE, params.REQUIRE_MULTIARCH)
                }
            }
        }
    }
}
