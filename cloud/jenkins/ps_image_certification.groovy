// =============================================================================
// PS Pillar Image Certification (SMOKE TEST - PS-10640)
// =============================================================================
// This pipeline is a stripped-down variant of pso_image_certification.groovy
// for certifying the standalone Percona Server 8.0 pillar image (no operator,
// no release_versions plumbing, no operator git checkout).
//
// STATUS: SMOKE TEST ONLY. NOT FOR PRODUCTION USE.
//
// Before any non-DRY_RUN execution against a real Red Hat ISV project:
//   - Replace PYXIS_PROJECT_ID default with the actual PS pillar PID
//   - Replace REGISTRY_CREDS_ID default with the matching Jenkins credential
//     (the per-PID quay.io robot account from Red Hat Connect)
//
// Tracking: https://perconadev.atlassian.net/browse/PS-10640
// =============================================================================

def certificationTests = []
def certification

pipeline {
    agent {
        label 'docker-x64-min'
    }

    parameters {
        string(
            name: 'IMAGE',
            defaultValue: 'percona/percona-server:8.0.45-36',
            description: 'Source image to certify (Docker Hub coordinates)'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: '8.0.45-36',
            description: 'Tag to publish under in the Red Hat ISV registry'
        )

        choice(
            name: 'PLATFORM',
            choices: ['multiplatform', 'amd64', 'arm64'],
            description: 'Target platform'
        )

        string(
            name: 'PYXIS_PROJECT_ID',
            defaultValue: 'REPLACE_WITH_NEW_PS_PILLAR_PID',
            description: 'Pyxis project ID for the PS pillar image (placeholder until provisioned)'
        )
        string(
            name: 'REGISTRY_CREDS_ID',
            defaultValue: 'PS_CONTAINERS_REGISTRY',
            description: 'Jenkins credential ID for the per-PID quay.io robot account (placeholder)'
        )

        booleanParam(
            name: 'DRY_RUN',
            defaultValue: true,
            description: 'Skip --submit so this run does not dirty the Pyxis project (smoke-test safe)'
        )
    }

    stages {
        stage('Certify Image') {
            steps {
                script {
                    certification = load "cloud/common/imageCertification.groovy"

                    currentBuild.displayName = "${params.IMAGE_TAG} (${params.PLATFORM})"

                    echo "Image: ${params.IMAGE}"
                    echo "Tag: ${params.IMAGE_TAG}"
                    echo "Platform: ${params.PLATFORM}"
                    echo "Pyxis project ID: ${params.PYXIS_PROJECT_ID}"
                    echo "DRY_RUN: ${params.DRY_RUN}"

                    if (params.PYXIS_PROJECT_ID == 'REPLACE_WITH_NEW_PS_PILLAR_PID' && !params.DRY_RUN) {
                        error("PYXIS_PROJECT_ID is still the placeholder. Set a real PID or run with DRY_RUN=true.")
                    }

                    def target = [
                        src: params.IMAGE,
                        dest: "quay.io/redhat-isv-containers/${params.PYXIS_PROJECT_ID}:${params.IMAGE_TAG}",
                        credentials: params.REGISTRY_CREDS_ID
                    ]

                    def startedAt = System.currentTimeMillis()
                    def status

                    withCredentials([
                        string(credentialsId: 'PYXIS_TOKEN', variable: 'PYXIS_TOKEN'),
                        usernamePassword(
                            credentialsId: target.credentials,
                            usernameVariable: 'REGISTRY_USER',
                            passwordVariable: 'REGISTRY_KEY'
                        )
                    ]) {
                        def component = env.REGISTRY_USER.replaceAll(/^.*\+/, '').replaceAll(/-robot$/, '')
                        def noSubmitFlag = params.DRY_RUN ? '--no-submit' : ''

                        status = sh(
                            returnStatus: true,
                            script: """
                            set -e
                            python3 cloud/scripts/certify_images.py \
                              --image ${target.src} \
                              --dest_image ${target.dest} \
                              --component ${component} \
                              --platform ${params.PLATFORM} \
                              ${noSubmitFlag}
                        """
                        )
                    }

                    certificationTests.add([
                        name: 'IMAGE_PS',
                        cluster: params.PLATFORM,
                        result: status == 0 ? 'passed' : 'failure',
                        time: (System.currentTimeMillis() - startedAt) / 1000,
                    ])

                    if (status != 0) {
                        currentBuild.result = 'UNSTABLE'
                        echo "Certification failed for PS pillar image: ${params.IMAGE}"
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                certification = certification ?: load("cloud/common/imageCertification.groovy")

                certification.publishResults()
                certification.sendSlack(certificationTests, '-', params.PLATFORM, params.IMAGE_TAG)
            }
        }
    }
}
