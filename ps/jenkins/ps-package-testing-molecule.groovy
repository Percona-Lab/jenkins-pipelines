

library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])


def ps90PackageTesting() {
    return [
        'ubuntu-noble',
        'ubuntu-noble-arm'
    ]
}

def ps80PackageTesting() {
    return [
        'debian-11',
        'debian-11-arm',
        'debian-12',
        'debian-12-arm',
        'oracle-8',
        'oracle-9',
        'rhel-9',
        'rhel-8-arm',
        'rhel-9-arm',
        'ubuntu-jammy',
        'ubuntu-jammy-arm',
        'ubuntu-focal',
        'ubuntu-focal-arm',
        'ubuntu-noble',
        'ubuntu-noble-arm',
        'amazon-linux-2023',
        'amazon-linux-2023-arm'
    ]
}

def ps84PackageTesting() {
    return [
        'debian-11',
        'debian-11-arm',
        'debian-12',
        'debian-12-arm',
        'oracle-8',
        'oracle-9',
        'rhel-9',
        'rhel-10',
        'rhel-8-arm',
        'rhel-9-arm',
        'rhel-10-arm',
        'ubuntu-jammy',
        'ubuntu-jammy-arm',
        'ubuntu-focal',
        'ubuntu-focal-arm',
        'ubuntu-noble',
        'ubuntu-noble-arm'
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
        "debian-12"
    ]
}

List allOS = ps90PackageTesting() + ps80PackageTesting() + ps84PackageTesting() + ps57PackageTesting()

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

def installMolecule() {
        sh """
            sudo apt update -y
            sudo apt install -y python3 python3-pip python3-dev python3-venv
            python3 -m venv virtenv
            . virtenv/bin/activate
            python3 --version
            python3 -m pip install --upgrade pip
            python3 -m pip install --upgrade setuptools
            python3 -m pip install --upgrade setuptools-rust
            python3 -m pip install --upgrade PyYaml==5.3.1 molecule==3.3.0 testinfra pytest molecule-ec2==0.3 molecule[ansible] "ansible<10.0.0" "ansible-lint>=5.1.1,<6.0.0" boto3 boto
        """
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
            description: 'Choose the product version to test: PS8.0 OR ps_lts_innovation',
            name: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: 'return ["ps_57", "ps_80", "ps_84", "ps_lts_innovation", "client_test"]'
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
            description: 'Branch name',
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
                        if (product_to_test == "ps_57") {
                            return ["install", "upgrade", "major_upgrade_from", "kmip", "kms"]
                        }
                        else if (product_to_test == "ps_80" || product_to_test == "ps_84") {
                            return ["install", "upgrade", "major_upgrade_to", "kmip", "kms"]
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
            description: 'EOL version or Normal (only available for ps_57)',
            name: 'EOL',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "ps_57") {
                            return ["yes", "no"]
                        }
                        else {
                            return ["no"]
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
        EOL="${params.EOL}"
    }
    options {
        withCredentials(moleculePdpsJenkinsCreds())
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
                    git poll: false, branch: "${params.git_branch}", url: "${params.git_repo}"      }
            }
            stage('Prepare') {
                steps {
                    script {
                        installMolecule()
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
                                else {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_${action_to_test}" > .env.ENV_VARS
                                    """
                                }
                                def envMap = loadEnvFile('.env.ENV_VARS')
                                withEnv(envMap) {
                                    if (product_to_test == "ps_lts_innovation") {
                                        moleculeParallelTestALL(allOS, ps90PackageTesting(), "molecule/ps/")
                                    } 
                                    else if (product_to_test == "ps_57") {
                                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                            moleculeParallelTestALL(allOS, ps57PackageTesting(), "molecule/ps/")
                                        }
                                    }
                                    else if (product_to_test == "ps_80") {
                                        moleculeParallelTestALL(allOS, ps80PackageTesting(), "molecule/ps/")
                                    }
                                    else if (product_to_test == "ps_84") {
                                        moleculeParallelTestALL(allOS, ps84PackageTesting(), "molecule/ps/")
                                    }
                                    else {
                                        error("Unsupported product_to_test: ${product_to_test}")
                                    }
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

