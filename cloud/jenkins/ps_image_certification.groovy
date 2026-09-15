// =============================================================================
// PS Pillar Image Certification (SMOKE TEST - PS-10640)
// =============================================================================
// This pipeline is a stripped-down variant of pso_image_certification.groovy
// for certifying the standalone Percona Server pillar image (no operator,
// no release_versions plumbing, no operator git checkout).
//
// STATUS: SMOKE TEST ONLY. NOT FOR PRODUCTION USE.
//
// DRY_RUN=true (default): runs preflight against the SOURCE image only.
//   No registry login, no image push to quay.io, no Pyxis API contact,
//   no credentials required. Safe to run without any RHPC setup.
//
// DRY_RUN=false: full cert path. Requires:
//   - PYXIS_PROJECT_ID set to a real Pyxis certification project id
//   - REGISTRY_CREDS_ID set to a Jenkins credential containing the per-PID
//     quay.io robot account from Red Hat Connect
//   - PYXIS_TOKEN credential present on the Jenkins instance
//
// Tracking: https://perconadev.atlassian.net/browse/PS-10640
// =============================================================================

def certificationTests = []
def certification
def PLACEHOLDER_PID = 'REPLACE_WITH_NEW_PS_PILLAR_PID'
def PLACEHOLDER_CREDS = 'REPLACE_WITH_REAL_REGISTRY_CREDS_ID'

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
            description: 'Tag to publish under in the Red Hat ISV registry (used only when DRY_RUN=false)'
        )

        choice(
            name: 'PLATFORM',
            choices: ['multiplatform', 'amd64', 'arm64'],
            description: 'Target platform'
        )

        string(
            name: 'PYXIS_PROJECT_ID',
            defaultValue: 'REPLACE_WITH_NEW_PS_PILLAR_PID',
            description: 'Pyxis project ID for the PS pillar image. Required when DRY_RUN=false.'
        )
        string(
            name: 'REGISTRY_CREDS_ID',
            defaultValue: 'REPLACE_WITH_REAL_REGISTRY_CREDS_ID',
            description: 'Jenkins credential ID for the per-PID quay.io robot account. Required when DRY_RUN=false.'
        )

        booleanParam(
            name: 'DRY_RUN',
            defaultValue: true,
            description: 'When true: run preflight against the source image only, no registry push, no Pyxis submit, no credentials required. When false: full cert path.'
        )
    }

    stages {
        stage('Certify Image') {
            steps {
                script {
                    certification = load "cloud/common/imageCertification.groovy"

                    currentBuild.displayName = "${params.IMAGE_TAG} (${params.PLATFORM})${params.DRY_RUN ? ' DRY' : ''}"

                    def pid = (params.PYXIS_PROJECT_ID ?: '').trim()
                    def credsId = (params.REGISTRY_CREDS_ID ?: '').trim()

                    echo "Image: ${params.IMAGE}"
                    echo "Platform: ${params.PLATFORM}"
                    echo "DRY_RUN: ${params.DRY_RUN}"

                    if (!params.DRY_RUN) {
                        if (!pid || pid == 'REPLACE_WITH_NEW_PS_PILLAR_PID') {
                            error("PYXIS_PROJECT_ID must be set to a real PID when DRY_RUN=false (currently: '${pid}').")
                        }
                        if (!credsId || credsId == 'REPLACE_WITH_REAL_REGISTRY_CREDS_ID') {
                            error("REGISTRY_CREDS_ID must be set to a real Jenkins credential id when DRY_RUN=false (currently: '${credsId}').")
                        }
                        echo "Pyxis project ID: ${pid}"
                        echo "Registry creds id: ${credsId}"
                    }

                    def startedAt = System.currentTimeMillis()
                    def status

                    if (params.DRY_RUN) {
                        status = sh(
                            returnStatus: true,
                            script: """
                            set -e
                            python3 cloud/scripts/certify_images.py \\
                              --image ${params.IMAGE} \\
                              --platform ${params.PLATFORM} \\
                              --no-submit
                        """
                        )
                    } else {
                        def destImage = "quay.io/redhat-isv-containers/${pid}:${params.IMAGE_TAG}"

                        withCredentials([
                            string(credentialsId: 'PYXIS_TOKEN', variable: 'PYXIS_TOKEN'),
                            usernamePassword(
                                credentialsId: credsId,
                                usernameVariable: 'REGISTRY_USER',
                                passwordVariable: 'REGISTRY_KEY'
                            )
                        ]) {
                            def component = env.REGISTRY_USER.replaceAll(/^.*\+/, '').replaceAll(/-robot$/, '')

                            status = sh(
                                returnStatus: true,
                                script: """
                                set -e
                                python3 cloud/scripts/certify_images.py \\
                                  --image ${params.IMAGE} \\
                                  --dest_image ${destImage} \\
                                  --component ${component} \\
                                  --platform ${params.PLATFORM}
                            """
                            )
                        }
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
