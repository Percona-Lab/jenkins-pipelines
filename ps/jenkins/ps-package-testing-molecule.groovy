
library changelog: false, identifier: "lib@check-pxb-sbom", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])


def ps90PackageTesting() {
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
        'amazon-linux-2023',
        'amazon-linux-2023-arm'
    ]
}

def ps80PackageTesting() {
    return [
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
    ]
}

def ps84PackageTesting() {
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
        'amazon-linux-2023',
        'amazon-linux-2023-arm'
    ]
}

def ps97PackageTesting() {
    return [
        'ubuntu-noble',
        'ubuntu-noble-arm',
        'ubuntu-jammy',
        'ubuntu-jammy-arm',
        'debian-12',
        'debian-12-arm',
        'debian-13',
        'debian-13-arm',
        'oracle-8',
        'oracle-9',
        'rhel-8',
        'rhel-8-arm',
        'rhel-9',
        'rhel-9-arm',
        'rhel-10',
        'rhel-10-arm',
        'rocky-8',
        'rocky-8-arm',
        'rocky-9',
        'rocky-9-arm',
        'ubuntu-resolute',
        'ubuntu-resolute-arm',
        'rocky-8',
        'rocky-8-arm',
        'rocky-9',
        'rocky-9-arm',
        'amazon-linux-2023',
        'amazon-linux-2023-arm'
    ]
}

def ps57PackageTesting() {
    return [
        "debian-10",
        "centos-7",
        "oracle-8",
        "ubuntu-bionic",
        "ubuntu-focal",
        "amazon-linux-2",
        "ubuntu-jammy",
        "oracle-9",
        "debian-11",
        "debian-12",
        "rocky-8",
        "rocky-9"
    ]
}

List allOS = ps90PackageTesting() + ps80PackageTesting() + ps84PackageTesting() + ps57PackageTesting() + ps97PackageTesting()

def moleculeParallelTestALL(allOS, operatingSystems, moleculeDir) {
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
                    echo "Skipping ${os} as it's not in operatingSystems"
                }
            }
        }
    }
    parallel tests
}

// The install playbooks that collect SBOM files (package-testing
// playbooks/ps_{80,84,97,innovation}.yml include tasks/check_sbom.yml).
def sbomCollected() {
    return env.action_to_test == 'install' &&
        ['ps_80', 'ps_84', 'ps_97', 'ps_innovation'].contains(env.product_to_test)
}

def sbomOsList(product) {
    switch (product) {
        case 'ps_innovation': return ps90PackageTesting()
        case 'ps_80': return ps80PackageTesting()
        case 'ps_84': return ps84PackageTesting()
        case 'ps_97': return ps97PackageTesting()
        default: return []
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
                    credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa',
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

properties([
    parameters([
        [
            $class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Choose the product version to test: PS8.0 OR ps_innovation',
            name: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: 'return ["ps_57", "ps_80", "ps_84", "ps_innovation", "ps_97", "client_test"]'
                ]
            ]
        ],

        string(
            defaultValue: 'Percona-QA',
            description: 'Git account name',
            name: 'git_account',
            trim: false
        ),
        string(
            defaultValue: 'master',
            description: 'Git Branch name',
            name: 'git_branch',
            trim: false
        ),
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Action To Test',
            name: 'action_to_test',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "ps57") {
                            return ["install", "upgrade", "major_upgrade", "kmip", "kms"]
                        }
                        else if (product_to_test == "ps_80" || product_to_test == "ps_84" || product_to_test == "ps_97") {
                            return ["install", "upgrade", "major_upgrade", "kmip", "kms"]
                        }
                        else {
                            return ["install", "upgrade", "kmip", "kms"]
                        }
                    '''
                ]
            ]
        ],

        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Install Repo',
            name: 'install_repo',
            referencedParameters: 'action_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            return ["NA"]
                        }
                        else {
                            return ["testing", "main", "experimental"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'from',
            name: 'major_upgrade_from_product',
            referencedParameters: 'action_to_test,product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            if (product_to_test == "ps_80") {
                                return ["ps_80","ps_57", "ps_84"]
                            }
                            else if (product_to_test == "ps_84") {
                                return ["ps_80","ps_84"]
                            }
                            else if (product_to_test == "ps_57") {
                                return ["ps_57", "ps_80"]
                            }
                            else if (product_to_test == "ps_97") {
                                return ["ps_84"]
                            }
                            else {
                                return ["NA"]
                            }
                        }
                        else {
                            return ["NA"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'major upgrade from repo',
            name: 'major_upgrade_from_repo',
            referencedParameters: 'action_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            return ["testing", "main", "experimental"]
                        }
                        else {
                            return ["NA"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'from',
            name: 'major_upgrade_to_product',
            referencedParameters: 'action_to_test,product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            if (product_to_test == "ps_80") {
                                return ["ps_80","ps_57", "ps_84"]
                            }
                            else if (product_to_test == "ps_84") {
                                return ["ps_80","ps_84"]
                            }
                            else if (product_to_test == "ps_57") {
                                return ["ps_57", "ps_80"]
                            }
                            else if (product_to_test == "ps_97") {
                                return ["ps_97"]
                            }
                            else {
                                return ["NA"]
                            }
                        }
                        else {
                            return ["NA"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'major upgrade to repo',
            name: 'major_upgrade_to_repo',
            referencedParameters: 'action_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (action_to_test == "major_upgrade") {
                            return ["testing", "main", "experimental"]
                        }
                        else {
                            return ["NA"]
                        }
                    '''
                ]
            ]
        ],
        choice(
            choices: ['yes', 'no'],
            description: 'check_warnings',
            name: 'check_warnings'
        ),
        choice(
            choices: ['yes', 'no'],
            description: 'Install MySQL Shell',
            name: 'install_mysql_shell'
        ),
        choice(
            choices: ['warn', 'enforce', 'off'],
            description: 'PS SBOM verification (install runs of ps_80, ps_84, ps_97 and ps_innovation). warn: validate the SBOM files when the package ships them, skip when it does not. enforce: require them. off: skip entirely.',
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
        git_repo = "${params.git_repo}"
        install_repo = "${params.install_repo}"
        action_to_test  = "${params.action_to_test}"
        check_warnings = "${params.check_warnings}"
        install_mysql_shell = "${params.install_mysql_shell}"
        EOL="yes"//PS 57 has default EOL to yes
        major_upgrade_from_product = "${params.major_upgrade_from_product}"
        major_upgrade_from_repo = "${params.major_upgrade_from_repo}"
        major_upgrade_to_product = "${params.major_upgrade_to_product}"
        major_upgrade_to_repo = "${params.major_upgrade_to_repo}"
        TESTING_BRANCH = "${params.git_branch}"
        TESTING_GIT_ACCOUNT = "${params.git_account}"
        SBOM_CHECK_MODE = "${params.SBOM_CHECK_MODE}"
        SBOM_VULN_MODE = "${params.SBOM_VULN_MODE}"
        SBOM_EXTERNAL_TOOLS = "${params.SBOM_EXTERNAL_TOOLS}"
        SBOM_LICENSE_STRICT = "1"
    }
    options {
        withCredentials(moleculePdpsJenkinsCreds())
        timeout(time: 6, unit: 'HOURS')
    }
        stages {
            stage('Set Build Name'){
                steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-${action_to_test}"
                    }
                }
            }
            stage('Checkout') {
                steps {
                    deleteDir() 
                    git poll: false, branch: "${params.git_branch}", url: "https://github.com/${params.git_account}/package-testing.git"
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
                                if (action_to_test == 'install') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}" > .env.ENV_VARS
                                    """
                                } 
                                else if (action_to_test == 'upgrade') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_upgrade" > .env.ENV_VARS
                                    """
                                }
                                else if (action_to_test == 'major_upgrade')     {
                                    sh """
                                         echo PLAYBOOK_VAR="${product_to_test}_major_upgrade_to" > .env.ENV_VARS
                                    """
                                }
                                else {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_${action_to_test}" > .env.ENV_VARS
                                    """
                                }
                                
                                sh """
                                    echo IIT_BILLING_TAG="${product_to_test}_package_testing" >> .env.ENV_VARS
                                    echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
                                """

                                // Every launched platform must send back an SBOM
                                // collection (runSbomChecks). Scenario names equal
                                // these entries, and each target labels its
                                // collection with MOLECULE_SCENARIO_NAME. unique():
                                // ps97PackageTesting() lists some platforms twice.
                                if (sbomCollected()) {
                                    env.SBOM_EXPECTED_PLATFORMS = sbomOsList(product_to_test).unique().join(',')
                                }

                                def envMap = loadEnvFile('.env.ENV_VARS')

                                withEnv(envMap) {
                                    if (product_to_test == "ps_innovation") {
                                        moleculeParallelTestALL(allOS, ps90PackageTesting(), "molecule/ps/")
                                    } 
                                    else if (product_to_test == "ps_57") {
                                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                            moleculeParallelTestALL(allOS, ps57PackageTesting(), "molecule/ps/")
                                        }
                                    }
                                    else if (product_to_test == "ps_80") {
                                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                            moleculeParallelTestALL(allOS, ps80PackageTesting(), "molecule/ps/")
                                        }
                                    }
                                    else if (product_to_test == "ps_84") {
                                        moleculeParallelTestALL(allOS, ps84PackageTesting(), "molecule/ps/")
                                    }
                                    else if (product_to_test == "ps_97") {
                                        moleculeParallelTestALL(allOS, ps97PackageTesting(), "molecule/ps/")
                                    }
                                    else {
                                        error("Unsupported product_to_test: ${product_to_test}")
                                    }
                                }
                            }
                        }
                        post {
                            always {
                                script {
                                    // The SBOM checks run HERE, once per platform, over the
                                    // collections the targets fetched back (*_sbom.zip, from
                                    // package-testing tasks/check_sbom.yml). In post/always so a
                                    // failed platform does not stop the others from being checked.
                                    if (sbomCollected()) {
                                        runSbomChecks(product: 'ps')
                                        archiveSbomFiles()
                                    }
                                    // allowEmptyResults: true because only the install runs
                                    // of the products below produce this file.
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
            echo "Pipeline completed."
        }
    }
}

