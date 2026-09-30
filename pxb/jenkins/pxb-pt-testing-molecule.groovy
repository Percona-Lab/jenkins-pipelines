
    library changelog: false, identifier: "lib@check-pxb-sbom", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
    ])


def pxb80PackageTesting() {
    return [
        'debian-11',
        'debian-11-arm',
        'debian-12',
        'debian-12-arm',
        'oracle-8',
        'oracle-9',
        'rhel-8',
        'rhel-9',
        'rhel-8-arm',
        'rhel-9-arm',
        'rocky-8',
        'rocky-8-arm',
        'rocky-9',
        'rocky-9-arm',
        'ubuntu-jammy',
        'ubuntu-jammy-arm',
        'ubuntu-noble',
        'ubuntu-noble-arm',
        'al-2023',
        'al-2023-arm'
    ]
}

def pxb84PackageTesting() {
    return [
        'debian-12',
        'debian-12-arm',
        'debian-13',
        'debian-13-arm',
        'oracle-8',
        'oracle-9',
        'rhel-8',
        'rhel-9',
        'rhel-10',
        'rhel-8-arm',
        'rhel-9-arm',
        'rhel-10-arm',
        'rocky-8',
        'rocky-8-arm',
        'rocky-9',
        'rocky-9-arm',
        'ubuntu-jammy',
        'ubuntu-jammy-arm',
        'ubuntu-noble',
        'ubuntu-noble-arm',
        'ubuntu-resolute',
        'ubuntu-resolute-arm',
        'al-2023',
        'al-2023-arm'
    ]
}

def pxb97PackageTesting() {
    return [
        'debian-12',
        'debian-12-arm',
        'debian-13',
        'debian-13-arm',
        'oracle-8',
        'oracle-9',
        'rhel-8',
        'rhel-9',
        'rhel-10',
        'rhel-8-arm',
        'rhel-9-arm',
        'rhel-10-arm',
        'rocky-8',
        'rocky-8-arm',
        'rocky-9',
        'rocky-9-arm',
        'ubuntu-jammy',
        'ubuntu-jammy-arm',
        'ubuntu-noble',
        'ubuntu-noble-arm',
        'ubuntu-resolute',
        'ubuntu-resolute-arm',
        'al-2023',
        'al-2023-arm'
    ]
}

def pxbInnovationPackageTesting() {
    return [
        'debian-12',
        'debian-12-arm',
        'debian-13',
        'debian-13-arm',
        'oracle-8',
        'oracle-9',
        'rhel-8',
        'rhel-9',
        'rhel-10',
        'rhel-8-arm',
        'rhel-9-arm',
        'rhel-10-arm',
        'rocky-8',
        'rocky-8-arm',
        'rocky-9',
        'rocky-9-arm',
        'ubuntu-jammy',
        'ubuntu-jammy-arm',
        'ubuntu-noble',
        'ubuntu-noble-arm',
        'ubuntu-resolute',
        'ubuntu-resolute-arm',
        'al-2023',
        'al-2023-arm'
    ]
}

List pxbAllOS = (pxb80PackageTesting() + pxb84PackageTesting() + pxb97PackageTesting() + pxbInnovationPackageTesting()).unique()

def moleculeParallelTestPXBALL(allOS, operatingSystems, moleculeDir) {
    def tests = [:]
    allOS.each { os ->
        tests["${os}"] = {
            stage("${os}") {
                if (operatingSystems.contains(os)) {
                    sh """
                        . virtenv/bin/activate
                        cd ${moleculeDir}
                        molecule test -s ${os}
                    """
                } else {
                    echo "Skipping ${os} as it's not in operatingSystems for ${env.product_to_test}"
                }
            }
        }
    }
    parallel tests
}


    properties([
        parameters([

            [
                $class: 'ChoiceParameter',
                choiceType: 'PT_SINGLE_SELECT',
                description: 'Choose the product version to test: PXB8.0, PXB8.4, PXB9.7 OR pxb_innovation',
                name: 'product_to_test',
                script: [
                    $class: 'GroovyScript',
                    script: [
                        classpath: [],
                        sandbox: true,
                        script: 'return ["pxb_80", "pxb_innovation", "pxb_84", "pxb_97"]'
                    ]
                ]
            ],
            [
                $class: 'CascadeChoiceParameter',
                choiceType: 'PT_SINGLE_SELECT',
                description: 'Server to test (filtered by product version)',
                name: 'server_to_test',
                referencedParameters: 'product_to_test',
                script: [
                    $class: 'GroovyScript',
                    script: [
                        classpath: [],
                        sandbox: true,
                        script: '''
                            if (product_to_test == "pxb_80") {
                                return ["ps-80", "ms-80"]
                            }
                            else if (product_to_test == "pxb_84") {
                                return ["ps-84", "ms-84"]
                            }
                            else if (product_to_test == "pxb_97") {
                                return ["ps-97", "ms-97"]
                            }
                            else if (product_to_test == "pxb_innovation") {
                                return ["ps_innovation", "ms_innovation"]
                            }
                            else {
                                return ["ps_innovation", "ms_innovation", "ps-80", "ms-80", "ps-84", "ms-84", "ps-97", "ms-97"]
                            }
                        '''
                    ]
                ]
            ],
            choice(
                choices: ['testing', 'main', 'experimental'],
                description: 'Choose the repo to install packages and run the tests',
                name: 'install_repo'
            ),
            string(
                defaultValue: 'https://github.com/Percona-QA/package-testing.git',
                description: 'repo name',
                name: 'git_repo',
                trim: false
            ),
            string(
                defaultValue: 'master',
                description: 'Branch for package-testing repository',
                name: 'TESTING_BRANCH'
            ),
            choice(
                choices: ['install', 'major_upgrade', 'upgrade', 'kms', 'kmip'],
                description: 'Scenario To Test',
                name: 'scenario_to_test'
            ),
            choice(
                choices: ['NORMAL', 'PRO'],
                description: 'Choose the product to test',
                name: 'REPO_TYPE'
            ),
            choice(
                choices: ['warn', 'enforce', 'off'],
                description: 'PXB SBOM verification. warn: validate the SBOM files when the package ships them, skip when it does not (PXB does not ship them yet). enforce: require them. off: skip entirely.',
                name: 'SBOM_CHECK_MODE'
            ),
            choice(
                choices: ['warn', 'enforce', 'off'],
                description: 'Vulnerability scanning of the SBOM. Gated separately from SBOM_CHECK_MODE so a new upstream CVE in a vendored library does not fail package testing.',
                name: 'SBOM_VULN_MODE'
            ),
            booleanParam(
                defaultValue: true,
                description: 'Install trivy and cyclonedx-cli once, on this Jenkins agent, and run the SBOM schema validation and vulnerability scan there over the files every target collected. Untick to skip them; the checks then report as skipped rather than failing.',
                name: 'SBOM_EXTERNAL_TOOLS'
            )
        ])
    ])

    pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        product_to_test = "${params.product_to_test}"
        node_to_test = "${params.server_to_test}"
        install_repo = "${params.install_repo}"
        server_to_test  = "${params.server_to_test}"
        scenario_to_test = "${params.scenario_to_test}"
        REPO_TYPE = "${params.REPO_TYPE}"
        TESTING_BRANCH = "${params.TESTING_BRANCH}"
        SBOM_CHECK_MODE = "${params.SBOM_CHECK_MODE}"
        SBOM_VULN_MODE = "${params.SBOM_VULN_MODE}"
        SBOM_EXTERNAL_TOOLS = "${params.SBOM_EXTERNAL_TOOLS}"
        SBOM_LICENSE_STRICT = "1"
    }
    options {
        withCredentials(moleculepxbJenkinsCreds())
        timeout(time: 6, unit: 'HOURS')
    }

        stages {
            
            stage('Set Build Name'){
                steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-${server_to_test}-${scenario_to_test}-${REPO_TYPE}"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    deleteDir()
                    git poll: false, branch: "${params.TESTING_BRANCH}", url: "${params.git_repo}"
                }
            }

            stage('Prepare') {
                steps {
                    script {
                        installMoleculeBookwormMysql()
                    }
                }
            }
            stage('RUN TESTS') {
                        steps {
                            script {
                                if (scenario_to_test == 'install') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}" > .env.ENV_VARS
                                        echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
                                    """
                                } else {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_${scenario_to_test}" > .env.ENV_VARS
                                        echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
                                    """
                                }

                                sh """
                                    echo IIT_BILLING_TAG="${product_to_test}_package_testing" >> .env.ENV_VARS
                                """
                                
                                def envMap = loadEnvFile('.env.ENV_VARS')
                                
                                withEnv(envMap) {

                                    def osList
                                    if (product_to_test == "pxb_80") {
                                        osList = pxb80PackageTesting()
                                    } else if (product_to_test == "pxb_84") {
                                        osList = pxb84PackageTesting()
                                    } else if (product_to_test == "pxb_97") {
                                        osList = pxb97PackageTesting()
                                    } else if (product_to_test == "pxb_innovation") {
                                        osList = pxbInnovationPackageTesting()
                                    } else {
                                        error("Unsupported product_to_test: ${product_to_test}")
                                    }

                                    if (server_to_test.startsWith('ms')) {
                                        osList = osList.findAll { !it.endsWith('-arm') }
                                    }

                                    // Every launched platform must send back an SBOM
                                    // collection (runSbomChecks). Scenario names equal
                                    // these entries, and each target labels its
                                    // collection with MOLECULE_SCENARIO_NAME.
                                    env.SBOM_EXPECTED_PLATFORMS = osList.join(',')

                                    if (REPO_TYPE == 'PRO') {
                                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                            script {
                                                moleculeParallelTestPXBALL(pxbAllOS, osList, "molecule/pxb-package-testing/")
                                            }
                                        }
                                    }
                                    else {
                                        moleculeParallelTestPXBALL(pxbAllOS, osList, "molecule/pxb-package-testing/")
                                    }

                                }

                            }
                        }

                        post {
                            always {

                                script{
                                    //sh "ls -la ."
                                    //sh "mkdir ARTIFACTS && cp *.zip ARTIFACTS/"
                                    //sh "ls -la ARTIFACTS/"
                                    //sh "zip -r ${env.BUILD_NUMBER}-ARTIFACTS.zip ARTIFACTS"
                                    archiveArtifacts artifacts: '*.zip', allowEmptyArchive: true

                                    // The SBOM checks run HERE, once per platform, over the
                                    // collections the targets fetched back (*_sbom.zip, from
                                    // tasks/check_pxb_sbom.yml). Only the install playbooks
                                    // collect. In post/always so a failed platform does not
                                    // stop the others from being checked.
                                    if (scenario_to_test == 'install') {
                                        runSbomChecks()
                                    }

                                    // allowEmptyResults: true because only install runs
                                    // produce this file -- upgrade, major_upgrade, kms and
                                    // kmip runs must not fail for lacking it.
                                    junit testResults: 'sbom-junit.xml',
                                          keepLongStdio: true,
                                          allowEmptyResults: true

                                }
                            }
                        }
            }
        }

    post {
        always {
            deleteBuildInstances()
        }
    }
    }


def runSbomChecks() {
    // Tools are installed once, on this agent -- not on every target, where they
    // needed per-AMI workarounds and a 1.4 GB trivy database each. A failed
    // install is caught so the checks below still run and report the missing
    // tool per platform, instead of aborting before any junit exists.
    if (params.SBOM_EXTERNAL_TOOLS.toString() == 'true') {
        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
            if (params.SBOM_VULN_MODE != 'off') {
                installTrivy()
            }
            // rm first, and no "|| true": curl -f does not truncate on an HTTP
            // error, so a failed download must not leave an older binary behind.
            sh '''
                set -e
                ARCH=$(uname -m)
                if [ "$ARCH" = "aarch64" ]; then
                    CDX_ASSET=cyclonedx-linux-arm64
                else
                    CDX_ASSET=cyclonedx-linux-x64
                fi
                rm -rf sbom-tools
                mkdir sbom-tools
                curl -fsSL -o sbom-tools/cyclonedx \
                    https://github.com/CycloneDX/cyclonedx-cli/releases/latest/download/${CDX_ASSET}
                chmod +x sbom-tools/cyclonedx
            '''
        }
    }
    // A failing check fails the build, but the junit step after this still
    // publishes. SBOM_CHECK_MODE, SBOM_VULN_MODE, SBOM_EXTERNAL_TOOLS and
    // SBOM_LICENSE_STRICT come from the pipeline environment.
    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        sh """
            . virtenv/bin/activate
            export SBOM_FETCHED='*_sbom.zip'
            export SBOM_EXPECTED_PLATFORMS='${env.SBOM_EXPECTED_PLATFORMS ?: ''}'
            export CYCLONEDX_BIN="\$PWD/sbom-tools/cyclonedx"
            python -m pytest -v -p no:cacheprovider pytest-tests/test_pxb_sbom.py --junitxml=sbom-junit.xml
        """
    }
}

def deleteBuildInstances(){
    script {
        echo "All tests completed"

        def awsCredentials = [
                sshUserPrivateKey(
                    credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
                    keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
                    passphraseVariable: '',
                    usernameVariable: ''
                ),
                aws(
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                )
        ]

        withCredentials(awsCredentials) {
            def jobName = env.JOB_NAME
            def BUILD_NUMBER = env.BUILD_NUMBER
            jobName.trim()

            echo "Fetched JOB_TO_RUN from environment: '${jobName}'"

            echo "Listing EC2 instances with job-name tag: ${jobName}"
            sh """
            aws ec2 describe-instances --region us-west-2 --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}"  --query "Reservations[].Instances[].InstanceId" --output text
            """

            sh """
            echo "=== EC2 Instances to be cleaned up ==="
            aws ec2 describe-instances --region us-west-2 \\
            --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" \\
            --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" \\
            --output table || echo "No instances found with job-name tag: ${jobName}"
            """

            def instanceIds = sh(
                script: """
                aws ec2 describe-instances --region us-west-2 \\
                --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" "Name=instance-state-name,Values=running" \\
                --query "Reservations[].Instances[].InstanceId" \\
                --output text
                """,
                returnStdout: true
            ).trim()

            if (instanceIds != null && !instanceIds.trim().isEmpty()) {
                echo "Found instances to terminate: ${instanceIds.trim()}"

                sh """
                echo "${instanceIds.trim()}" | xargs -r aws ec2 terminate-instances --instance-ids
                """
            
                sleep(30)
                
                echo "Terminated instances: ${instanceIds.trim()}"
                
                echo "==========================================="

                echo "Verification: Status of terminated instances:"

                sh """
                sleep 5 && aws ec2 describe-instances --instance-ids ${instanceIds} --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" --output table
                """
            
            } else {
                echo "No instances found to terminate"
            }
        }
    }
}

def loadEnvFile(envFilePath) {
    def envMap = []
    def envFileContent = readFile(file: envFilePath).trim().split('\n')
    envFileContent.each { line ->
        if (line && !line.startsWith('#')) {
            def parts = line.split('=')
            if (parts.length == 2) {
                envMap << "${parts[0].trim()}=${parts[1].trim()}"
            }
        }
    }
    return envMap
}
